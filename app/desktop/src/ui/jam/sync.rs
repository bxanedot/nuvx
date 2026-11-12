use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use crate::audio::AudioCmd;
use crate::jam::{RoomState, Tick};
use super::super::App;
use super::{AFTER, BEFORE, MAX_DRIFT, TICK_EVERY};

fn host_tick(app: &App) -> (std::ops::Range<usize>, Tick, u64) {
    let index = app.queue.index.unwrap_or(0);
    let from = index.saturating_sub(BEFORE);
    let to = (index + AFTER).min(app.queue.tracks.len()).max(from);
    let vid = app.current().map(|t| t.id.clone()).unwrap_or_default();
    let (pos, playing) = match app.resume_at {
        Some(at) => (at, false),
        None => (app.audio.status.position(), app.playing()),
    };
    let mut h = std::collections::hash_map::DefaultHasher::new();
    for t in app.queue.tracks.get(from..to).unwrap_or_default() {
        t.id.hash(&mut h);
    }
    (index - from).hash(&mut h);
    vid.hash(&mut h);
    playing.hash(&mut h);
    app.generation.hash(&mut h);
    (from..to, Tick { vid, pos: pos.as_millis() as u64, playing }, h.finish() | 1)
}

fn host_state(app: &App, range: std::ops::Range<usize>, tick: &Tick) -> RoomState {
    let index = app.queue.index.unwrap_or(0) - range.start;
    let queue = app.queue.tracks.get(range).map(<[_]>::to_vec).unwrap_or_default();
    let ids: std::collections::HashSet<&str> = queue.iter().map(|t| t.id.as_str()).collect();
    let pick = |m: &HashMap<String, String>| m.iter().filter(|(id, _)| ids.contains(id.as_str())).map(|(k, v)| (k.clone(), v.clone())).collect();
    let (by, owner) = (pick(&app.jam.by), pick(&app.jam.owner));
    RoomState { queue, index, by, owner, vid: tick.vid.clone(), pos: tick.pos, playing: tick.playing }
}

pub(super) fn host_send(app: &mut App) {
    if app.buffering {
        return;
    }
    let (range, tick, sig) = host_tick(app);
    let now = Instant::now();
    if app.jam.conn.is_none() {
        return;
    }
    let state = (sig != app.jam.sent_sig).then(|| host_state(app, range, &tick));
    let s = &mut app.jam;
    let Some(conn) = &s.conn else { return };
    if let Some(state) = state {
        log::info!(
            "jam: sending state ({} songs, {} at {} ms, playing {})",
            state.queue.len(),
            state.vid,
            state.pos,
            state.playing
        );
        conn.send_state(&state);
        s.sent_sig = sig;
        s.sent_from = app.queue.index.unwrap_or(0).saturating_sub(BEFORE);
        s.sent = Some((tick, now));
        return;
    }
    let Some((sent, at)) = &s.sent else { return };
    let expected = sent.position_at(*at).as_millis() as i64;
    let jumped = (expected - tick.pos as i64).abs() > 1500;
    if jumped || (tick.playing && at.elapsed() >= TICK_EVERY) {
        log::debug!("jam: sending tick at {} ms", tick.pos);
        conn.send_tick(&tick);
        s.sent = Some((tick, now));
    }
}

pub(super) fn waiting(app: &App, member: &str) -> usize {
    let start = app.queue.index.map_or(0, |i| i + 1);
    app.queue.tracks.get(start..).unwrap_or_default().iter().filter(|t| app.jam.owner.get(&t.id).is_some_and(|m| m == member)).count()
}

pub(super) fn set_control(app: &mut App, on: bool) {
    app.jam.control = on;
    app.jam.queue = on;
    if let Some(c) = &app.jam.conn {
        c.set_control(on);
    }
}

pub(super) fn apply_state(app: &mut App, state: RoomState, at: Instant) {
    let tick = Tick { vid: state.vid.clone(), pos: state.pos, playing: state.playing };
    app.jam.by = state.by;
    app.jam.owner = state.owner;
    app.queue_source = None;
    app.queue.fill = None;
    app.play_pending = None;
    if state.queue.is_empty() {
        app.queue.tracks.clear();
        app.queue.index = None;
        app.jam.loaded = None;
        app.audio.send(AudioCmd::Stop);
        app.jam.last = None;
        return;
    }
    app.queue.index = Some(state.index.min(state.queue.len() - 1));
    app.queue.tracks = state.queue;
    app.jam.last = Some((tick, at));
    sync(app);
}

fn sync(app: &mut App) {
    let Some((tick, at)) = app.jam.last.clone() else { return };
    if tick.vid.is_empty() {
        return;
    }
    if app.current_id() != Some(tick.vid.as_str()) {
        match app.queue.tracks.iter().position(|t| t.id == tick.vid) {
            Some(i) => app.queue.index = Some(i),
            None => return,
        }
    }
    let target = tick.position_at(at);
    if app.jam.loaded.as_deref() != Some(tick.vid.as_str()) || app.resume_at.is_some() {
        if tick.playing {
            let ahead = Duration::from_millis(app.jam.load_ms);
            let report = app.jam.reported.as_deref() != Some(tick.vid.as_str());
            app.jam.reported = Some(tick.vid.clone());
            app.jam.loaded = Some(tick.vid.clone());
            app.jam.load_started = Some(Instant::now());
            app.play_current_at(target + ahead, report);
        } else if app.jam.loaded.as_deref() != Some(tick.vid.as_str()) || app.resume_at != Some(target) {
            app.audio.send(AudioCmd::Stop);
            app.jam.loaded = None;
            app.resume_at = Some(target);
            app.audio.status.position_ms.store(target.as_millis() as u64, Ordering::Relaxed);
            app.audio.status.duration_ms.store(app.current().and_then(|t| t.duration).map_or(0, |d| d as u64 * 1000), Ordering::Relaxed);
            app.request_art();
        }
        return;
    }
    if app.buffering {
        return;
    }
    if tick.playing != app.playing() {
        app.audio.send(if tick.playing { AudioCmd::Resume } else { AudioCmd::Pause });
    }
    let pos = app.audio.status.position_smooth();
    let off = pos.as_millis() as i64 - target.as_millis() as i64;
    log::debug!("jam: {off:+} ms from the host (at {} ms, host {} ms)", pos.as_millis(), target.as_millis());
    if off.unsigned_abs() > MAX_DRIFT.as_millis() as u64 {
        log::info!("jam: {off:+} ms from the host, seeking");
        app.audio.send(if tick.playing { AudioCmd::SeekLive { pos: target, at: Instant::now() } } else { AudioCmd::Seek(target) });
    }
}

pub(super) fn guest_follow(app: &mut App) {
    if let Some(t) = app.jam.load_started
        && !app.buffering
        && app.playing()
    {
        app.jam.load_started = None;
        let took = t.elapsed().as_millis() as u64;
        app.jam.load_ms = ((app.jam.load_ms + took.min(8000)) / 2).max(200);
        if let Some((tick, at)) = &app.jam.last {
            let off = app.audio.status.position().as_millis() as i64 - tick.position_at(*at).as_millis() as i64;
            log::info!("jam: song started after {took} ms, {off:+} ms from the host");
        }
        sync(app);
    }
}
