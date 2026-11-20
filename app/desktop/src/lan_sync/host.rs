//! The LAN Sync host: this computer serves the Nuvx Sync v1 op log (`core/sync/src/server.ts`) to paired phones
//! on the same network, inside encrypted envelopes, and takes part in the sync itself through the same
//! diff/replay rules. No account, no internet, no hosted service.

use super::crypto::{self, HostKey};
use super::replay::{self, Meta, NewOp, Op, Playlist};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{IpAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const API_VERSION: u64 = 1;
pub const DEFAULT_PORT: u16 = 47731;
const PAIRING_TTL_MS: i64 = 5 * 60_000;
const MAX_PENDING_PAIRINGS: usize = 4;
const MAX_DEVICES: usize = 20;
const MAX_BODY: usize = 512 * 1024;
const MAX_OPS_PER_PUSH: usize = 200;
const MAX_OP_BYTES: usize = 32 * 1024;
const MAX_OPS: usize = 200_000;
const PULL_LIMIT: usize = 500;
const CLOCK_SKEW_MS: i64 = 2 * 60_000;
const MAX_CONNECTIONS: usize = 16;
const KEYS_VAULT: &str = "lan_sync_keys";

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    #[serde(rename = "createdMs")]
    pub created_ms: i64,
    #[serde(rename = "seenMs")]
    pub seen_ms: i64,
}

#[derive(Default, Serialize, Deserialize)]
struct Saved {
    host_id: String,
    space_id: String,
    #[serde(default)]
    port: u16,
    #[serde(default)]
    devices: Vec<Device>,
    #[serde(default)]
    ops: Vec<Op>,
    #[serde(default)]
    last_synced: Vec<Playlist>,
    #[serde(default)]
    meta: HashMap<String, Meta>,
    #[serde(default)]
    last_sync_ms: i64,
}

enum PairState {
    Committed { commit: String, key: HostKey },
    Waiting { device_key: [u8; 32], code: String },
    Approved { device_id: String },
    Denied,
}

struct Pairing {
    name: String,
    expires_ms: i64,
    state: PairState,
}

/// A pairing waiting for the user to compare codes and approve it here.
#[derive(Clone, Debug, PartialEq)]
pub struct PendingApproval {
    pub id: String,
    pub name: String,
    pub code: String,
}

pub struct Inner {
    path: PathBuf,
    data_dir: PathBuf,
    saved: Saved,
    keys: HashMap<String, [u8; 32]>,
    op_ids: HashSet<String>,
    pairings: HashMap<String, Pairing>,
    nonces: HashMap<String, i64>,
    /// Phones pushed ops this computer has not replayed yet.
    pub remote_changed: bool,
    host_name: String,
    /// Nuvx Connect: what this computer plays, written by the window every frame.
    pub connect_now: Value,
    /// Nuvx Connect: commands from paired phones, carried out by the window.
    pub connect_commands: Vec<super::connect::Command>,
    /// Nuvx Connect is its own opt-in: a phone paired for playlists cannot control playback until this is on.
    pub connect_allowed: bool,
    /// Four digits shown on this computer so a phone can find it on the Wi-Fi without typing an address. Not a
    /// secret: pairing still needs the approval here and the matching six-digit code.
    pub find_code: String,
    uploads: HashMap<String, super::files::Upload>,
    /// This computer's Bluetooth address while the Bluetooth listener runs, told to phones in `/lan/hello`.
    pub bluetooth: Option<String>,
    /// Nuvx Connect the other way: what each paired phone plays, and what this computer asked it to do.
    phones: HashMap<String, super::connect::PhoneNow>,
    phone_commands: HashMap<String, Vec<&'static str>>,
}

impl Inner {
    /// Loads the saved space, or starts a new one. `keys` come from secure storage.
    pub fn load(data_dir: &Path, host_name: String, keys: Option<HashMap<String, [u8; 32]>>) -> Self {
        let path = data_dir.join("lan_sync.json");
        let mut saved: Saved = std::fs::read(&path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        if saved.host_id.is_empty() {
            saved = Saved { host_id: hex(&crypto::random_bytes::<12>()), space_id: hex(&crypto::random_bytes::<12>()), port: DEFAULT_PORT, ..Saved::default() };
        }
        let keys = keys.unwrap_or_else(|| read_keys(data_dir));
        // A device whose key is gone (credential store wiped) cannot talk to us; drop it rather than keep a ghost.
        saved.devices.retain(|d| keys.contains_key(&d.id));
        let op_ids = saved.ops.iter().map(|o| o.op_id.clone()).collect();
        let me = Self { path, data_dir: data_dir.to_owned(), saved, keys, op_ids, pairings: HashMap::new(), nonces: HashMap::new(), remote_changed: false, host_name, connect_now: Value::Null, connect_commands: Vec::new(), connect_allowed: false, find_code: new_find_code(), uploads: HashMap::new(), bluetooth: None, phones: HashMap::new(), phone_commands: HashMap::new() };
        me.persist();
        me
    }

    fn persist(&self) {
        // shortcut: rewrites the whole log on every change, fine for playlist-sized logs; move to SQLite past ~10k ops.
        let tmp = self.path.with_extension("json.tmp");
        let ok = serde_json::to_vec(&self.saved).ok().is_some_and(|b| std::fs::write(&tmp, b).is_ok() && std::fs::rename(&tmp, &self.path).is_ok());
        if !ok {
            log::warn!("lan sync: could not save state to {}", self.path.display());
        }
    }

    fn persist_keys(&self) {
        if cfg!(test) {
            return; // tests hand keys in through `load`; they must never write the user's credential store
        }
        let map: HashMap<&String, String> = self.keys.iter().map(|(id, k)| (id, crypto::b64(k))).collect();
        let text = serde_json::to_vec(&map).unwrap_or_default();
        if let Err(e) = crate::vault::set(KEYS_VAULT, &self.data_dir, &text) {
            log::warn!("lan sync: could not store device keys: {e:#}");
        }
    }

    pub fn port(&self) -> u16 {
        if self.saved.port == 0 { DEFAULT_PORT } else { self.saved.port }
    }

    #[cfg(test)]
    pub fn device_keys(&self) -> HashMap<String, [u8; 32]> {
        self.keys.clone()
    }

    pub fn devices(&self) -> Vec<Device> {
        self.saved.devices.clone()
    }

    /// Paired phones playing something right now, with what they play.
    pub fn phones_playing(&self) -> Vec<(Device, super::connect::PhoneNow)> {
        let fresh = now_ms() - super::connect::PHONE_FRESH_MS;
        self.saved.devices.iter().filter_map(|d| Some((d.clone(), self.phones.get(&d.id).filter(|p| p.at_ms >= fresh)?.clone()))).collect()
    }

    /// Only a recently reporting, still-paired phone may receive a playback command.
    /// In particular, commands issued before a network outage must not play on reconnect.
    pub fn phone_command(&mut self, device_id: &str, action: &'static str) {
        self.expire();
        if !self.keys.contains_key(device_id) || !self.phones.contains_key(device_id) {
            return;
        }
        let queue = self.phone_commands.entry(device_id.to_owned()).or_default();
        if queue.len() < super::connect::MAX_QUEUED && super::connect::PHONE_ACTIONS.contains(&action) {
            queue.push(action);
        }
    }

    pub fn last_sync_ms(&self) -> i64 {
        self.saved.last_sync_ms
    }

    pub fn pending(&mut self) -> Vec<PendingApproval> {
        self.expire();
        let mut out: Vec<_> = self.pairings.iter().filter_map(|(id, p)| match &p.state {
            PairState::Waiting { code, .. } => Some(PendingApproval { id: id.clone(), name: p.name.clone(), code: code.clone() }),
            _ => None,
        }).collect();
        out.sort_by(|a, b| a.id.cmp(&b.id));
        out
    }

    /// Approval requires the six digits shown on the joining phone, not merely clicking a name.
    /// Verify the code and pending state *before* revoking a previous device with the same name.
    pub fn approve(&mut self, pairing_id: &str, entered_code: &str) -> Result<String, &'static str> {
        self.expire();
        let p = self.pairings.get(pairing_id).ok_or("That request has expired.")?;
        let PairState::Waiting { device_key, code } = &p.state else {
            return Err("That request is not waiting for approval.");
        };
        if !entered_code.trim().eq(code) {
            return Err("The code does not match the joining device. Check both screens.");
        }
        let name = p.name.clone();
        let device_key = *device_key;
        // A phone pairing again (its app data was cleared) replaces its old entry instead of leaving a dead twin.
        let twins: Vec<String> = self.saved.devices.iter().filter(|d| d.name == name).map(|d| d.id.clone()).collect();
        if self.saved.devices.len() - twins.len() >= MAX_DEVICES {
            return Err("Too many paired devices. Remove one first.");
        }
        for id in twins {
            self.revoke(&id);
        }
        let device_id = hex(&crypto::random_bytes::<12>());
        self.keys.insert(device_id.clone(), device_key);
        self.saved.devices.push(Device { id: device_id.clone(), name, created_ms: now_ms(), seen_ms: now_ms() });
        self.pairings.get_mut(pairing_id).unwrap().state = PairState::Approved { device_id: device_id.clone() };
        self.persist_keys();
        self.persist();
        Ok(device_id)
    }

    pub fn deny(&mut self, pairing_id: &str) {
        if let Some(p) = self.pairings.get_mut(pairing_id) {
            p.state = PairState::Denied;
        }
    }

    /// Removes a paired device; its key stops working at once.
    pub fn revoke(&mut self, device_id: &str) -> bool {
        let had = self.keys.remove(device_id).is_some();
        self.phones.remove(device_id);
        self.phone_commands.remove(device_id);
        self.saved.devices.retain(|d| d.id != device_id);
        self.persist_keys();
        self.persist();
        had
    }

    fn expire(&mut self) {
        let now = now_ms();
        self.pairings.retain(|_, p| p.expires_ms > now);
        self.nonces.retain(|_, until| *until >= now);
        // A disconnected phone has no current playback state and no pending remote actions.
        self.phones.retain(|id, p| self.keys.contains_key(id) && p.at_ms >= now - super::connect::PHONE_FRESH_MS);
        self.phone_commands.retain(|id, _| self.phones.contains_key(id));
    }

    /// This computer's turn: record its own edits in the log, replay everything, and return the playlists
    /// it should now hold, with the track facts known for them. Same steps as `SyncEngine.syncOnce`.
    pub fn sync_local(&mut self, library: Vec<(Playlist, HashMap<String, Meta>)>) -> (Vec<Playlist>, HashMap<String, Meta>) {
        let deleted: HashSet<String> = self.saved.ops.iter().filter(|o| o.kind == "playlist.delete")
            .map(|o| o.entity.trim_start_matches("playlist:").to_owned()).collect();
        let library: Vec<_> = library.into_iter().map(|(mut p, m)| {
            if deleted.contains(&p.id) {
                p.id = format!("{}-{}", p.id, hex(&crypto::random_bytes::<4>()));
            }
            (p, m)
        }).collect();
        for (_, m) in &library {
            self.saved.meta.extend(m.iter().map(|(k, v)| (k.clone(), v.clone())));
        }
        let ops = replay::diff(&self.saved.last_synced, &library);
        let host = self.saved.host_id.clone();
        for op in ops {
            self.append(&host, &hex(&crypto::random_bytes::<16>()), op, now_ms());
        }
        let result = replay::replay(&self.saved.ops);
        self.saved.last_synced = result.clone();
        self.saved.last_sync_ms = now_ms();
        self.remote_changed = false;
        self.persist();
        (result, self.saved.meta.clone())
    }

    fn append(&mut self, device: &str, op_id: &str, op: NewOp, client_ms: i64) -> u64 {
        if let Some(existing) = self.saved.ops.iter().find(|o| o.op_id == op_id) {
            return existing.seq; // idempotent: a retried op keeps its first sequence number
        }
        let seq = self.saved.ops.last().map_or(1, |o| o.seq + 1);
        if op.kind == "playlist.addTrack"
            && let Some(id) = op.payload.get("videoId").and_then(Value::as_str)
        {
            let m = Meta {
                title: op.payload.get("title").and_then(Value::as_str).unwrap_or("").to_owned(),
                artist: op.payload.get("artist").and_then(Value::as_str).unwrap_or("").to_owned(),
                thumbnail: op.payload.get("thumbnailUrl").and_then(Value::as_str).map(str::to_owned),
            };
            // Local metadata wins over a remote copy of the same track; remote fills in the rest.
            self.saved.meta.entry(id.to_owned()).or_insert(m);
        }
        self.saved.ops.push(Op { seq, op_id: op_id.to_owned(), device_id: device.to_owned(), kind: op.kind.to_owned(), entity: op.entity, payload: op.payload, client_ms });
        self.op_ids.insert(op_id.to_owned());
        seq
    }

    // ---- requests ----

    /// One HTTP request from the network. Returns status and body (JSON, or a sealed envelope as text).
    pub fn handle(&mut self, method: &str, path: &str, device_header: Option<&str>, body: &[u8]) -> (u16, String) {
        self.expire();
        let json_body = || serde_json::from_slice::<Value>(body).ok().filter(Value::is_object);
        match (method, path) {
            ("GET", "/lan/hello") => ok(json!({ "app": "nuvx", "api": API_VERSION, "name": self.host_name, "id": self.saved.host_id, "code": self.find_code, "bt": self.bluetooth })),
            ("POST", "/lan/pair/start") => {
                let Some(b) = json_body() else { return fail(422, "invalid_json", "Body must be one JSON object.") };
                let name = b.get("deviceName").and_then(Value::as_str).map(str::trim).filter(|n| !n.is_empty() && n.chars().count() <= 80);
                let commit = b.get("commit").and_then(Value::as_str).filter(|c| c.len() == 64 && c.chars().all(|ch| ch.is_ascii_hexdigit()));
                let (Some(name), Some(commit)) = (name, commit) else { return fail(422, "invalid_pairing", "deviceName and commit are required.") };
                if self.pairings.len() >= MAX_PENDING_PAIRINGS {
                    return fail(429, "busy", "Too many pairing requests at once. Try again in a few minutes.");
                }
                let id = hex(&crypto::random_bytes::<16>());
                let key = HostKey::new();
                let host_key = crypto::b64(&key.public);
                self.pairings.insert(id.clone(), Pairing { name: name.to_owned(), expires_ms: now_ms() + PAIRING_TTL_MS, state: PairState::Committed { commit: commit.to_lowercase(), key } });
                (201, json!({ "pairingId": id, "hostKey": host_key, "hostId": self.saved.host_id, "hostName": self.host_name }).to_string())
            }
            ("POST", "/lan/pair/reveal") => {
                let Some(b) = json_body() else { return fail(422, "invalid_json", "Body must be one JSON object.") };
                let id = b.get("pairingId").and_then(Value::as_str).unwrap_or("");
                let device_pub = b.get("deviceKey").and_then(Value::as_str).and_then(crypto::unb64);
                let Some(p) = self.pairings.get_mut(id) else { return fail(404, "no_such_pairing", "That pairing request is unknown or has expired.") };
                let PairState::Committed { commit, key } = &p.state else { return fail(409, "already_revealed", "This pairing already has a key.") };
                let Some(device_pub) = device_pub.filter(|k| &crypto::sha256_hex(k) == commit) else {
                    self.pairings.remove(id); // a wrong reveal ends the attempt: no second guess at the commitment
                    return fail(401, "bad_commit", "The key does not match what this device committed to.");
                };
                let Some((device_key, code)) = key.agree(&device_pub) else {
                    self.pairings.remove(id);
                    return fail(422, "bad_key", "Not a valid key.");
                };
                p.state = PairState::Waiting { device_key, code };
                ok(json!({ "ok": true }))
            }
            ("POST", "/lan/pair/claim") => {
                let Some(b) = json_body() else { return fail(422, "invalid_json", "Body must be one JSON object.") };
                let id = b.get("pairingId").and_then(Value::as_str).unwrap_or("");
                let proof = b.get("proof").and_then(Value::as_str).filter(|s| s.len() <= 128).unwrap_or("");
                let Some(p) = self.pairings.get(id) else { return fail(404, "no_such_pairing", "That pairing request is unknown or has expired.") };
                let key = match &p.state {
                    PairState::Waiting { device_key, .. } => device_key,
                    PairState::Approved { device_id } => match self.keys.get(device_id) {
                        Some(k) => k,
                        None => return fail(401, "unauthorized", "This pairing is no longer authorized."),
                    },
                    PairState::Denied => {
                        self.pairings.remove(id);
                        return fail(403, "denied", "The computer declined this device.");
                    }
                    PairState::Committed { .. } => return (202, json!({ "pending": true }).to_string()),
                };
                // The public pairing id alone cannot claim a credential. Only the phone that
                // derived the ECDH key can prove possession; AAD also binds it to this attempt.
                let aad = format!("nuvx-pair-claim:{id}");
                if crypto::open(key, aad.as_bytes(), proof).as_deref() != Some(b"claim" as &[u8]) {
                    return fail(401, "bad_proof", "The claiming device did not prove possession of its pairing key.");
                }
                match &p.state {
                    PairState::Approved { device_id } => {
                        let reply = json!({ "deviceId": device_id, "spaceId": self.saved.space_id, "hostId": self.saved.host_id, "hostName": self.host_name });
                        self.pairings.remove(id); // single use, even if proof replayed
                        ok(reply)
                    }
                    _ => (202, json!({ "pending": true }).to_string()),
                }
            }
            ("POST", "/lan/v1") => self.sealed(device_header.unwrap_or(""), body),
            _ => fail(404, "not_found", "Not found."),
        }
    }

    fn sealed(&mut self, device_id: &str, body: &[u8]) -> (u16, String) {
        let unauthorized = || fail(401, "unauthorized", "This device is not paired with this computer.");
        let Some(key) = self.keys.get(device_id).copied() else { return unauthorized() };
        let Some(plain) = std::str::from_utf8(body).ok().and_then(|t| crypto::open(&key, device_id.as_bytes(), t)) else { return unauthorized() };
        let Some(req) = serde_json::from_slice::<Value>(&plain).ok() else { return unauthorized() };
        let nonce = req.get("n").and_then(Value::as_str).unwrap_or("").to_owned();
        let at = req.get("t").and_then(Value::as_i64).unwrap_or(0);
        let now = now_ms();
        // Replay protection: fresh timestamps only, and each nonce once within the window.
        if nonce.len() < 16 || (at - now).abs() > CLOCK_SKEW_MS || self.nonces.contains_key(&nonce) {
            return unauthorized();
        }
        if self.nonces.len() > 10_000 {
            self.nonces.clear(); // bounded; the timestamp window still stops anything older
        }
        self.nonces.insert(nonce.clone(), now + 2 * CLOCK_SKEW_MS);
        if let Some(d) = self.saved.devices.iter_mut().find(|d| d.id == device_id)
            && d.seen_ms < now - 60_000
        {
            d.seen_ms = now;
        }
        let method = req.get("m").and_then(Value::as_str).unwrap_or("");
        let target = req.get("p").and_then(Value::as_str).unwrap_or("");
        let (path, query) = target.split_once('?').unwrap_or((target, ""));
        let (status, reply) = self.route(device_id, method, path, query, req.get("b").cloned().unwrap_or(Value::Null));
        let envelope = json!({ "s": status, "n": nonce, "b": reply }).to_string();
        (200, crypto::seal(&key, format!("resp:{device_id}").as_bytes(), envelope.as_bytes()))
    }

    fn route(&mut self, device: &str, method: &str, path: &str, query: &str, body: Value) -> (u16, Value) {
        let err = |s: u16, e: &str, m: &str| (s, json!({ "error": e, "message": m }));
        match (method, path) {
            ("POST", "/v1/ops") => {
                let Some(list) = body.get("ops").and_then(Value::as_array).filter(|l| !l.is_empty() && l.len() <= MAX_OPS_PER_PUSH) else {
                    return err(422, "invalid_ops", "ops must hold 1 to 200 operations.");
                };
                let mut rows = Vec::new();
                for raw in list {
                    let field = |k: &str, max: usize| raw.get(k).and_then(Value::as_str).map(str::trim).filter(|v| !v.is_empty() && v.len() <= max).map(str::to_owned);
                    let payload = raw.get("payload").cloned().unwrap_or(Value::Null);
                    let (Some(id), Some(kind), Some(entity)) = (field("id", 64), field("kind", 64), field("entity", 200)) else {
                        return err(422, "invalid_op", "Each op needs id, kind, entity and a payload under 32 KB.");
                    };
                    if payload.to_string().len() > MAX_OP_BYTES {
                        return err(422, "invalid_op", "Each op needs id, kind, entity and a payload under 32 KB.");
                    }
                    rows.push((id, kind, entity, payload, raw.get("clientMs").and_then(Value::as_i64).unwrap_or_else(now_ms)));
                }
                if self.saved.ops.len() + rows.len() > MAX_OPS {
                    return err(413, "space_full", "This sync log is full.");
                }
                let mut seqs = Vec::new();
                let mut fresh = false;
                for (id, kind, entity, payload, client_ms) in rows {
                    fresh |= !self.op_ids.contains(&id);
                    // Unknown kinds are stored and passed on like the server does; replay skips them.
                    let kind: &'static str = match kind.as_str() {
                        "playlist.create" => "playlist.create",
                        "playlist.rename" => "playlist.rename",
                        "playlist.delete" => "playlist.delete",
                        "playlist.addTrack" => "playlist.addTrack",
                        "playlist.moveTrack" => "playlist.moveTrack",
                        "playlist.removeTrack" => "playlist.removeTrack",
                        _ => {
                            let seq = self.append_raw(device, &id, kind, entity, payload, client_ms);
                            seqs.push(seq);
                            continue;
                        }
                    };
                    seqs.push(self.append(device, &id, NewOp { kind, entity, payload }, client_ms));
                }
                if fresh {
                    self.remote_changed = true;
                    self.persist();
                }
                (200, json!({ "seqs": seqs }))
            }
            ("GET", "/v1/ops") => {
                let after: u64 = query.split('&').find_map(|kv| kv.strip_prefix("after=")).and_then(|v| v.parse().ok()).unwrap_or(0);
                let page: Vec<&Op> = self.saved.ops.iter().filter(|o| o.seq > after).take(PULL_LIMIT).collect();
                let more = page.len() == PULL_LIMIT;
                (200, json!({ "api": API_VERSION, "ops": page, "more": more }))
            }
            ("GET", "/v1/devices") => {
                let mut devices: Vec<Value> = vec![json!({ "id": self.saved.host_id, "name": self.host_name, "createdMs": 0, "seenMs": now_ms() })];
                devices.extend(self.saved.devices.iter().map(|d| serde_json::to_value(d).unwrap_or(Value::Null)));
                (200, json!({ "devices": devices, "you": device }))
            }
            ("DELETE", "/v1/space") => {
                // A phone leaving with "delete server data" only removes itself: this computer's playlists stay.
                self.revoke(device);
                (200, json!({ "ok": true }))
            }
            ("DELETE", p) if p.starts_with("/v1/devices/") => {
                let target = &p["/v1/devices/".len()..];
                if target == self.saved.host_id {
                    return err(409, "host", "Remove the phone from the computer instead.");
                }
                if self.revoke(target) { (200, json!({ "ok": true })) } else { err(404, "no_such_device", "That device is not paired.") }
            }
            ("GET", "/v1/files") => (200, super::files::list()),
            ("POST", p) if p.starts_with("/v1/files/") => {
                let incoming = self.data_dir.join("incoming");
                match super::files::receive(&incoming, &mut self.uploads, device, &p["/v1/files/".len()..], &body) {
                    Ok(reply) => (200, reply),
                    Err((status, e)) => err(status, e, "That piece was refused; send the song again."),
                }
            }
            ("GET", p) if p.starts_with("/v1/files/") => match super::files::chunk(&p["/v1/files/".len()..], query) {
                Ok(piece) => (200, piece),
                Err((status, e)) => err(status, e, "That download is not here."),
            },
            // A phone reports what it plays and takes this computer's commands. Not behind Allow remote control:
            // that switch is about phones controlling this computer.
            ("POST", "/v1/connect/phone") => {
                match super::connect::PhoneNow::parse(&body, now_ms()) {
                    Some(now) => self.phones.insert(device.to_owned(), now),
                    None => self.phones.remove(device),
                };
                let commands = self.phone_commands.remove(device).unwrap_or_default();
                (200, json!({ "commands": commands }))
            }
            ("GET" | "POST", "/v1/connect") if !self.connect_allowed => err(403, "connect_off", "Remote control is turned off on this computer."),
            ("GET", "/v1/connect") => (200, if self.connect_now.is_null() { json!({ "track": null, "playing": false }) } else { self.connect_now.clone() }),
            ("POST", "/v1/connect") => {
                let Some(command) = super::connect::Command::parse(&body) else {
                    return err(422, "bad_command", "Unknown or invalid command.");
                };
                if self.connect_commands.len() >= super::connect::MAX_QUEUED {
                    return err(429, "busy", "Too many commands at once.");
                }
                log::info!("connect: {device} sent {command:?}");
                self.connect_commands.push(command);
                (202, json!({ "queued": true }))
            }
            _ => err(404, "not_found", "Not found."),
        }
    }

    fn append_raw(&mut self, device: &str, op_id: &str, kind: String, entity: String, payload: Value, client_ms: i64) -> u64 {
        if let Some(existing) = self.saved.ops.iter().find(|o| o.op_id == op_id) {
            return existing.seq;
        }
        let seq = self.saved.ops.last().map_or(1, |o| o.seq + 1);
        self.saved.ops.push(Op { seq, op_id: op_id.to_owned(), device_id: device.to_owned(), kind, entity, payload, client_ms });
        self.op_ids.insert(op_id.to_owned());
        seq
    }
}

fn read_keys(data_dir: &Path) -> HashMap<String, [u8; 32]> {
    let Some(raw) = crate::vault::get(KEYS_VAULT, data_dir) else { return HashMap::new() };
    let map: HashMap<String, String> = serde_json::from_slice(&raw).unwrap_or_default();
    map.into_iter().filter_map(|(id, k)| Some((id, crypto::unb64(&k)?.try_into().ok()?))).collect()
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn ok(v: Value) -> (u16, String) {
    (200, v.to_string())
}

fn fail(status: u16, error: &str, message: &str) -> (u16, String) {
    (status, json!({ "error": error, "message": message }).to_string())
}

/// Only devices on this network: private, link-local and loopback addresses. Never the internet.
pub fn is_local_peer(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_private() || v4.is_loopback() || v4.is_link_local(),
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_local_peer(IpAddr::V4(v4));
            }
            let first = v6.segments()[0];
            v6.is_loopback() || (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80
        }
    }
}

fn new_find_code() -> String {
    format!("{:04}", u16::from_le_bytes(crypto::random_bytes::<2>()) % 10_000)
}

/// The address phones should type. The outward route first (no packet is sent); when that runs through a VPN or
/// proxy adapter (e.g. 198.18.0.1), this computer's own addresses, home networks (192.168/16) first.
pub fn lan_address() -> Option<IpAddr> {
    let routed = std::net::UdpSocket::bind("0.0.0.0:0").ok().and_then(|s| {
        s.connect("192.0.2.1:9").ok()?;
        s.local_addr().ok().map(|a| a.ip())
    });
    let usable = |ip: &IpAddr| matches!(ip, IpAddr::V4(v4) if v4.is_private());
    if let Some(ip) = routed.filter(usable) {
        return Some(ip);
    }
    let name = std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).ok()?;
    let mut own: Vec<IpAddr> = std::net::ToSocketAddrs::to_socket_addrs(&(name.as_str(), 0)).ok()?.map(|a| a.ip()).filter(usable).collect();
    own.sort_by_key(|ip| match ip {
        IpAddr::V4(v4) if v4.octets()[0] == 192 => 0,
        IpAddr::V4(v4) if v4.octets()[0] == 172 => 1,
        _ => 2,
    });
    own.into_iter().next()
}

/// Serves until `stop` is set. Binds every interface but answers only local peers.
pub fn serve(inner: Arc<Mutex<Inner>>, stop: Arc<AtomicBool>, wake: impl Fn() + Send + Sync + 'static) -> std::io::Result<u16> {
    let start = inner.lock().unwrap_or_else(|e| e.into_inner()).port();
    let listener = (0..10).find_map(|i| TcpListener::bind(("0.0.0.0", start + i)).ok())
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::AddrInUse, "no free port for LAN Sync"))?;
    let port = listener.local_addr()?.port();
    {
        let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
        if g.saved.port != port {
            g.saved.port = port;
            g.persist();
        }
    }
    listener.set_nonblocking(true)?;
    let wake: Arc<dyn Fn() + Send + Sync> = Arc::new(wake);
    // Bluetooth carries the same requests for phones off the Wi-Fi; a computer without Bluetooth just skips it.
    let bt = super::bluetooth::serve(inner.clone(), stop.clone(), wake.clone());
    inner.lock().unwrap_or_else(|e| e.into_inner()).bluetooth = bt;
    let busy = Arc::new(AtomicUsize::new(0));
    std::thread::Builder::new().name("lan-sync".into()).spawn(move || {
        while !stop.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((stream, peer)) => {
                    if !is_local_peer(peer.ip()) || busy.load(Ordering::Relaxed) >= MAX_CONNECTIONS {
                        continue; // dropped unanswered
                    }
                    busy.fetch_add(1, Ordering::Relaxed);
                    let (inner, wake, busy) = (inner.clone(), wake.clone(), busy.clone());
                    std::thread::spawn(move || {
                        if let Err(e) = connection(stream, &inner, &*wake) {
                            log::debug!("lan sync: {e}");
                        }
                        busy.fetch_sub(1, Ordering::Relaxed);
                    });
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(100)),
                Err(e) => {
                    log::warn!("lan sync: accept failed: {e}");
                    std::thread::sleep(Duration::from_millis(500));
                }
            }
        }
    })?;
    Ok(port)
}

fn connection(stream: TcpStream, inner: &Mutex<Inner>, wake: &dyn Fn()) -> std::io::Result<()> {
    stream.set_nonblocking(false)?;
    stream.set_read_timeout(Some(Duration::from_secs(10)))?;
    stream.set_write_timeout(Some(Duration::from_secs(10)))?;
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut out = stream;
    respond(&mut reader, &mut out, inner, wake).map(|_| ())
}

/// Reads one HTTP request from `reader`, answers it on `out`. False when the peer closed before a request:
/// Bluetooth keeps one connection for many requests and stops there. Wi-Fi uses one request per connection.
pub(super) fn respond(reader: &mut impl BufRead, out: &mut impl Write, inner: &Mutex<Inner>, wake: &dyn Fn()) -> std::io::Result<bool> {
    let mut line = String::new();
    if reader.by_ref().take(8192).read_line(&mut line)? == 0 {
        return Ok(false);
    }
    let mut parts = line.split_whitespace();
    let (method, path) = (parts.next().unwrap_or("").to_owned(), parts.next().unwrap_or("").to_owned());
    let (mut length, mut device) = (0usize, None);
    for _ in 0..64 {
        let mut h = String::new();
        if reader.by_ref().take(8192).read_line(&mut h)? == 0 || h.trim().is_empty() {
            break;
        }
        if let Some((k, v)) = h.split_once(':') {
            match k.trim().to_ascii_lowercase().as_str() {
                "content-length" => length = v.trim().parse().unwrap_or(usize::MAX),
                "x-nuvx-device" => device = Some(v.trim().to_owned()),
                _ => {}
            }
        }
    }
    let (status, body) = if length > MAX_BODY {
        fail(413, "request_too_large", "Request body is too large.")
    } else {
        let mut body = vec![0; length];
        reader.read_exact(&mut body)?;
        let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
        let reply = g.handle(&method, &path, device.as_deref(), &body);
        let notify = g.remote_changed || !g.pairings.is_empty() || !g.connect_commands.is_empty();
        drop(g);
        if notify {
            wake();
        }
        reply
    };
    let reason = match status { 200 => "OK", 201 => "Created", 202 => "Accepted", 401 => "Unauthorized", 403 => "Forbidden", 404 => "Not Found", _ => "Error" };
    write!(out, "HTTP/1.1 {status} {reason}
content-type: application/json
cache-control: no-store
content-length: {}

{body}", body.len())?;
    out.flush()?;
    Ok(true)
}

