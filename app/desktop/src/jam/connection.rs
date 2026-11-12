use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crossbeam_channel::Sender;
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

use super::control::*;
use super::types::{Event, Out};
use super::wire::*;

const SERVER: &str = "https://nuvx-listen.bxane.workers.dev";

pub(super) fn device_id(data_dir: &std::path::Path) -> String {
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
    let mut out = String::new();
    for i in 0..2u8 {
        let mut h = std::collections::hash_map::RandomState::new().build_hasher();
        h.write_u128(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos(),
        );
        h.write_u8(i);
        out += &format!("{:016x}", h.finish());
    }
    out
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64)
}

fn error_text(code: &str, msg: Option<String>) -> String {
    match code {
        "no_such_party" => "No party with that code. Check it and try again".to_owned(),
        "party_full" => msg.unwrap_or_else(|| "That party is full".to_owned()),
        "rate_limited" | "create_rate_limited" => "Slow down a little".to_owned(),
        _ => msg.unwrap_or_else(|| code.to_owned()),
    }
}

enum End {
    Done(Option<String>),
    Refused(String),
    Dropped(String),
}

pub(super) struct Conn {
    pub(super) code: Arc<Mutex<String>>,
    pub(super) create: bool,
    pub(super) name: String,
    pub(super) device: String,
    pub(super) token: String,
    pub(super) events: Sender<Event>,
    pub(super) wake: Arc<dyn Fn() + Send + Sync>,
    pub(super) offset: Arc<AtomicI64>,
    pub(super) known: Known,
}

impl Conn {
    fn emit(&self, e: Event) {
        let _ = self.events.send(e);
        (self.wake)();
    }

    fn server_now(&self) -> i64 {
        now_ms() + self.offset.load(Ordering::Relaxed)
    }

    fn local_instant(&self, ts: i64) -> Instant {
        let d = (ts - self.server_now()).clamp(-60_000, 60_000);
        if d >= 0 {
            Instant::now() + Duration::from_millis(d as u64)
        } else {
            Instant::now() - Duration::from_millis(-d as u64)
        }
    }

    fn base() -> String {
        std::env::var("NUVX_PARTY_SERVER")
            .unwrap_or_else(|_| SERVER.to_owned())
            .trim_end_matches('/')
            .to_owned()
    }

    fn http() -> anyhow::Result<reqwest::Client> {
        Ok(crate::proxy::apply(reqwest::Client::builder())
            .user_agent(concat!("nuvx-desktop/", env!("CARGO_PKG_VERSION")))
            .timeout(Duration::from_secs(15))
            .build()?)
    }

    pub(super) async fn run(mut self, mut out: mpsc::UnboundedReceiver<Out>) {
        let mut retries = 0;
        loop {
            let started = Instant::now();
            let end = self.session(&mut out).await;
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

    async fn join(&mut self) -> Result<(), End> {
        let http = Self::http().map_err(|e| End::Dropped(e.to_string()))?;
        let body =
            json!({ "userId": self.device, "deviceId": self.device, "displayName": self.name });
        let code = self.code.lock().unwrap().clone();
        let url = if self.create {
            format!("{}/api/parties", Self::base())
        } else {
            format!("{}/api/parties/{code}/join", Self::base())
        };
        let resp = http
            .post(url)
            .json(&body)
            .send()
            .await
            .map_err(|e| End::Dropped(e.to_string()))?;
        let status = resp.status();
        let v: Value = resp.json().await.unwrap_or_default();
        if !status.is_success() {
            let text = error_text(
                v["error"].as_str().unwrap_or_default(),
                v["message"].as_str().map(str::to_owned),
            );
            return Err(if status.is_server_error() {
                End::Dropped(text)
            } else {
                End::Refused(text)
            });
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
            let _ = http
                .post(format!("{}/api/parties/{code}/leave", Self::base()))
                .bearer_auth(&self.token)
                .send()
                .await;
        }
    }

    async fn session(&mut self, out: &mut mpsc::UnboundedReceiver<Out>) -> End {
        if let Err(e) = self.join().await {
            return e;
        }
        let code = self.code.lock().unwrap().clone();
        let url = format!(
            "{}/ws/parties/{code}",
            Self::base().replacen("http", "ws", 1)
        );
        let connect = async {
            let mut req = url.as_str().into_client_request()?;
            req.headers_mut().insert(
                "Sec-WebSocket-Protocol",
                format!("nuvx.v1, nuvx.token.{}", self.token).parse()?,
            );
            let u = url::Url::parse(&url)?;
            let host = u.host_str().unwrap_or_default().to_owned();
            let port = u.port_or_known_default().unwrap_or(443);
            let tcp = crate::proxy::connect(&host, port).await?;
            anyhow::Ok(tokio_tungstenite::client_async_tls(req, tcp).await?)
        };
        let ws = match tokio::time::timeout(Duration::from_secs(10), connect).await {
            Ok(Ok((ws, _))) => ws,
            Ok(Err(e)) => return End::Dropped(e.to_string()),
            Err(_) => return End::Dropped("timed out".into()),
        };
        let (mut tx, mut rx) = ws.split();
        let mut keepalive = tokio::time::interval(Duration::from_secs(10));
        let mut sync = tokio::time::interval(Duration::from_millis(200));
        let mut pings_left = 0u32;
        let mut best_rtt = i64::MAX;
        let mut last_rx = Instant::now();
        let mut you = String::new();
        let mut host = String::new();
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
                            if playback.seq < self.known.seq {
                                continue;
                            }
                            self.known.set_playback(&playback);
                            if playback.queue_seq != self.known.queue_seq {
                                send.push(json!({ "type": "syncQueue" }));
                            }
                            let fresh = (playback.seq, playback.queue_seq) != shown;
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
