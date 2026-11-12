use std::sync::atomic::AtomicI64;
use std::sync::{Arc, Mutex};

use crossbeam_channel::{Receiver, unbounded};
use tokio::sync::mpsc;

mod wire;
mod control;
mod types;
mod connection;
use control::*;
use connection::{Conn, device_id};
pub use types::{Cmd, Event, Member, RoomState, Tick};
use types::Out;

pub const ALPHABET: &str = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ";
pub const CODE_LEN: usize = 6;
const PLAY_LEAD_MS: i64 = 350;
const MAX_UPCOMING: usize = 25;
const MAX_HOST_DRIFT_MS: i64 = 1500;

pub fn share_link(code: &str) -> String {
    format!("https://nuvx.lol/invite/{code}")
}

pub fn normalize_code(code: &str) -> Option<String> {
    let c: String = code
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| match c.to_ascii_uppercase() {
            'I' | 'L' => '1',
            'O' => '0',
            c => c,
        })
        .collect();
    (c.len() == CODE_LEN && c.chars().all(|ch| ALPHABET.contains(ch))).then_some(c)
}

pub struct Jam {
    code: Arc<Mutex<String>>,
    out: mpsc::UnboundedSender<Out>,
    events: Receiver<Event>,
}

impl Jam {
    pub fn start(backend: &crate::backend::Backend, code: String, create: bool, name: String) -> Jam {
        let (out_tx, out_rx) = mpsc::unbounded_channel();
        let (ev_tx, ev_rx) = unbounded();
        let wake = backend.clone();
        let code = Arc::new(Mutex::new(if create { String::new() } else { code }));
        let task = Conn {
            code: code.clone(),
            create,
            name,
            device: device_id(backend.data_dir()),
            token: String::new(),
            events: ev_tx,
            wake: Arc::new(move || wake.wake()),
            offset: Arc::new(AtomicI64::new(0)),
            known: Known::default(),
        };
        backend.spawn(task.run(out_rx));
        Jam { code, out: out_tx, events: ev_rx }
    }

    pub fn code(&self) -> String {
        self.code.lock().unwrap().clone()
    }

    pub fn try_recv(&self) -> Option<Event> {
        self.events.try_recv().ok()
    }

    pub fn send_state(&self, s: &RoomState) {
        let _ = self.out.send(Out::State(s.clone()));
    }

    pub fn send_tick(&self, t: &Tick) {
        let _ = self.out.send(Out::Tick(t.clone()));
    }

    pub fn set_control(&self, on: bool) {
        let _ = self.out.send(Out::HostOnly(!on));
    }

    pub fn request(&self, cmd: Cmd) {
        let _ = self.out.send(Out::Cmd(cmd));
    }

    pub fn leave(&self) {
        let _ = self.out.send(Out::Leave);
    }
}

impl Drop for Jam {
    fn drop(&mut self) {
        self.leave();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Track;
    use std::time::{Duration, Instant};
    use super::wire::*;

    fn track(id: &str) -> Track {
        from_wire(&WireTrack { video_id: id.into(), title: id.into(), artist: "A".into(), ..Default::default() })
    }

    #[test]
    fn codes() {
        assert_eq!(normalize_code(" abc-234 ").as_deref(), Some("ABC234"));
        assert_eq!(normalize_code("abco1l").as_deref(), Some("ABC011"));
        assert_eq!(normalize_code("ABC2345"), None);
        assert_eq!(normalize_code("ABC23"), None);
    }

    #[test]
    fn parses_server_messages() {
        let m: In = serde_json::from_str(r#"{"type":"state","playback":{"seq":3,"track":{"videoId":"x","title":"T","artist":"A","fromAutoplay":false},"queueSeq":1,"queueIndex":0,"queueLength":1,"isPlaying":true,"positionMs":1200,"anchorMs":5,"effectivePositionMs":1200,"updatedBy":"m1","startedBy":null,"startedByName":null,"autoplayEnabled":false,"updatedAtMs":5},"serverMs":5}"#).unwrap();
        assert!(matches!(m, In::State { ref playback } if playback.seq == 3 && playback.track.as_ref().unwrap().video_id == "x"));
        let m: In = serde_json::from_str(r#"{"type":"members","members":[{"memberId":"a","userId":"u","displayName":"Sam","avatarUrl":null,"isHost":true,"connected":true,"joinedAtMs":1,"lastSeenMs":1}],"maxMembers":5,"hostOnlyControl":true,"serverMs":1}"#).unwrap();
        assert!(matches!(m, In::Members { host_only_control: true, .. }));
        let m: In = serde_json::from_str(r#"{"type":"pong","clientMs":10,"serverMs":20}"#).unwrap();
        assert!(matches!(m, In::Pong { client_ms: Some(10), server_ms: 20 }));
    }

    #[test]
    fn host_sends_only_what_differs() {
        let s = RoomState { queue: vec![track("a"), track("b")], index: 0, vid: "a".into(), pos: 1000, playing: true, ..Default::default() };
        let first = host_controls(&Known::default(), &s, 0);
        assert_eq!(first.iter().map(|v| v["action"].as_str().unwrap()).collect::<Vec<_>>(), ["setQueue", "setTrack"]);
        assert_eq!(first[1]["positionMs"], 1000 + PLAY_LEAD_MS);
        let mut k = Known::default();
        k.queue = s.queue.iter().map(to_wire).collect();
        k.index = 0;
        k.vid = "a".into();
        k.playing = true;
        k.position_ms = 1000;
        k.anchor_ms = 0;
        assert!(host_controls(&k, &RoomState { pos: 1500, ..s.clone() }, 500).is_empty());
        assert_eq!(host_controls(&k, &RoomState { playing: false, ..s.clone() }, 0)[0]["action"], "pause");
        assert_eq!(host_controls(&k, &RoomState { pos: 60_000, ..s.clone() }, 0)[0]["action"], "seek");
    }

    #[test]
    fn host_queue_is_cut_like_the_servers() {
        let queue: Vec<Track> = (0..40).map(|i| track(&format!("v{i}"))).collect();
        let s = RoomState { queue, index: 2, vid: "v2".into(), playing: false, ..Default::default() };
        let sent = host_controls(&Known::default(), &s, 0);
        assert_eq!(sent[0]["queue"].as_array().unwrap().len(), 2 + 1 + MAX_UPCOMING);
    }

    #[test]
    fn guest_requests() {
        let mut k = Known::default();
        k.queue = vec![to_wire(&track("a")), to_wire(&track("b"))];
        assert_eq!(cmd_control(&k, &Cmd::Next { track: track("c") }, 0).unwrap()["playNext"], true);
        assert_eq!(cmd_control(&k, &Cmd::Toggle, 0).unwrap()["action"], "play");
        k.playing = true;
        assert_eq!(cmd_control(&k, &Cmd::Toggle, 0).unwrap()["action"], "pause");
        assert_eq!(cmd_control(&k, &Cmd::Jump { index: 1 }, 0).unwrap()["track"]["videoId"], "b");
        assert!(cmd_control(&k, &Cmd::Jump { index: 9 }, 0).is_none());
    }

    #[test]
    fn future_anchor_counts_down() {
        let t = Tick { vid: "a".into(), pos: 1000, playing: true };
        let p = t.position_at(Instant::now() + Duration::from_millis(300));
        assert!(p > Duration::from_millis(600) && p < Duration::from_millis(750), "{p:?}");
    }
}
