//! Listen together: a party on nuvx's party server (core/worker, the same one the
//! Android apps and the web player use) that keeps everyone on the same song at the
//! same moment.
//!
//! No audio goes through the server: each member streams the song from YouTube like
//! any other. The server holds the party's queue and playback, and every member sends
//! it controls (`setQueue`, `setTrack`, `play`, `queueAdd`, …). The UI still thinks
//! in host state and guest requests (`RoomState`, `Cmd`); this module turns those into
//! controls, sending only what differs from what the server already has, so applying
//! the server's state never echoes back to it.
//!
//! The socket lives on the backend's tokio runtime; the UI talks to it through
//! channels and is woken when something arrives.

use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crossbeam_channel::{Receiver, Sender, unbounded};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

use crate::model::{Link, Thumb, Track};

/// The party server. `NUVX_PARTY_SERVER` overrides it (e.g. `http://127.0.0.1:8787` for `wrangler dev`).
const SERVER: &str = "https://nuvx-listen.bxane.workers.dev";

/// The server's code alphabet (core/worker/src/party.ts): no I, L or O.
pub const ALPHABET: &str = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ";
pub const CODE_LEN: usize = 6;
/// The server anchors a play this far ahead of when it gets it (PLAY_LEAD_MS).
const PLAY_LEAD_MS: i64 = 350;
/// Songs after the current one the server keeps (MAX_UPCOMING).
const MAX_UPCOMING: usize = 25;
/// The host's position may wander this far from the server's before it re-seeks the room.
const MAX_HOST_DRIFT_MS: i64 = 1500;

/// The invite link the apps and the web player share (`https://nuvx.lol/invite/CODE`).
pub fn share_link(code: &str) -> String {
    format!("https://nuvx.lol/invite/{code}")
}

/// `code` cleaned up the way the server reads it (case, spaces, dashes; I/L as 1, O as 0)
/// if it's a valid party code.
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

#[derive(Clone, Debug)]
pub struct Member {
    pub id: String,
    pub name: String,
}

/// The party's queue and playback, as the UI applies it.
#[derive(Clone, Debug, Default)]
pub struct RoomState {
    pub queue: Vec<Track>,
    pub index: usize,
    /// Who added which song (video ID → name). The party server doesn't track it.
    pub by: std::collections::HashMap<String, String>,
    pub owner: std::collections::HashMap<String, String>,
    pub vid: String,
    /// Position in ms at the `Instant` sent with it.
    pub pos: u64,
    pub playing: bool,
}

/// Where playback is, without the queue.
#[derive(Clone, Debug)]
pub struct Tick {
    pub vid: String,
    pub pos: u64,
    pub playing: bool,
}

impl Tick {
    /// Position now, given when (locally) it was at `pos`. The server anchors a play a
    /// moment ahead, so `at` can still be in the future.
    pub fn position_at(&self, at: Instant) -> Duration {
        let pos = Duration::from_millis(self.pos);
        if !self.playing {
            return pos;
        }
        let now = Instant::now();
        if now >= at { pos + (now - at) } else { pos.saturating_sub(at - now) }
    }
}

/// What a guest asks the party to do.
#[derive(Clone, Debug)]
pub enum Cmd {
    Add { track: Track },
    /// Play next.
    Next { track: Track },
    Toggle,
    Skip,
    Prev,
    Seek { pos: u64 },
    Jump { index: usize },
}

impl Cmd {
    /// Only allowed while the host lets everyone control the music.
    pub fn is_control(&self) -> bool {
        !matches!(self, Cmd::Add { .. } | Cmd::Next { .. })
    }
}

#[derive(Debug)]
pub enum Event {
    /// Joined (or rejoined after a dropped connection).
    Welcome { you: String, host: String, control: bool, queue: bool, members: Vec<Member> },
    Members { host: String, members: Vec<Member> },
    /// What guests may do: control playback, add songs (one switch on nuvx's server).
    Control { on: bool, queue: bool },
    /// The party's state, changed by someone else; `Instant` is when (locally)
    /// playback is at `state.pos`.
    State(RoomState, Instant),
    /// A control the server turned down, or what someone did; the party keeps going.
    Notice(String),
    /// The connection is gone for good: a reason to show.
    Closed(String),
    Reconnecting,
}

enum Out {
    State(RoomState),
    Tick(Tick),
    HostOnly(bool),
    Cmd(Cmd),
    Leave,
}

pub struct Jam {
    code: Arc<Mutex<String>>,
    out: mpsc::UnboundedSender<Out>,
    events: Receiver<Event>,
}

impl Jam {
    /// Join party `code`, or with `create` start a new one (the server picks its code).
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

    /// The party code ("" until the server has made the party).
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

    /// Host: let guests control playback and add songs (`on`), or only the host.
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

/// This install's device ID for parties: rejoining with it keeps our place.
fn device_id(data_dir: &std::path::Path) -> String {
    let path = data_dir.join("party_device");
    if let Ok(id) = std::fs::read_to_string(&path)
        && !id.trim().is_empty()
    {
        return id.trim().to_owned();
    }
    let id = random_hex();
    let _ = std::fs::write(&path, &id);
    id
}

fn random_hex() -> String {
    use std::hash::{BuildHasher, Hasher};
    // RandomState is seeded randomly per process; mix in the time.
    let mut out = String::new();
    for i in 0..2u8 {
        let mut h = std::collections::hash_map::RandomState::new().build_hasher();
        h.write_u128(SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos());
        h.write_u8(i);
        out += &format!("{:016x}", h.finish());
    }
    out
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

// ---------------------------------------------------------------------- wire

/// A song as the party knows it (core/worker `Track`).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireTrack {
    video_id: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    artist: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    thumbnail_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    duration_ms: Option<u64>,
    #[serde(default)]
    from_autoplay: bool,
}

fn to_wire(t: &Track) -> WireTrack {
    WireTrack {
        video_id: t.id.clone(),
        title: t.title.clone(),
        artist: t.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", "),
        thumbnail_url: t.thumbs.iter().max_by_key(|th| th.width).map(|th| th.url.clone()),
        duration_ms: t.duration.map(|d| d as u64 * 1000),
        from_autoplay: false,
    }
}

fn from_wire(w: &WireTrack) -> Track {
    Track {
        id: w.video_id.clone(),
        title: w.title.clone(),
        artists: if w.artist.is_empty() { vec![] } else { vec![Link { name: w.artist.clone(), id: None }] },
        album: None,
        duration: w.duration_ms.map(|d| (d / 1000) as u32),
        thumbs: w.thumbnail_url.iter().map(|u| Thumb { url: u.clone(), width: 0, height: 0 }).collect(),
        track_nr: None,
        plays: None,
        music_video: false,
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireMember {
    member_id: String,
    display_name: String,
    #[serde(default)]
    is_host: bool,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WirePlayback {
    seq: i64,
    track: Option<WireTrack>,
    queue_seq: i64,
    queue_index: i64,
    is_playing: bool,
    position_ms: i64,
    anchor_ms: i64,
    updated_by: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
struct WireQueue {
    seq: i64,
    index: i64,
    items: Vec<WireTrack>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireParty {
    code: String,
    host_only_control: bool,
    members: Vec<WireMember>,
    playback: WirePlayback,
    queue: WireQueue,
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
enum In {
    #[serde(rename_all = "camelCase")]
    Welcome { you: WireMember, party: WireParty, server_ms: i64 },
    #[serde(rename_all = "camelCase")]
    Members { members: Vec<WireMember>, host_only_control: bool },
    State { playback: WirePlayback },
    Queue { queue: WireQueue },
    #[serde(rename_all = "camelCase")]
    Pong { client_ms: Option<i64>, server_ms: i64 },
    Error { error: String, message: Option<String> },
    Bye { reason: Option<String>, message: Option<String> },
    Activity { by: String, detail: String },
}

fn members_of(m: &[WireMember]) -> (String, Vec<Member>) {
    let host = m.iter().find(|m| m.is_host).map(|m| m.member_id.clone()).unwrap_or_default();
    (host, m.iter().map(|m| Member { id: m.member_id.clone(), name: m.display_name.clone() }).collect())
}

// ------------------------------------------------------------------ translation

/// What the server has, as far as we've heard.
#[derive(Clone, Debug, Default)]
struct Known {
    queue: Vec<WireTrack>,
    queue_seq: i64,
    index: i64,
    seq: i64,
    vid: String,
    playing: bool,
    position_ms: i64,
    anchor_ms: i64,
    updated_by: Option<String>,
}

impl Known {
    fn position_at(&self, server_now: i64) -> i64 {
        if self.playing { self.position_ms + (server_now - self.anchor_ms).max(0) } else { self.position_ms }
    }

    fn set_playback(&mut self, p: &WirePlayback) {
        self.seq = p.seq;
        self.vid = p.track.as_ref().map(|t| t.video_id.clone()).unwrap_or_default();
        self.playing = p.is_playing;
        self.position_ms = p.position_ms;
        self.anchor_ms = p.anchor_ms;
        self.index = p.queue_index;
        self.updated_by = p.updated_by.clone();
    }

    fn set_queue(&mut self, q: &WireQueue) {
        self.queue = q.items.clone();
        self.queue_seq = q.seq;
        self.index = q.index;
    }

    fn room(&self) -> RoomState {
        let index = usize::try_from(self.index).unwrap_or(0).min(self.queue.len().saturating_sub(1));
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

/// The controls that bring the server from `k` to the host's state `s`.
fn host_controls(k: &Known, s: &RoomState, server_now: i64) -> Vec<Value> {
    let mut out = Vec::new();
    // The server keeps the current song and MAX_UPCOMING after it.
    let keep = s.queue.len().min(s.index + 1 + MAX_UPCOMING);
    let queue: Vec<WireTrack> = s.queue[..keep].iter().map(to_wire).collect();
    if queue.iter().map(|t| &t.video_id).ne(k.queue.iter().map(|t| &t.video_id)) || (!queue.is_empty() && s.index as i64 != k.index) {
        out.push(json!({ "type": "control", "action": "setQueue", "queue": queue, "queueIndex": if queue.is_empty() { -1 } else { s.index as i64 } }));
    }
    let lead = if s.playing { PLAY_LEAD_MS } else { 0 };
    let pos = s.pos as i64 + lead;
    if s.vid != k.vid {
        if let Some(t) = s.queue.get(s.index).filter(|t| t.id == s.vid) {
            out.push(json!({ "type": "control", "action": "setTrack", "track": to_wire(t), "positionMs": pos, "isPlaying": s.playing, "queueIndex": s.index }));
        }
    } else if !s.vid.is_empty() {
        out.extend(tick_controls(k, &Tick { vid: s.vid.clone(), pos: s.pos, playing: s.playing }, server_now));
    }
    out
}

/// Play, pause or seek when the host's position differs from the server's.
fn tick_controls(k: &Known, t: &Tick, server_now: i64) -> Vec<Value> {
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

/// A guest's request as a control.
fn cmd_control(k: &Known, cmd: &Cmd, server_now: i64) -> Option<Value> {
    Some(match cmd {
        Cmd::Add { track } if !track.id.is_empty() => json!({ "type": "control", "action": "queueAdd", "track": to_wire(track) }),
        Cmd::Next { track } if !track.id.is_empty() => json!({ "type": "control", "action": "queueAdd", "track": to_wire(track), "playNext": true }),
        Cmd::Add { .. } | Cmd::Next { .. } => return None,
        Cmd::Toggle if k.playing => json!({ "type": "control", "action": "pause", "positionMs": k.position_at(server_now) }),
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

/// Words for a server error code.
fn error_text(code: &str, msg: Option<String>) -> String {
    match code {
        "no_such_party" => "No party with that code. Check it and try again".to_owned(),
        "party_full" => msg.unwrap_or_else(|| "That party is full".to_owned()),
        "rate_limited" | "create_rate_limited" => "Slow down a little".to_owned(),
        _ => msg.unwrap_or_else(|| code.to_owned()),
    }
}

// ------------------------------------------------------------------ connection

/// Why a connection ended.
enum End {
    /// Left, or removed: nothing to retry.
    Done(Option<String>),
    /// The server refused us (bad code, full…): show it, don't retry.
    Refused(String),
    /// The network dropped: try again.
    Dropped(String),
}

struct Conn {
    code: Arc<Mutex<String>>,
    create: bool,
    name: String,
    device: String,
    token: String,
    events: Sender<Event>,
    wake: Arc<dyn Fn() + Send + Sync>,
    /// Server clock minus ours, in ms.
    offset: Arc<AtomicI64>,
    known: Known,
}

impl Conn {
    fn emit(&self, e: Event) {
        let _ = self.events.send(e);
        (self.wake)();
    }

    fn server_now(&self) -> i64 {
        now_ms() + self.offset.load(Ordering::Relaxed)
    }

    /// When (locally) the server time `ts` is; it can be a moment ahead.
    fn local_instant(&self, ts: i64) -> Instant {
        let d = (ts - self.server_now()).clamp(-60_000, 60_000);
        if d >= 0 { Instant::now() + Duration::from_millis(d as u64) } else { Instant::now() - Duration::from_millis(-d as u64) }
    }

    fn base() -> String {
        std::env::var("NUVX_PARTY_SERVER").unwrap_or_else(|_| SERVER.to_owned()).trim_end_matches('/').to_owned()
    }

    fn http() -> anyhow::Result<reqwest::Client> {
        Ok(crate::proxy::apply(reqwest::Client::builder()).user_agent(concat!("nuvx-desktop/", env!("CARGO_PKG_VERSION"))).timeout(Duration::from_secs(15)).build()?)
    }

    async fn run(mut self, mut out: mpsc::UnboundedReceiver<Out>) {
        let mut retries = 0;
        loop {
            let started = Instant::now();
            let end = self.session(&mut out).await;
            // Drops spread over a long party shouldn't add up to giving up.
            if started.elapsed() > Duration::from_secs(120) {
                retries = 0;
            }
            match end {
                End::Done(msg) => {
                    if let Some(m) = msg {
                        self.emit(Event::Closed(m));
                    }
                    return;
                }
                End::Refused(msg) => {
                    self.emit(Event::Closed(msg));
                    return;
                }
                End::Dropped(e) => {
                    log::warn!("party: connection dropped: {e}");
                    retries += 1;
                    if retries > 6 {
                        self.emit(Event::Closed("Lost the connection to the party".into()));
                        return;
                    }
                    self.emit(Event::Reconnecting);
                    tokio::time::sleep(Duration::from_secs(2 * retries)).await;
                    if matches!(out.try_recv(), Ok(Out::Leave)) || out.is_closed() {
                        self.leave_http().await;
                        return;
                    }
                }
            }
        }
    }

    /// Create or (re)join the party over HTTP: the member token for the socket.
    async fn join(&mut self) -> Result<(), End> {
        let http = Self::http().map_err(|e| End::Dropped(e.to_string()))?;
        let body = json!({ "userId": self.device, "deviceId": self.device, "displayName": self.name });
        let code = self.code.lock().unwrap().clone();
        let url = if self.create { format!("{}/api/parties", Self::base()) } else { format!("{}/api/parties/{code}/join", Self::base()) };
        let resp = http.post(url).json(&body).send().await.map_err(|e| End::Dropped(e.to_string()))?;
        let status = resp.status();
        let v: Value = resp.json().await.unwrap_or_default();
        if !status.is_success() {
            let text = error_text(v["error"].as_str().unwrap_or_default(), v["message"].as_str().map(str::to_owned));
            return Err(if status.is_server_error() { End::Dropped(text) } else { End::Refused(text) });
        }
        let (Some(code), Some(token)) = (v["code"].as_str(), v["token"].as_str()) else {
            return Err(End::Dropped("the party server sent no token".into()));
        };
        *self.code.lock().unwrap() = code.to_owned();
        self.token = token.to_owned();
        self.create = false;
        Ok(())
    }

    async fn leave_http(&self) {
        let code = self.code.lock().unwrap().clone();
        if self.token.is_empty() || code.is_empty() {
            return;
        }
        if let Ok(http) = Self::http() {
            let _ = http.post(format!("{}/api/parties/{code}/leave", Self::base())).bearer_auth(&self.token).send().await;
        }
    }

    async fn session(&mut self, out: &mut mpsc::UnboundedReceiver<Out>) -> End {
        // A fresh token each time: rejoining with our device ID keeps our place.
        if let Err(e) = self.join().await {
            return e;
        }
        let code = self.code.lock().unwrap().clone();
        let url = format!("{}/ws/parties/{code}", Self::base().replacen("http", "ws", 1));
        let connect = async {
            let mut req = url.as_str().into_client_request()?;
            req.headers_mut().insert("Sec-WebSocket-Protocol", format!("nuvx.v1, nuvx.token.{}", self.token).parse()?);
            let u = url::Url::parse(&url)?;
            let host = u.host_str().unwrap_or_default().to_owned();
            let port = u.port_or_known_default().unwrap_or(443);
            // Through the proxy setting, like everything else.
            let tcp = crate::proxy::connect(&host, port).await?;
            anyhow::Ok(tokio_tungstenite::client_async_tls(req, tcp).await?)
        };
        let ws = match tokio::time::timeout(Duration::from_secs(10), connect).await {
            Ok(Ok((ws, _))) => ws,
            Ok(Err(e)) => return End::Dropped(e.to_string()),
            Err(_) => return End::Dropped("timed out".into()),
        };
        let (mut tx, mut rx) = ws.split();
        // Pings keep the socket alive, make the server send its state every few seconds,
        // and (the first few, close together) sync the clocks: the quickest round trip wins.
        let mut keepalive = tokio::time::interval(Duration::from_secs(10));
        let mut sync = tokio::time::interval(Duration::from_millis(200));
        let mut pings_left = 0u32;
        let mut best_rtt = i64::MAX;
        let mut last_rx = Instant::now();
        let mut you = String::new();
        let mut host = String::new();
        // (seq, queueSeq) of the last state handed to the UI, so heartbeats aren't re-applied.
        let mut shown = (-1i64, -1i64);
        loop {
            let mut send: Vec<Value> = Vec::new();
            tokio::select! {
                msg = out.recv() => match msg {
                    Some(Out::State(s)) => send = host_controls(&self.known, &s, self.server_now()),
                    Some(Out::Tick(t)) => send = tick_controls(&self.known, &t, self.server_now()),
                    Some(Out::HostOnly(on)) => send.push(json!({ "type": "control", "action": "setHostOnlyControl", "enabled": on })),
                    Some(Out::Cmd(c)) => send.extend(cmd_control(&self.known, &c, self.server_now())),
                    Some(Out::Leave) | None => {
                        let _ = tx.close().await;
                        self.leave_http().await;
                        return End::Done(None);
                    }
                },
                _ = keepalive.tick() => {
                    if last_rx.elapsed() > Duration::from_secs(45) {
                        return End::Dropped("no reply from the server".into());
                    }
                    send.push(json!({ "type": "ping", "clientMs": now_ms() }));
                }
                _ = sync.tick(), if pings_left > 0 => {
                    pings_left -= 1;
                    send.push(json!({ "type": "ping", "clientMs": now_ms() }));
                }
                msg = rx.next() => {
                    last_rx = Instant::now();
                    let text = match msg {
                        Some(Ok(Message::Text(t))) => t,
                        Some(Ok(Message::Close(_))) | None => return End::Dropped("closed by server".into()),
                        Some(Ok(_)) => continue,
                        Some(Err(e)) => return End::Dropped(e.to_string()),
                    };
                    let Ok(m) = serde_json::from_str::<In>(&text) else {
                        log::debug!("party: unhandled message {text}");
                        continue;
                    };
                    match m {
                        In::Welcome { you: me, party, server_ms } => {
                            // A first guess until the pings answer.
                            self.offset.store(server_ms - now_ms(), Ordering::Relaxed);
                            pings_left = 5;
                            *self.code.lock().unwrap() = party.code.clone();
                            you = me.member_id;
                            let (h, members) = members_of(&party.members);
                            host = h.clone();
                            self.known.set_queue(&party.queue);
                            self.known.set_playback(&party.playback);
                            let open = !party.host_only_control;
                            self.emit(Event::Welcome { you: you.clone(), host: h, control: open, queue: open, members });
                            shown = (self.known.seq, self.known.queue_seq);
                            self.emit(Event::State(self.known.room(), self.local_instant(self.known.anchor_ms)));
                        }
                        In::Members { members, host_only_control } => {
                            let (h, members) = members_of(&members);
                            host = h.clone();
                            self.emit(Event::Members { host: h, members });
                            self.emit(Event::Control { on: !host_only_control, queue: !host_only_control });
                        }
                        In::Queue { queue } => self.known.set_queue(&queue),
                        In::State { playback } => {
                            // Older than what we have: two controllers pressing at once settle.
                            if playback.seq < self.known.seq {
                                continue;
                            }
                            self.known.set_playback(&playback);
                            if playback.queue_seq != self.known.queue_seq {
                                send.push(json!({ "type": "syncQueue" }));
                            }
                            let fresh = (playback.seq, playback.queue_seq) != shown;
                            // The host already plays its own changes.
                            let own = you == host && playback.updated_by.as_deref() == Some(you.as_str());
                            if fresh && !own {
                                shown = (playback.seq, playback.queue_seq);
                                self.emit(Event::State(self.known.room(), self.local_instant(self.known.anchor_ms)));
                            } else if fresh {
                                shown = (playback.seq, playback.queue_seq);
                            }
                        }
                        In::Pong { client_ms: Some(c), server_ms } => {
                            let rtt = now_ms() - c;
                            if rtt >= 0 && rtt < best_rtt {
                                best_rtt = rtt;
                                self.offset.store(server_ms - (c + rtt / 2), Ordering::Relaxed);
                            }
                        }
                        In::Pong { .. } => {}
                        In::Error { error, message } => self.emit(Event::Notice(error_text(&error, message))),
                        In::Bye { reason, message } => {
                            return End::Done(match reason.as_deref() {
                                Some("left") => None,
                                _ => Some(message.unwrap_or_else(|| "You were removed from the party".into())),
                            });
                        }
                        In::Activity { by, detail } => log::info!("party: {by}: {detail}"),
                    }
                }
            }
            for v in send {
                if let Err(e) = tx.send(Message::text(v.to_string())).await {
                    return End::Dropped(e.to_string());
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        // A fresh party: queue and track.
        let first = host_controls(&Known::default(), &s, 0);
        assert_eq!(first.iter().map(|v| v["action"].as_str().unwrap()).collect::<Vec<_>>(), ["setQueue", "setTrack"]);
        assert_eq!(first[1]["positionMs"], 1000 + PLAY_LEAD_MS);
        // The server now has it: nothing to send, even a little later.
        let mut k = Known::default();
        k.queue = s.queue.iter().map(to_wire).collect();
        k.index = 0;
        k.vid = "a".into();
        k.playing = true;
        k.position_ms = 1000;
        k.anchor_ms = 0;
        assert!(host_controls(&k, &RoomState { pos: 1500, ..s.clone() }, 500).is_empty());
        // Paused here: pause there. Jumped ahead: seek.
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
