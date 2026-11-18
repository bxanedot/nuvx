use super::crypto::{self, tests::PhoneKey};
use super::host::*;
use super::replay::{self, Meta, Op, Playlist};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::path::PathBuf;

struct Dir(PathBuf);
impl Drop for Dir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn host() -> (Inner, Dir) {
    let p = std::env::temp_dir().join(format!("nuvx-lan-{}", crypto::b64(&crypto::random_bytes::<8>())));
    std::fs::create_dir_all(&p).unwrap();
    (Inner::load(&p, "Test PC".into(), Some(HashMap::new())), Dir(p))
}

fn post(h: &mut Inner, path: &str, body: Value) -> (u16, Value) {
    let (s, b) = h.handle("POST", path, None, body.to_string().as_bytes());
    (s, serde_json::from_str(&b).unwrap())
}

/// Starts and reveals a pairing as Android does; returns the pairing id, the phone's key and its code.
fn request(h: &mut Inner) -> (String, [u8; 32], String) {
    let phone = PhoneKey::new();
    let (s, start) = post(h, "/lan/pair/start", json!({ "deviceName": "Galaxy S26", "commit": crypto::sha256_hex(&phone.public) }));
    assert_eq!(s, 201);
    let id = start["pairingId"].as_str().unwrap().to_owned();
    let (key, code) = phone.agree(&crypto::unb64(start["hostKey"].as_str().unwrap()).unwrap());
    let (s, _) = post(h, "/lan/pair/reveal", json!({ "pairingId": id, "deviceKey": crypto::b64(&phone.public) }));
    assert_eq!(s, 200);
    (id, key, code)
}

fn paired(h: &mut Inner) -> (String, [u8; 32]) {
    let (id, key, _) = request(h);
    h.approve(&id).unwrap();
    let (s, joined) = post(h, "/lan/pair/claim", json!({ "pairingId": id }));
    assert_eq!(s, 200);
    (joined["deviceId"].as_str().unwrap().to_owned(), key)
}

/// One encrypted call the way the Android interceptor makes it.
#[allow(clippy::too_many_arguments)]
fn call(h: &mut Inner, device: &str, key: &[u8; 32], method: &str, path: &str, body: Value, at: i64, nonce: &str) -> (u16, Value) {
    let sealed = crypto::seal(key, device.as_bytes(), json!({ "m": method, "p": path, "t": at, "n": nonce, "b": body }).to_string().as_bytes());
    let (s, reply) = h.handle("POST", "/lan/v1", Some(device), sealed.as_bytes());
    if s != 200 {
        return (s, Value::Null);
    }
    let opened: Value = serde_json::from_slice(&crypto::open(key, format!("resp:{device}").as_bytes(), &reply).unwrap()).unwrap();
    assert_eq!(opened["n"], nonce, "reply bound to this request");
    (opened["s"].as_u64().unwrap() as u16, opened["b"].clone())
}

fn fresh() -> String {
    crypto::b64(&crypto::random_bytes::<16>())
}

#[test]
fn pairing_needs_the_approval_on_this_computer_and_the_same_code() {
    let (mut h, _d) = host();
    let (id, _, phone_code) = request(&mut h);
    assert_eq!(h.pending(), vec![PendingApproval { id: id.clone(), name: "Galaxy S26".into(), code: phone_code }]);
    assert_eq!(post(&mut h, "/lan/pair/claim", json!({ "pairingId": id })).0, 202, "not before approval");
    h.approve(&id).unwrap();
    assert_eq!(post(&mut h, "/lan/pair/claim", json!({ "pairingId": id })).0, 200);
    assert_eq!(post(&mut h, "/lan/pair/claim", json!({ "pairingId": id })).0, 404, "single use");
    assert_eq!(h.devices().len(), 1);

    let (denied, _, _) = request(&mut h);
    h.deny(&denied);
    assert_eq!(post(&mut h, "/lan/pair/claim", json!({ "pairingId": denied })).0, 403);
    assert_eq!(h.devices().len(), 1);
}

#[test]
fn a_reveal_that_breaks_the_commitment_ends_the_attempt() {
    let (mut h, _d) = host();
    let phone = PhoneKey::new();
    let (_, start) = post(&mut h, "/lan/pair/start", json!({ "deviceName": "x", "commit": crypto::sha256_hex(b"something else") }));
    let id = start["pairingId"].clone();
    assert_eq!(post(&mut h, "/lan/pair/reveal", json!({ "pairingId": id, "deviceKey": crypto::b64(&phone.public) })).0, 401);
    assert_eq!(post(&mut h, "/lan/pair/reveal", json!({ "pairingId": id, "deviceKey": crypto::b64(&phone.public) })).0, 404);
    assert!(h.pending().is_empty());
}

#[test]
fn unpaired_stale_replayed_and_revoked_requests_are_refused() {
    let (mut h, _d) = host();
    let (dev, key) = paired(&mut h);
    assert_eq!(h.handle("POST", "/lan/v1", Some("nobody"), b"garbage").0, 401);
    assert_eq!(h.handle("POST", "/lan/v1", Some(&dev), b"garbage").0, 401, "not sealed with the key");
    assert_eq!(call(&mut h, &dev, &crypto::random_bytes::<32>(), "GET", "/v1/ops?after=0", Value::Null, now_ms(), &fresh()).0, 401, "wrong key");
    assert_eq!(call(&mut h, &dev, &key, "GET", "/v1/ops?after=0", Value::Null, now_ms() - 10 * 60_000, &fresh()).0, 401, "stale");
    let n = fresh();
    assert_eq!(call(&mut h, &dev, &key, "GET", "/v1/ops?after=0", Value::Null, now_ms(), &n).0, 200);
    assert_eq!(call(&mut h, &dev, &key, "GET", "/v1/ops?after=0", Value::Null, now_ms(), &n).0, 401, "replayed nonce");
    assert!(h.revoke(&dev));
    assert_eq!(call(&mut h, &dev, &key, "GET", "/v1/ops?after=0", Value::Null, now_ms(), &fresh()).0, 401, "revoked");
}

#[test]
fn both_directions_offline_edits_duplicates_and_a_restart() {
    let (mut h, d) = host();
    let (dev, key) = paired(&mut h);
    let meta = |t: &str| HashMap::from([(t.to_owned(), Meta { title: format!("Song {t}"), artist: "A".into(), thumbnail: None })]);
    // The computer already has a playlist before the phone ever syncs.
    let (lists, _) = h.sync_local(vec![(Playlist { id: "pc".into(), title: "Desk".into(), tracks: vec!["d1".into()] }, meta("d1"))]);
    assert_eq!(lists.len(), 1);

    // The phone pushes its own playlist (made offline), then retries the very same push.
    let ops = json!({ "ops": [
        { "id": "op-1", "kind": "playlist.create", "entity": "playlist:ph", "payload": { "title": "Phone" }, "clientMs": 1 },
        { "id": "op-2", "kind": "playlist.addTrack", "entity": "playlist:ph", "payload": { "videoId": "p1", "after": null, "title": "Song p1", "artist": "B" }, "clientMs": 2 },
    ]});
    let (s, r1) = call(&mut h, &dev, &key, "POST", "/v1/ops", ops.clone(), now_ms(), &fresh());
    assert_eq!(s, 200);
    let (_, r2) = call(&mut h, &dev, &key, "POST", "/v1/ops", ops, now_ms(), &fresh());
    assert_eq!(r1["seqs"], r2["seqs"], "a retried push changes nothing");
    assert!(h.remote_changed);

    // The computer replays (picking up the phone's list), then adds a track to it.
    let (lists, meta_now) = h.sync_local(vec![(Playlist { id: "pc".into(), title: "Desk".into(), tracks: vec!["d1".into()] }, meta("d1"))]);
    assert_eq!(lists.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), vec!["pc", "ph"]);
    assert_eq!(meta_now["p1"].title, "Song p1", "the phone's track facts arrive with it");
    let (lists, _) = h.sync_local(vec![
        (Playlist { id: "pc".into(), title: "Desk".into(), tracks: vec!["d1".into()] }, meta("d1")),
        (Playlist { id: "ph".into(), title: "Phone".into(), tracks: vec!["p1".into(), "d2".into()] }, meta("d2")),
    ]);
    assert_eq!(lists[1].tracks, vec!["p1", "d2"]);

    // The phone pulls everything: the computer's list and its edit to the phone's list.
    let (_, pulled) = call(&mut h, &dev, &key, "GET", "/v1/ops?after=0", Value::Null, now_ms(), &fresh());
    let replayed = replay::replay(&serde_json::from_value::<Vec<Op>>(pulled["ops"].clone()).unwrap());
    assert_eq!(replayed, lists);

    // A restart keeps the log and the devices.
    let keys = h.device_keys();
    drop(h);
    let mut again = Inner::load(&d.0, "Test PC".into(), Some(keys));
    assert_eq!(again.devices().len(), 1);
    let (_, after) = call(&mut again, &dev, &key, "GET", "/v1/ops?after=0", Value::Null, now_ms(), &fresh());
    assert_eq!(after["ops"], pulled["ops"]);
}

#[test]
fn only_this_network_is_answered() {
    for ok in ["192.168.1.5", "10.0.0.2", "172.16.4.4", "127.0.0.1", "169.254.1.1", "fe80::1", "fd00::5", "::ffff:192.168.0.9"] {
        assert!(is_local_peer(ok.parse().unwrap()), "{ok}");
    }
    for bad in ["8.8.8.8", "1.1.1.1", "2001:4860::8888", "::ffff:8.8.8.8"] {
        assert!(!is_local_peer(bad.parse().unwrap()), "{bad}");
    }
}
