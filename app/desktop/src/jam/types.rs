use std::time::{Duration, Instant};

use crate::model::Track;

#[derive(Clone, Debug)]
pub struct Member {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Debug, Default)]
pub struct RoomState {
    pub queue: Vec<Track>,
    pub index: usize,
    pub by: std::collections::HashMap<String, String>,
    pub owner: std::collections::HashMap<String, String>,
    pub vid: String,
    pub pos: u64,
    pub playing: bool,
}

#[derive(Clone, Debug)]
pub struct Tick {
    pub vid: String,
    pub pos: u64,
    pub playing: bool,
}

impl Tick {
    pub fn position_at(&self, at: Instant) -> Duration {
        let pos = Duration::from_millis(self.pos);
        if !self.playing {
            return pos;
        }
        let now = Instant::now();
        if now >= at {
            pos + (now - at)
        } else {
            pos.saturating_sub(at - now)
        }
    }
}

#[derive(Clone, Debug)]
pub enum Cmd {
    Add { track: Track },
    Next { track: Track },
    Toggle,
    Skip,
    Prev,
    Seek { pos: u64 },
    Jump { index: usize },
}

impl Cmd {
    pub fn is_control(&self) -> bool {
        !matches!(self, Cmd::Add { .. } | Cmd::Next { .. })
    }
}

#[derive(Debug)]
pub enum Event {
    Welcome {
        you: String,
        host: String,
        control: bool,
        queue: bool,
        members: Vec<Member>,
    },
    Members {
        host: String,
        members: Vec<Member>,
    },
    Control {
        on: bool,
        queue: bool,
    },
    State(RoomState, Instant),
    Notice(String),
    Closed(String),
    Reconnecting,
}

pub(super) enum Out {
    State(RoomState),
    Tick(Tick),
    HostOnly(bool),
    Cmd(Cmd),
    Leave,
}
