use std::collections::HashMap;
use std::time::{Duration, Instant};

use super::{Action, App, Page};
use crate::jam::{Cmd, Event, Jam, Member, Tick};
use crate::model::Target;

const MAX_DRIFT: Duration = Duration::from_millis(350);
const TICK_EVERY: Duration = Duration::from_secs(15);
const BEFORE: usize = 20;
const AFTER: usize = 100;
const GUEST_WAITING: usize = 5;

mod sync;
use sync::{apply_state, guest_follow, host_send, set_control, waiting};
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
