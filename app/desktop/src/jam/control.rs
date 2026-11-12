use serde_json::{Value, json};

use super::wire::{WirePlayback, WireQueue, WireTrack, from_wire, to_wire};
use super::{Cmd, MAX_HOST_DRIFT_MS, MAX_UPCOMING, PLAY_LEAD_MS, RoomState, Tick};

#[derive(Clone, Debug, Default)]
pub(super) struct Known {
    pub(super) queue: Vec<WireTrack>,
    pub(super) queue_seq: i64,
    pub(super) index: i64,
    pub(super) seq: i64,
    pub(super) vid: String,
    pub(super) playing: bool,
    pub(super) position_ms: i64,
    pub(super) anchor_ms: i64,
    pub(super) updated_by: Option<String>,
}

impl Known {
    pub(super) fn position_at(&self, server_now: i64) -> i64 {
        if self.playing {
            self.position_ms + (server_now - self.anchor_ms).max(0)
        } else {
            self.position_ms
        }
    }

    pub(super) fn set_playback(&mut self, p: &WirePlayback) {
        self.seq = p.seq;
        self.vid = p
            .track
            .as_ref()
            .map(|t| t.video_id.clone())
            .unwrap_or_default();
        self.playing = p.is_playing;
        self.position_ms = p.position_ms;
        self.anchor_ms = p.anchor_ms;
        self.index = p.queue_index;
        self.updated_by = p.updated_by.clone();
    }

    pub(super) fn set_queue(&mut self, q: &WireQueue) {
        self.queue = q.items.clone();
        self.queue_seq = q.seq;
        self.index = q.index;
    }

    pub(super) fn room(&self) -> RoomState {
        let index = usize::try_from(self.index)
            .unwrap_or(0)
            .min(self.queue.len().saturating_sub(1));
        RoomState {
            queue: self.queue.iter().map(from_wire).collect(),
            index,
            vid: self.vid.clone(),
            pos: self.position_ms.max(0) as u64,
            playing: self.playing,
            ..Default::default()
        }
    }
}

pub(super) fn host_controls(k: &Known, s: &RoomState, server_now: i64) -> Vec<Value> {
    let mut out = Vec::new();
    let keep = s.queue.len().min(s.index + 1 + MAX_UPCOMING);
    let queue: Vec<WireTrack> = s.queue[..keep].iter().map(to_wire).collect();
    if queue
        .iter()
        .map(|t| &t.video_id)
        .ne(k.queue.iter().map(|t| &t.video_id))
        || (!queue.is_empty() && s.index as i64 != k.index)
    {
        out.push(json!({ "type": "control", "action": "setQueue", "queue": queue, "queueIndex": if queue.is_empty() { -1 } else { s.index as i64 } }));
    }
    let lead = if s.playing { PLAY_LEAD_MS } else { 0 };
    let pos = s.pos as i64 + lead;
    if s.vid != k.vid {
        if let Some(t) = s.queue.get(s.index).filter(|t| t.id == s.vid) {
            out.push(json!({ "type": "control", "action": "setTrack", "track": to_wire(t), "positionMs": pos, "isPlaying": s.playing, "queueIndex": s.index }));
        }
    } else if !s.vid.is_empty() {
        out.extend(tick_controls(
            k,
            &Tick {
                vid: s.vid.clone(),
                pos: s.pos,
                playing: s.playing,
            },
            server_now,
        ));
    }
    out
}

pub(super) fn tick_controls(k: &Known, t: &Tick, server_now: i64) -> Vec<Value> {
    if t.vid != k.vid || t.vid.is_empty() {
        return vec![];
    }
    let lead = if t.playing { PLAY_LEAD_MS } else { 0 };
    let pos = t.pos as i64 + lead;
    if t.playing != k.playing {
        let action = if t.playing { "play" } else { "pause" };
        return vec![json!({ "type": "control", "action": action, "positionMs": pos })];
    }
    if (k.position_at(server_now) - t.pos as i64).abs() > MAX_HOST_DRIFT_MS {
        return vec![json!({ "type": "control", "action": "seek", "positionMs": pos })];
    }
    vec![]
}

pub(super) fn cmd_control(k: &Known, cmd: &Cmd, server_now: i64) -> Option<Value> {
    Some(match cmd {
        Cmd::Add { track } if !track.id.is_empty() => {
            json!({ "type": "control", "action": "queueAdd", "track": to_wire(track) })
        }
        Cmd::Next { track } if !track.id.is_empty() => {
            json!({ "type": "control", "action": "queueAdd", "track": to_wire(track), "playNext": true })
        }
        Cmd::Add { .. } | Cmd::Next { .. } => return None,
        Cmd::Toggle if k.playing => {
            json!({ "type": "control", "action": "pause", "positionMs": k.position_at(server_now) })
        }
        Cmd::Toggle => json!({ "type": "control", "action": "play" }),
        Cmd::Skip => json!({ "type": "control", "action": "next" }),
        Cmd::Prev => json!({ "type": "control", "action": "previous" }),
        Cmd::Seek { pos } => json!({ "type": "control", "action": "seek", "positionMs": pos }),
        Cmd::Jump { index } => {
            let t = k.queue.get(*index)?;
            json!({ "type": "control", "action": "setTrack", "track": t, "positionMs": 0, "isPlaying": true, "queueIndex": index })
        }
    })
}
