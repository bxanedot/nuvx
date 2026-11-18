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
    inner: Option<Arc<Mutex<Inner>>>,
    stop: Option<Arc<AtomicBool>>,
    port: Option<u16>,
    error: Option<String>,
    synced: u64,
}

impl LanSync {
    pub fn new(enabled: bool) -> Self {
        Self { enabled, inner: None, stop: None, port: None, error: None, synced: 0 }
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
    let address = match (host::lan_address(), app.lan.port) {
        (Some(ip), Some(port)) => format!("{ip}:{port}"),
        (None, _) => "Not connected to a network".into(),
        _ => "Starting…".into(),
    };
    setting_row(ui, "Pair a new device", Some(&format!("On your phone: Settings → Nuvx Sync → Local Wi-Fi Sync, and enter {address}. If Windows asks, allow nuvx on private networks.")), |ui| {
        ui.label(RichText::new(&address).font(theme::semibold(14.0)).color(theme::text()));
    });
    let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
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
