use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering::Relaxed;
use std::sync::{Arc, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};

use crate::webmusic::{self, Session};

const ORIGIN: &str = "https://music.youtube.com";
const FALLBACK_VERSION: &str = "1.20261006.10.00";
const REFRESH: Duration = Duration::from_secs(24 * 3600);
const CONSENT: &str = "SOCS=CAISAiAD";

#[derive(Default, Clone, serde::Serialize, serde::Deserialize)]
struct Web {
    version: String,
    visitor_data: String,
    at: u64,
}

pub struct Api {
    file: PathBuf,
    web: tokio::sync::Mutex<Option<Web>>,
    account: RwLock<Option<Arc<Session>>>,
    epoch: std::sync::atomic::AtomicU64,
    tz: (String, i32),
    checked: std::sync::atomic::AtomicBool,
    unavailable: std::sync::atomic::AtomicBool,
    failed_at: std::sync::atomic::AtomicU64,
    signed_out: std::sync::atomic::AtomicBool,
    paused_until: std::sync::atomic::AtomicU64,
    refusals: std::sync::atomic::AtomicU64,
    rotated_at: std::sync::atomic::AtomicU64,
}

#[derive(Debug)]
struct Unavailable;

impl std::fmt::Display for Unavailable {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("YouTube Music isn't available where your proxy connects from. Try another proxy or server (Settings › Network)")
    }
}

impl std::error::Error for Unavailable {}

impl Api {
    pub fn new(data_dir: &Path) -> Self {
        let file = data_dir.join("ytmapi.json");
        let web = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice::<Web>(&b).ok());
        Self {
            file,
            web: tokio::sync::Mutex::new(web),
            account: RwLock::new(Session::load(data_dir).map(Arc::new)),
            epoch: Default::default(),
            tz: local_tz(),
            checked: Default::default(),
            unavailable: Default::default(),
            failed_at: Default::default(),
            signed_out: Default::default(),
            paused_until: Default::default(),
            refusals: Default::default(),
            rotated_at: Default::default(),
        }
    }

    pub fn account(&self) -> Option<Arc<Session>> {
        self.account.read().unwrap().clone()
    }

    pub fn epoch(&self) -> u64 {
        self.epoch.load(Relaxed)
    }

    pub fn sign_in(&self, s: Session) -> bool {
        let mut slot = self.account.write().unwrap();
        let saved = self.file.parent().is_some_and(|dir| s.save(dir));
        *slot = Some(Arc::new(s));
        self.epoch.fetch_add(1, Relaxed);
        saved
    }

    pub fn sign_out(&self) {
        let mut slot = self.account.write().unwrap();
        if let Some(dir) = self.file.parent() {
            Session::remove(dir);
        }
        *slot = None;
        self.epoch.fetch_add(1, Relaxed);
    }

    pub fn take_signed_out(&self) -> bool {
        self.signed_out.swap(false, Relaxed)
    }

    pub async fn rotate(&self) -> anyhow::Result<()> {
        let Some(account) = self.account() else { return Ok(()) };
        let now = now_ms();
        if now.saturating_sub(self.rotated_at.load(Relaxed)) < 60_000 {
            return Ok(());
        }
        self.take_in_replaced();
        let account = self.account().unwrap_or(account);
        let set = account.rotate(&crate::proxy::http()).await?;
        self.rotated_at.store(now, Relaxed);
        self.keep(&account, set.iter().map(String::as_str));
        Ok(())
    }

    fn take_in_replaced(&self) {
        let Some(account) = self.account() else { return };
        let lines = account.take_replaced();
        if !lines.is_empty() {
            self.keep(&account, lines.iter().map(String::as_str));
        }
    }

    pub async fn ensure_visitor(&self) {
        let Some(account) = self.account().filter(|a| a.visitor_data.is_none()) else { return };
        match self.session_from_cookie(&account.cookie).await {
            Ok(fresh) => {
                let mut slot = self.account.write().unwrap();
                if let Some(cur) = slot.as_ref().filter(|s| s.same_login(&account)) {
                    let mut next = (**cur).clone();
                    next.visitor_data = fresh.visitor_data;
                    if let Some(dir) = self.file.parent() {
                        next.save(dir);
                    }
                    *slot = Some(Arc::new(next));
                }
            }
            Err(e) => log::info!("couldn't read the account's visitor ID: {e:#}"),
        }
    }

    async fn wait_if_paused(&self) {
        loop {
            let (until, now) = (self.paused_until.load(Relaxed), now_ms());
            if until <= now {
                return;
            }
            tokio::time::sleep(Duration::from_millis(until - now)).await;
        }
    }

    fn refused(&self, endpoint: &str) {
        const RUN: u64 = 6;
        let n = self.refusals.fetch_add(1, Relaxed) + 1;
        if n < RUN {
            return;
        }
        let ms = 5_000 << (n - RUN).min(3);
        let until = now_ms() + ms;
        if self.paused_until.fetch_max(until, Relaxed) < until {
            log::info!("{endpoint}: YouTube Music is limiting this account's requests; pausing them for {} s", ms / 1000);
        }
    }

    pub fn limited(&self) -> bool {
        self.paused_until.load(Relaxed) > now_ms()
    }

    pub fn keep<'a>(&self, used: &Session, set_cookies: impl IntoIterator<Item = &'a str>) {
        let mut slot = self.account.write().unwrap();
        let Some(new) = slot.as_ref().filter(|s| s.same_login(used)).and_then(|s| s.updated(set_cookies)) else { return };
        if let Some(dir) = self.file.parent() {
            new.save(dir);
        }
        *slot = Some(Arc::new(new));
    }

    async fn web(&self, fresh: bool) -> Web {
        let mut slot = self.web.lock().await;
        let now = now_secs();
        let refresh = if crate::proxy::can_switch_exit() { 9 * 60 } else { REFRESH.as_secs() };
        let unchecked = crate::proxy::active().resolve().is_some() && !self.checked.load(Relaxed);
        let stale = unchecked || slot.as_ref().is_none_or(|w| fresh || now.saturating_sub(w.at) > refresh || w.visitor_data.is_empty());
        let backing_off = now.saturating_sub(self.failed_at.load(Relaxed)) < 30;
        if stale && !(backing_off && !unchecked) {
            let mut switched = false;
            loop {
                match self.read_web().await {
                    Ok(w) => {
                        let _ = std::fs::write(&self.file, serde_json::to_vec(&w).unwrap_or_default());
                        *slot = Some(w);
                        self.unavailable.store(false, Relaxed);
                    }
                    Err(e) if e.is::<Unavailable>() => {
                        if !std::mem::replace(&mut switched, true) && crate::proxy::new_circuit(offered_through).await {
                            continue;
                        }
                        log::warn!("{e}");
                        self.unavailable.store(true, Relaxed);
                    }
                    Err(e) => {
                        log::warn!("couldn't read music.youtube.com: {e:#}");
                        self.failed_at.store(now, Relaxed);
                    }
                }
                break;
            }
            self.checked.store(true, Relaxed);
        }
        slot.clone().unwrap_or_else(|| Web { version: FALLBACK_VERSION.into(), ..Default::default() })
    }

    async fn read_web(&self) -> anyhow::Result<Web> {
        let resp = crate::proxy::http()
            .get(ORIGIN)
            .header("User-Agent", webmusic::UA)
            .header("Cookie", CONSENT)
            .timeout(Duration::from_secs(20))
            .send()
            .await?
            .error_for_status()?;
        let cookie = resp.headers().get_all("set-cookie").iter().find_map(|c| {
            let v = c.to_str().ok()?.strip_prefix("__Secure-YEC=")?.split(';').next()?;
            (!v.is_empty()).then(|| v.to_owned())
        });
        let html = resp.text().await?;
        if !html.contains("\"INNERTUBE_CONTEXT_GL\"") && html.len() < 100_000 {
            anyhow::bail!(Unavailable);
        }
        let visitor_data = cookie
            .or_else(|| between(&html, "\"VISITOR_DATA\":\"", "\"").map(str::to_owned))
            .ok_or_else(|| anyhow::anyhow!("no visitor data on the page"))?;
        let version = between(&html, "\"INNERTUBE_CLIENT_VERSION\":\"", "\"")
            .or_else(|| between(&html, "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"", "\""))
            .unwrap_or(FALLBACK_VERSION)
            .to_owned();
        log::info!("music.youtube.com client {version}");
        Ok(Web { version, visitor_data, at: now_secs() })
    }

    pub async fn visitor_data(&self, fresh: bool) -> anyhow::Result<String> {
        let w = self.web(fresh).await;
        anyhow::ensure!(!w.visitor_data.is_empty(), "couldn't get a YouTube visitor ID");
        Ok(w.visitor_data)
    }

    pub async fn call(&self, endpoint: &str, body: Value, auth: bool) -> anyhow::Result<Value> {
        let account = if auth { self.account() } else { None };
        match self.call_once(endpoint, &body, account.as_deref(), false).await {
            Err(e) if account.is_none() && is_retryable(&e) => self.retry_anonymous(endpoint, &body, e).await,
            Err(e) if account.is_some() && is_refused(&e) => self.retry_signed_in(endpoint, &body, account.as_deref(), e).await,
            r => r,
        }
    }

    pub async fn youtube_search(&self, query: &str) -> anyhow::Result<Value> {
        let web = self.web(false).await;
        let payload = json!({
            "query": query,
            "params": "EgIQAQ%3D%3D",
            "context": {
                "client": { "clientName": "WEB", "clientVersion": YOUTUBE_WEB_VERSION, "hl": "en", "gl": "US", "visitorData": web.visitor_data },
            },
        });
        let resp = crate::proxy::http()
            .post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("User-Agent", webmusic::UA)
            .header("Origin", "https://www.youtube.com")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", YOUTUBE_WEB_VERSION)
            .header("Cookie", CONSENT)
            .timeout(Duration::from_secs(15))
            .json(&payload)
            .send()
            .await?;
        let status = resp.status();
        anyhow::ensure!(status.is_success(), "YouTube search returned HTTP {status}");
        Ok(resp.json().await?)
    }

    pub async fn call_signed_in(&self, endpoint: &str, body: Value) -> anyhow::Result<Value> {
        let account = self.account().ok_or_else(|| anyhow::anyhow!("sign in first"))?;
        self.call_as(endpoint, body, &account).await
    }

    pub async fn call_as_google_account(&self, endpoint: &str, body: Value) -> anyhow::Result<Value> {
        let account = self.account().ok_or_else(|| anyhow::anyhow!("sign in first"))?;
        self.call_as(endpoint, body, &account.on_channel(None)).await
    }

    async fn call_as(&self, endpoint: &str, body: Value, account: &Session) -> anyhow::Result<Value> {
        match self.call_once(endpoint, &body, Some(account), false).await {
            Err(e) if is_refused(&e) => self.retry_signed_in(endpoint, &body, Some(account), e).await,
            r => r,
        }
    }

    pub fn switch_channel(&self, page_id: Option<String>, epoch: u64) -> anyhow::Result<u64> {
        let mut slot = self.account.write().unwrap();
        anyhow::ensure!(self.epoch() == epoch, "The account changed meanwhile; pick the channel again");
        let next = slot.as_ref().ok_or_else(|| anyhow::anyhow!("sign in first"))?.on_channel(page_id);
        anyhow::ensure!(self.file.parent().is_some_and(|dir| next.save(dir)), "Couldn't save the channel choice");
        *slot = Some(Arc::new(next));
        Ok(self.epoch.fetch_add(1, Relaxed) + 1)
    }

    async fn retry_anonymous(&self, endpoint: &str, body: &Value, e: anyhow::Error) -> anyhow::Result<Value> {
        let mut last = e;
        for (i, wait) in [500, 1_500, 3_000].into_iter().enumerate() {
            let stale = last.downcast_ref::<ApiError>().is_some_and(|a| a.status == 400);
            let fresh = stale || i == 2;
            log::debug!("{endpoint}: {last:#}; trying again{}", if fresh { " with a fresh visitor ID" } else { "" });
            tokio::time::sleep(Duration::from_millis(wait + now_ms() % 300)).await;
            match self.call_once(endpoint, body, None, fresh).await {
                Err(e) if is_retryable(&e) => last = e,
                r => return r,
            }
        }
        Err(last)
    }

    async fn retry_signed_in(&self, endpoint: &str, body: &Value, account: Option<&Session>, e: anyhow::Error) -> anyhow::Result<Value> {
        let mut last = e;
        for wait in [700, 2_000, 4_000] {
            log::debug!("{endpoint}: {last:#}; trying again");
            tokio::time::sleep(Duration::from_millis(wait + now_ms() % 300)).await;
            match self.call_once(endpoint, body, account, false).await {
                Err(e) if is_refused(&e) => last = e,
                r => return r,
            }
        }
        Err(last)
    }

    async fn call_once(&self, endpoint: &str, body: &Value, account: Option<&Session>, fresh: bool) -> anyhow::Result<Value> {
        let mut web = self.web(fresh).await;
        if account.is_some() {
            self.wait_if_paused().await;
            self.take_in_replaced();
        }
        let current = account.and_then(|a| {
            let c = self.account().filter(|c| c.same_login(a))?;
            Some(if c.channel() == a.channel() { c } else { Arc::new(c.on_channel(a.channel().map(str::to_owned))) })
        });
        let account = current.as_deref().or(account);
        if let Some(v) = account.and_then(|a| a.visitor_data.clone()) {
            web.visitor_data = v;
        }
        anyhow::ensure!(!self.unavailable.load(Relaxed), Unavailable);
        let mut payload = body.clone();
        payload["context"] = json!({
            "client": {
                "clientName": "WEB_REMIX",
                "clientVersion": web.version,
                "platform": "DESKTOP",
                "originalUrl": ORIGIN,
                "visitorData": web.visitor_data,
                "hl": "en",
                "gl": "US",
                "timeZone": self.tz.0,
                "utcOffsetMinutes": self.tz.1,
            },
            "request": {"useSsl": true},
            "user": {"lockedSafetyMode": false},
        });
        let mut req = crate::proxy::http()
            .post(format!("{ORIGIN}/youtubei/v1/{endpoint}?prettyPrint=false"))
            .header("User-Agent", webmusic::UA)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", ORIGIN)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", &web.version)
            .timeout(Duration::from_secs(15));
        if !web.visitor_data.is_empty() {
            req = req.header("X-Goog-Visitor-Id", &web.visitor_data);
        }
        req = match account {
            Some(a) => a.sign(req, ORIGIN)?,
            None => req.header("Cookie", CONSENT),
        };
        let resp = req.json(&payload).send().await?;
        if let Some(a) = account {
            self.keep(a, resp.headers().get_all("set-cookie").iter().filter_map(|c| c.to_str().ok()));
        }
        let status = resp.status();
        let text = resp.text().await?;
        if status.is_success() {
            if account.is_some() {
                self.refusals.store(0, Relaxed);
            }
        } else {
            let msg = serde_json::from_str::<Value>(&text).ok().and_then(|v| v["error"]["message"].as_str().map(str::to_owned));
            let e = ApiError { status: status.as_u16(), msg: msg.unwrap_or_default() };
            if account.is_some() && is_throttled(&e) {
                self.refused(endpoint);
            }
            anyhow::bail!(e);
        }
        let v: Value = serde_json::from_str(&text)?;
        if account.is_some() && logged_out(&v) {
            log::warn!("{endpoint}: signed-in request answered as signed out");
            self.signed_out.store(true, Relaxed);
        }
        Ok(v)
    }

    pub async fn get_signed_in(&self, url: &str, query: &[(&str, &str)]) -> anyhow::Result<()> {
        let account = self.account().ok_or_else(|| anyhow::anyhow!("sign in first"))?;
        let req = crate::proxy::http()
            .get(url)
            .query(query)
            .header("User-Agent", webmusic::UA)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", ORIGIN)
            .timeout(Duration::from_secs(15));
        let resp = account.sign(req, ORIGIN)?.send().await?;
        self.keep(&account, resp.headers().get_all("set-cookie").iter().filter_map(|c| c.to_str().ok()));
        resp.error_for_status()?;
        Ok(())
    }

    pub async fn session_from_cookie(&self, cookie: &str) -> anyhow::Result<Session> {
        let resp = crate::proxy::http()
            .get("https://www.youtube.com/results?search_query=")
            .header("User-Agent", webmusic::UA)
            .header("Cookie", cookie)
            .timeout(Duration::from_secs(15))
            .send()
            .await?;
        if let Some(a) = self.account().filter(|a| a.cookie == cookie) {
            self.keep(&a, resp.headers().get_all("set-cookie").iter().filter_map(|c| c.to_str().ok()));
        }
        let html = resp.error_for_status()?.text().await?;
        anyhow::ensure!(html.contains("\"USER_SESSION_ID\":\""), NotSignedIn);
        let page_id = between(&html, "\"datasyncId\":\"", "\"").and_then(|d| {
            let (page, user) = d.split_once("||")?;
            (!user.is_empty()).then(|| page.to_owned())
        });
        let session_index = between(&html, "\"SESSION_INDEX\":\"", "\"").and_then(|s| s.parse().ok()).unwrap_or(0);
        let mut session = Session::new(cookie.to_owned(), session_index, page_id);
        session.visitor_data = between(&html, "\"VISITOR_DATA\":\"", "\"").filter(|v| !v.is_empty()).map(str::to_owned);
        Ok(session)
    }
}

#[derive(Debug)]
pub struct NotSignedIn;

impl std::fmt::Display for NotSignedIn {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("YouTube didn't recognise a signed-in session in those cookies")
    }
}

impl std::error::Error for NotSignedIn {}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

const YOUTUBE_WEB_VERSION: &str = "2.20251105.01.00";

pub fn logged_out(v: &Value) -> bool {
    v["responseContext"]["serviceTrackingParams"]
        .as_array()
        .into_iter()
        .flatten()
        .flat_map(|s| s["params"].as_array().into_iter().flatten())
        .any(|p| p["key"] == "logged_in" && p["value"] == "0")
}

#[derive(Debug)]
pub struct ApiError {
    pub status: u16,
    pub msg: String,
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match (self.status, self.msg.is_empty()) {
            (401, _) => f.write_str("YouTube Music signed this session out; sign in again"),
            (_, true) => write!(f, "YouTube Music returned HTTP {}", self.status),
            (_, false) => write!(f, "YouTube Music returned HTTP {}: {}", self.status, self.msg),
        }
    }
}

impl std::error::Error for ApiError {}

fn is_retryable(e: &anyhow::Error) -> bool {
    match e.downcast_ref::<ApiError>() {
        Some(a) => a.status == 400 || a.status == 403 || a.status == 429 || a.status >= 500,
        None => e.downcast_ref::<reqwest::Error>().is_some_and(|r| r.is_timeout() || r.is_connect()),
    }
}

pub async fn offered_through(http: &reqwest::Client) -> bool {
    let Ok(mut resp) = http.get(ORIGIN).header("User-Agent", webmusic::UA).header("Cookie", CONSENT).timeout(Duration::from_secs(15)).send().await else {
        return false;
    };
    let mut head = Vec::new();
    while head.len() < 16 * 1024 {
        match resp.chunk().await {
            Ok(Some(b)) => head.extend_from_slice(&b),
            Ok(None) => return false,
            Err(_) => return false,
        }
    }
    resp.status().is_success()
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

fn is_refused(e: &anyhow::Error) -> bool {
    e.downcast_ref::<ApiError>().is_some_and(is_throttled)
}

fn is_throttled(e: &ApiError) -> bool {
    e.status == 403 && !e.msg.to_ascii_lowercase().contains("permission")
}

pub fn between<'a>(s: &'a str, start: &str, end: &str) -> Option<&'a str> {
    let i = s.find(start)? + start.len();
    let j = s[i..].find(end)?;
    Some(&s[i..i + j])
}

fn local_tz() -> (String, i32) {
    let name = localzone::get_local_zone().unwrap_or_else(|| "UTC".into());
    (name, utc_offset_minutes())
}

#[cfg(windows)]
fn utc_offset_minutes() -> i32 {
    #[repr(C)]
    struct SystemTime([u16; 8]);
    #[repr(C)]
    struct TimeZoneInformation {
        bias: i32,
        standard_name: [u16; 32],
        standard_date: SystemTime,
        standard_bias: i32,
        daylight_name: [u16; 32],
        daylight_date: SystemTime,
        daylight_bias: i32,
    }
    unsafe extern "system" {
        fn GetTimeZoneInformation(info: *mut TimeZoneInformation) -> u32;
    }
    unsafe {
        let mut info: TimeZoneInformation = std::mem::zeroed();
        match GetTimeZoneInformation(&mut info) {
            1 => -(info.bias + info.standard_bias),
            2 => -(info.bias + info.daylight_bias),
            0 => -info.bias,
            _ => 0,
        }
    }
}

#[cfg(unix)]
fn utc_offset_minutes() -> i32 {
    unsafe {
        let now = libc::time(std::ptr::null_mut());
        let mut tm: libc::tm = std::mem::zeroed();
        if libc::localtime_r(&now, &mut tm).is_null() { 0 } else { (tm.tm_gmtoff / 60) as i32 }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stray_refusals_dont_pause_everything() {
        let api = Api::new(&std::env::temp_dir().join("nuvx-pause-test"));
        for _ in 0..5 {
            api.refused("search");
        }
        assert!(!api.limited(), "a few refusals only retry the requests refused");
        api.refused("search");
        assert!(api.limited(), "a long run pauses them all");
        let wait = api.paused_until.load(Relaxed) - now_ms();
        assert!((4_000..=5_000).contains(&wait), "{wait}");
        for _ in 0..10 {
            api.refused("search");
        }
        assert!(api.paused_until.load(Relaxed) - now_ms() <= 40_000);
        assert!(!is_throttled(&ApiError { status: 403, msg: "The caller does not have permission".into() }));
        assert!(is_throttled(&ApiError { status: 403, msg: String::new() }));
    }

    #[test]
    fn between_finds_ytcfg_values() {
        let html = r#"ytcfg.set({"INNERTUBE_CLIENT_VERSION":"1.20261006.10.00","VISITOR_DATA":"Cgt4eXo%3D"});"#;
        assert_eq!(between(html, "\"INNERTUBE_CLIENT_VERSION\":\"", "\""), Some("1.20261006.10.00"));
        assert_eq!(between(html, "\"VISITOR_DATA\":\"", "\""), Some("Cgt4eXo%3D"));
        assert_eq!(between(html, "\"MISSING\":\"", "\""), None);
    }
}
