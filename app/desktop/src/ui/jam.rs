use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use super::{Action, App, Page};
use crate::audio::AudioCmd;
use crate::jam::{Cmd, Event, Jam, Member, RoomState, Tick};
use crate::model::Target;

const MAX_DRIFT: Duration = Duration::from_millis(350);
const TICK_EVERY: Duration = Duration::from_secs(15);
const BEFORE: usize = 20;
const AFTER: usize = 100;
const GUEST_WAITING: usize = 5;

mod page;
pub use page::page;

#[derive(Default)]
pub struct Session {
    conn: Option<Jam>,
    created: bool,
    welcomed: bool,
    reconnecting: bool,
    you: String,
    host: String,
    control: bool,
    queue: bool,
    members: Vec<Member>,
    pub by: HashMap<String, String>,
    owner: HashMap<String, String>,
    code_input: String,
    pub name: String,
    error: Option<String>,

    sent_sig: u64,
    sent: Option<(Tick, Instant)>,
    sent_from: usize,

    last: Option<(Tick, Instant)>,
    loaded: Option<String>,
    reported: Option<String>,
    load_started: Option<Instant>,
    load_ms: u64,
    script_done: usize,
}

impl Session {
    pub fn active(&self) -> bool {
        self.conn.is_some()
    }

    pub fn is_host(&self) -> bool {
        self.active() && if self.welcomed { self.you == self.host } else { self.created }
    }

    pub fn guest(&self) -> bool {
        self.active() && !self.is_host()
    }
}

fn my_name(app: &App) -> String {
    [Some(app.jam.name.clone()), app.account_name.clone()]
        .into_iter()
        .flatten()
        .map(|n| n.trim().to_owned())
        .find(|n| !n.is_empty())
        .unwrap_or_else(|| "Guest".to_owned())
}

fn fresh(app: &mut App, created: bool) {
    let code_input = std::mem::take(&mut app.jam.code_input);
    let name = std::mem::take(&mut app.jam.name);
    app.jam = Session { created, code_input, name, queue: true, load_ms: 800, ..Default::default() };
}

pub fn start(app: &mut App) {
    start_with(app, String::new());
}

fn start_with(app: &mut App, code: String) {
    leave(app);
    fresh(app, true);
    app.jam.conn = Some(Jam::start(&app.backend, code, true, my_name(app)));
    app.play_listed_upload();
}

pub fn join(app: &mut App, code: &str) {
    let Some(code) = crate::jam::normalize_code(code) else {
        app.jam.error = Some("Codes are 6 letters and numbers".into());
        return;
    };
    if app.jam.conn.as_ref().is_some_and(|c| c.code() == code) {
        return;
    }
    leave(app);
    fresh(app, false);
    app.jam.conn = Some(Jam::start(&app.backend, code, false, my_name(app)));
    app.play_listed_upload();
}

pub fn leave(app: &mut App) {
    if let Some(c) = app.jam.conn.take() {
        c.leave();
    }
    fresh(app, false);
}

pub fn dev_hooks(app: &mut App) {
    if super::dev_var("NUVX_PARTY_HOST").is_some() {
        app.go(Page::Jam);
        start_with(app, String::new());
    } else if let Some(code) = super::dev_var("NUVX_PARTY_JOIN") {
        app.go(Page::Jam);
        join(app, code);
    }
}

pub fn dev_script(app: &mut App, ctx: &egui::Context, now: f64) {
    let Some(script) = super::dev_var("NUVX_PARTY_SCRIPT") else { return };
    for (i, step) in script.split(',').enumerate() {
        let Some((t, act)) = step.split_once(':') else { continue };
        let Ok(t) = t.parse::<f64>() else { continue };
        if i < app.jam.script_done {
            continue;
        }
        if now < t {
            ctx.request_repaint_after(Duration::from_secs_f64(t - now));
            break;
        }
        app.jam.script_done = i + 1;
        log::info!("jam: script {act}");
        match act {
            "skip" => app.next(),
            "prev" => app.prev(),
            "toggle" => app.toggle(),
            "control=on" => set_control(app, true),
            "control=off" => set_control(app, false),
            a if a.starts_with("add=") => {
                let track = crate::model::Track { id: a[4..].to_owned(), title: "Test song".into(), artists: vec![], album: None, duration: None, thumbs: vec![], track_nr: None, plays: None, music_video: false };
                app.apply(Action::Enqueue(track));
            }
            a if a.starts_with("seek=") => {
                if let Ok(s) = a[5..].parse() {
                    app.apply(Action::Seek(s));
                }
            }
            _ => {}
        }
    }
}

pub fn open_link(app: &mut App, code: String) {
    app.go(Page::Jam);
    join(app, &code);
}

pub fn tick(app: &mut App) {
    let Some(conn) = &app.jam.conn else { return };
    let mut events = Vec::new();
    while let Some(e) = conn.try_recv() {
        events.push(e);
    }
    for e in events {
        on_event(app, e);
    }
    if !app.jam.welcomed || app.jam.reconnecting {
        return;
    }
    if app.jam.is_host() {
        host_send(app);
    } else {
        guest_follow(app);
    }
}

fn on_event(app: &mut App, e: Event) {
    let s = &mut app.jam;
    match e {
        Event::Welcome { you, host, control, queue, members } => {
            let rejoin = s.welcomed;
            s.welcomed = true;
            s.reconnecting = false;
            s.error = None;
            s.you = you;
            s.host = host;
            s.control = control;
            s.queue = queue;
            s.members = members;
            s.sent_sig = 0;
            if !rejoin && s.created {
                app.toast("Party started: share the code or link");
            } else if !rejoin {
                app.toast("Joined the party");
            }
        }
        Event::Members { host, members } => {
            let promoted = s.you != s.host && host == s.you;
            s.host = host;
            s.members = members;
            if promoted {
                s.sent_sig = 0;
                app.toast("You're the host now");
            }
        }
        Event::Control { on, queue } => {
            s.control = on;
            s.queue = queue;
        }
        Event::State(state, at) => {
            if s.is_host() && app.current_id() == Some(state.vid.as_str()) {
                app.jam.loaded = Some(state.vid.clone());
            }
            apply_state(app, state, at);
        }
        Event::Notice(m) => {
            log::info!("jam: {m}");
            app.toast(m);
        }
        Event::Reconnecting => s.reconnecting = true,
        Event::Closed(msg) => {
            let first = !s.welcomed;
            leave(app);
            if first {
                app.jam.error = Some(msg);
            } else {
                app.toast(msg);
            }
        }
    }
}

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

fn host_send(app: &mut App) {
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

fn waiting(app: &App, member: &str) -> usize {
    let start = app.queue.index.map_or(0, |i| i + 1);
    app.queue.tracks.get(start..).unwrap_or_default().iter().filter(|t| app.jam.owner.get(&t.id).is_some_and(|m| m == member)).count()
}

fn set_control(app: &mut App, on: bool) {
    app.jam.control = on;
    app.jam.queue = on;
    if let Some(c) = &app.jam.conn {
        c.set_control(on);
    }
}

fn apply_state(app: &mut App, state: RoomState, at: Instant) {
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

fn guest_follow(app: &mut App) {
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

pub fn request(app: &mut App, cmd: Cmd) {
    if cmd.is_control() && !app.jam.control {
        app.toast("Only the host can control playback");
        return;
    }
    if !cmd.is_control() && !app.jam.queue {
        app.toast("The host turned off adding songs");
        return;
    }
    if !cmd.is_control() && waiting(app, &app.jam.you) >= GUEST_WAITING {
        log::info!("jam: {GUEST_WAITING} songs already waiting");
        app.toast(format!("You have {GUEST_WAITING} songs waiting. Add more once one plays"));
        return;
    }
    match &cmd {
        Cmd::Add { .. } => app.toast("Added to the party"),
        Cmd::Next { .. } => app.toast("Playing next in the party"),
        _ => {}
    }
    if let Some(c) = &app.jam.conn {
        c.request(cmd);
    }
}

pub fn guest_action(app: &mut App, a: Action) -> Option<Action> {
    match a {
        Action::Play(tracks, i) => {
            if let Some(t) = tracks.into_iter().nth(i) {
                if app.current_id() == Some(t.id.as_str()) {
                    request(app, Cmd::Toggle);
                } else {
                    request(app, Cmd::Add { track: t });
                }
            }
        }
        Action::Open(Target::Song(t)) | Action::Radio(t) | Action::Enqueue(t) => request(app, Cmd::Add { track: t }),
        Action::PlayNext(t) => request(app, Cmd::Next { track: t }),
        Action::PlayCollection(..) => app.toast("In a party, add songs one at a time"),
        Action::QueueJump(i) => request(app, Cmd::Jump { index: i }),
        Action::QueueRemove(_) => app.toast("Only the host can remove songs"),
        Action::Seek(secs) => request(app, Cmd::Seek { pos: (secs.max(0.0) * 1000.0) as u64 }),
        Action::TogglePlay => request(app, Cmd::Toggle),
        a => return Some(a),
    }
    None
}
