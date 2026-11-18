//! Settings → Nuvx Sync → Local Wi-Fi Sync, and the per-frame step that keeps Nuvx playlists in step with the
//! LAN Sync log (`crate::lan_sync`). Off by default; nothing listens on the network until the user turns it on.

use super::pages::setting_row;
use super::widgets::pill;
use super::{App, LocalPlaylist};
use crate::lan_sync::host::{self, Inner};
use crate::lan_sync::replay::{Meta, Playlist};
use crate::model::{Link, Thumb, Track};
use crate::theme;
use egui::{RichText, Ui};
use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

pub(super) struct LanSync {
    pub enabled: bool,
    /// Nuvx Connect: paired phones may control playback here. Off until turned on in its own settings tab.
    pub connect: bool,
    inner: Option<Arc<Mutex<Inner>>>,
    stop: Option<Arc<AtomicBool>>,
    port: Option<u16>,
    error: Option<String>,
    synced: u64,
}

impl LanSync {
    pub fn new(enabled: bool, connect: bool) -> Self {
        Self { enabled, connect, inner: None, stop: None, port: None, error: None, synced: 0 }
    }
}

impl Drop for LanSync {
    fn drop(&mut self) {
        if let Some(stop) = &self.stop {
            stop.store(true, Ordering::Relaxed);
        }
    }
}

fn fingerprint(lists: &[LocalPlaylist]) -> u64 {
    let mut h = std::collections::hash_map::DefaultHasher::new();
    for p in lists {
        p.id.hash(&mut h);
        p.title.hash(&mut h);
        for t in &p.tracks {
            t.id.hash(&mut h);
        }
    }
    h.finish()
}

fn to_sync(p: &LocalPlaylist) -> (Playlist, HashMap<String, Meta>) {
    let meta = p.tracks.iter().map(|t| (t.id.clone(), Meta {
        title: t.title.clone(),
        artist: t.artist_line(),
        thumbnail: t.thumbs.iter().max_by_key(|th| th.width).map(|th| th.url.clone()),
    })).collect();
    (Playlist { id: p.id.clone(), title: p.title.clone(), tracks: p.tracks.iter().map(|t| t.id.clone()).collect() }, meta)
}

/// Keeps the richer local copy of a track when this computer already has it, like `NuvxSync.toLocal`.
fn to_local(synced: Vec<Playlist>, meta: &HashMap<String, Meta>, current: &[LocalPlaylist]) -> Vec<LocalPlaylist> {
    let mut known: HashMap<&str, &Track> = HashMap::new();
    for p in current {
        for t in &p.tracks {
            known.entry(t.id.as_str()).or_insert(t);
        }
    }
    synced.into_iter().map(|p| LocalPlaylist {
        artwork: current.iter().find(|c| c.id == p.id).and_then(|c| c.artwork.clone()),
        tracks: p.tracks.iter().map(|id| known.get(id.as_str()).map(|t| (*t).clone()).unwrap_or_else(|| {
            let m = meta.get(id).cloned().unwrap_or_default();
            Track {
                id: id.clone(),
                title: m.title,
                artists: if m.artist.is_empty() { vec![] } else { vec![Link { name: m.artist, id: None }] },
                album: None,
                duration: None,
                thumbs: m.thumbnail.map(|url| vec![Thumb { url, width: 544, height: 544 }]).unwrap_or_default(),
                track_nr: None,
                plays: None,
                music_video: false,
            }
        })).collect(),
        id: p.id,
        title: p.title,
    }).collect()
}

impl App {
    fn lan_start(&mut self, ctx: &egui::Context) {
        let inner = Arc::new(Mutex::new(Inner::load(self.backend.data_dir(), host_name(), None)));
        let stop = Arc::new(AtomicBool::new(false));
        let wake = ctx.clone();
        match host::serve(inner.clone(), stop.clone(), move || wake.request_repaint()) {
            Ok(port) => {
                self.lan.port = Some(port);
                self.lan.error = None;
                self.lan.inner = Some(inner);
                self.lan.stop = Some(stop);
                self.lan.synced = 0; // first frame: record this computer's playlists in the log
            }
            Err(e) => self.lan.error = Some(format!("Couldn't start: {e}")),
        }
    }

    fn lan_stop(&mut self) {
        if let Some(stop) = self.lan.stop.take() {
            stop.store(true, Ordering::Relaxed);
        }
        self.lan.inner = None;
        self.lan.port = None;
    }

    /// Each frame: start or stop the host, and sync when this computer's playlists or a phone's pushes changed.
    pub(super) fn lan_tick(&mut self, ctx: &egui::Context) {
        if !self.lan.enabled {
            if self.lan.inner.is_some() {
                self.lan_stop();
            }
            return;
        }
        if self.lan.inner.is_none() {
            if self.lan.error.is_none() {
                self.lan_start(ctx);
            }
            return;
        }
        let Some(inner) = self.lan.inner.clone() else { return };
        let commands = {
            let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
            g.connect_allowed = self.lan.connect;
            g.connect_now = if self.lan.connect { self.connect_snapshot() } else { serde_json::Value::Null };
            std::mem::take(&mut g.connect_commands)
        };
        for command in commands {
            self.connect_apply(command);
        }
        let fp = fingerprint(&self.local_playlists);
        let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
        if fp == self.lan.synced && !g.remote_changed {
            return;
        }
        let (lists, meta) = g.sync_local(self.local_playlists.iter().map(to_sync).collect());
        drop(g);
        let next = to_local(lists, &meta, &self.local_playlists);
        let after = fingerprint(&next);
        if after != fp {
            let gone: Vec<String> = self.local_playlists.iter().filter(|p| !next.iter().any(|n| n.id == p.id)).map(|p| p.id.clone()).collect();
            self.local_playlists = next;
            for id in gone {
                self.lists.forget(&id);
                if let Some(library) = &mut self.library {
                    library.playlists.retain(|card| card.target != super::Target::Playlist(id.clone()));
                }
            }
            for id in self.local_playlists.iter().rev().map(|p| p.id.clone()).collect::<Vec<_>>() {
                self.refresh_local_playlist(&id);
            }
        }
        self.lan.synced = after;
    }
}

impl super::App {
    /// What a paired phone sees in Nuvx Connect.
    fn connect_snapshot(&self) -> serde_json::Value {
        let track = self.current().map(|t| serde_json::json!({
            "id": t.id, "title": t.title, "artist": t.artist_line(), "durationMs": t.duration.map(|s| u64::from(s) * 1000).or_else(|| self.audio.status.duration().map(|d| d.as_millis() as u64)),
        }));
        serde_json::json!({
            "track": track,
            "playing": self.playing() && self.resume_at.is_none(),
            "ended": self.playback_ended(),
            "positionMs": self.audio.status.position().as_millis() as u64,
        })
    }

    /// The last song in the queue played to its end and nothing follows: the phone's queue takes over from here.
    fn playback_ended(&self) -> bool {
        let Some(total) = self.audio.status.duration() else { return false };
        !self.playing() && self.resume_at.is_none() && !total.is_zero()
            && self.audio.status.position() + std::time::Duration::from_millis(1_500) >= total
            && self.queue.upcoming().is_none()
    }

    fn connect_apply(&mut self, command: crate::lan_sync::connect::Command) {
        use crate::lan_sync::connect::Command;
        match command {
            Command::Toggle => self.toggle(),
            Command::Play => if !self.playing() { self.toggle() },
            Command::Pause => if self.playing() { self.toggle() },
            Command::Next => self.next(),
            Command::Prev => self.prev(),
            Command::Seek(ms) => self.seek_to(std::time::Duration::from_millis(ms)),
            Command::Load { tracks, position_ms, paused } => {
                let tracks: Vec<crate::model::Track> = tracks.into_iter().map(|i| crate::model::Track {
                    id: i.id, title: i.title, artists: vec![crate::model::Link { name: i.artist, id: None }],
                    album: None, duration: None, thumbs: vec![crate::model::Thumb { url: i.art, width: 544, height: 544 }],
                    track_nr: None, plays: None, music_video: false,
                }).collect();
                let at = std::time::Duration::from_millis(position_ms);
                if !self.queue.replace(tracks, 0, None) {
                    return;
                }
                self.play_pending = None;
                if paused {
                    // Ready where the phone was; Play (from either side) starts it there.
                    self.audio.send(crate::audio::AudioCmd::Stop);
                    self.audio.status.position_ms.store(position_ms, std::sync::atomic::Ordering::Relaxed);
                    self.resume_at = Some(at);
                    self.request_art();
                } else {
                    self.recoveries = 0;
                    self.play_current_at(at, true, false, false);
                }
            }
        }
    }
}

fn host_name() -> String {
    std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).ok().filter(|n| !n.trim().is_empty()).unwrap_or_else(|| "This computer".into())
}

fn ago(ms: i64) -> String {
    if ms <= 0 {
        return "Never".into();
    }
    let s = (host::now_ms() - ms).max(0) / 1000;
    match s {
        0..=59 => "Just now".into(),
        60..=3599 => format!("{} min ago", s / 60),
        3600..=86_399 => format!("{} h ago", s / 3600),
        _ => format!("{} days ago", s / 86_400),
    }
}

pub(super) fn settings(app: &mut App, ui: &mut Ui) {
    let mut on = app.lan.enabled;
    setting_row(ui, "Local Wi-Fi Sync", Some("Sync Nuvx playlists straight with your phone on the same Wi-Fi. No account, no internet, no server."), |ui| {
        super::pages::toggle(ui, &mut on, "Local Wi-Fi Sync");
    });
    if on != app.lan.enabled {
        app.lan.enabled = on;
        app.lan.error = None;
    }
    if !app.lan.enabled {
        return;
    }
    if let Some(e) = app.lan.error.clone() {
        setting_row(ui, "Not running", Some(&e), |ui| {
            if pill(ui, "Try again", None, false).clicked() {
                app.lan.error = None;
            }
        });
        return;
    }
    let Some(inner) = app.lan.inner.clone() else { return };
    let address = address_text(app.lan.port);
    let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
    pair_row(ui, &g.find_code, &address);
    for p in g.pending() {
        let desc = format!("Check that the phone shows the same code before you approve: {} {}", &p.code[..3], &p.code[3..]);
        let mut choice = None;
        setting_row(ui, &format!("{} wants to sync", p.name), Some(&desc), |ui| {
            if pill(ui, "Approve", None, true).clicked() {
                choice = Some(true);
            }
            ui.add_space(6.0);
            if pill(ui, "Decline", None, false).clicked() {
                choice = Some(false);
            }
        });
        match choice {
            Some(true) => {
                if let Err(e) = g.approve(&p.id) {
                    app.toast(e.to_owned());
                }
            }
            Some(false) => g.deny(&p.id),
            None => {}
        }
    }
    let devices = g.devices();
    let last = g.last_sync_ms();
    if devices.is_empty() {
        setting_row(ui, "Paired devices", Some("None yet."), |_| {});
    }
    for d in devices {
        let mut remove = false;
        setting_row(ui, &d.name, Some(&format!("Paired device · last seen {}", ago(d.seen_ms).to_lowercase())), |ui| {
            remove = pill(ui, "Remove", None, false).clicked();
        });
        if remove {
            g.revoke(&d.id);
        }
    }
    drop(g);
    let mut now = false;
    setting_row(ui, "Last sync", Some(&format!("{} · Syncs by itself whenever a paired phone connects.", ago(last))), |ui| {
        now = pill(ui, "Sync now", None, false).clicked();
    });
    if now {
        app.lan.synced = 0;
    }
    ui.ctx().request_repaint_after(std::time::Duration::from_secs(2)); // new pairing requests show up without a click
}

/// How a phone finds this computer: the four-digit code, or the address for networks where the search fails.
fn pair_row(ui: &mut Ui, code: &str, address: &str) {
    let desc = format!(
        "On your phone: Settings → Sync & Connect → Local Wi-Fi Sync, and enter this code (or the address {address}). If Windows asks, allow nuvx on private networks."
    );
    setting_row(ui, "Pair a new device", Some(&desc), |ui| {
        ui.label(RichText::new(code).font(theme::semibold(22.0)).color(theme::text()));
    });
}

// shortcut: looks the address up on every repaint of these two pages (rare, and the lookup is local); cache it if it shows in a profile.
fn address_text(port: Option<u16>) -> String {
    match (host::lan_address(), port) {
        (Some(ip), Some(port)) => format!("{ip}:{port}"),
        (None, _) => "Not connected to a network".into(),
        _ => "Starting…".into(),
    }
}

/// Settings → Sync & Connect, the Connect half: whether paired phones may play through this computer.
pub(super) fn connect_settings(app: &mut App, ui: &mut Ui) {
    let mut on = app.lan.connect;
    setting_row(ui, "Allow remote control", Some("On a paired phone, pick this computer with the speaker button in the player: its music then plays here and the phone's player controls it. Only phones paired above, on this network, encrypted."), |ui| {
        super::pages::toggle(ui, &mut on, "Allow remote control");
    });
    app.lan.connect = on;
    if app.lan.connect && !app.lan.enabled {
        setting_row(ui, "Local Wi-Fi Sync is off", Some("Turn it on above and pair your phone; Nuvx Connect uses that pairing."), |_| {});
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(id: &str, title: &str) -> Track {
        Track { id: id.into(), title: title.into(), artists: vec![], album: Some(Link { name: "Album".into(), id: None }), duration: Some(200), thumbs: vec![], track_nr: None, plays: None, music_video: false }
    }

    #[test]
    fn synced_lists_keep_the_richer_local_tracks_and_artwork() {
        let current = vec![LocalPlaylist { id: "a".into(), title: "A".into(), tracks: vec![track("1", "Local")], artwork: Some("art".into()) }];
        let meta = HashMap::from([("2".to_owned(), Meta { title: "Remote".into(), artist: "R".into(), thumbnail: Some("t".into()) })]);
        let out = to_local(vec![Playlist { id: "a".into(), title: "A2".into(), tracks: vec!["2".into(), "1".into()] }], &meta, &current);
        assert_eq!(out[0].title, "A2");
        assert_eq!(out[0].artwork.as_deref(), Some("art"));
        assert_eq!(out[0].tracks[0].title, "Remote");
        assert_eq!(out[0].tracks[0].artists[0].name, "R");
        assert_eq!(out[0].tracks[1].duration, Some(200), "the local copy keeps its duration and album");
        let (p, m) = to_sync(&out[0]);
        assert_eq!(p.tracks, vec!["2", "1"]);
        assert_eq!(m["2"].thumbnail.as_deref(), Some("t"));
    }
}
