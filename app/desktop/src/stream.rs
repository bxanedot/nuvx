mod decoder;
pub use decoder::{decoder, TrackDecoder};

use std::future::Future;
use std::io::{self, Read, Seek, SeekFrom};
use std::pin::Pin;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use bytes::Bytes;

const BLOCK: u64 = 256 * 1024;
const WORKERS: usize = 4;
const RETRIES: u32 = 6;
const STALL: Duration = Duration::from_secs(20);
const MAX_REFRESHES: u32 = 2;
const HEAD_BLOCKS: usize = 2;
const WINDOWED_FROM: u64 = 40 * 1024 * 1024;
const AHEAD: usize = 40;
const BEHIND: usize = 8;

pub type Refresh = Arc<dyn Fn() -> Pin<Box<dyn Future<Output = anyhow::Result<String>> + Send>> + Send + Sync>;

#[derive(Clone, Debug, PartialEq)]
pub enum Format {
    Aac,
    Opus,
    File { hint: &'static str, mime: Option<String> },
    Pcm { rate: u32, channels: u16 },
}

impl Format {
    pub fn from_opus(opus: bool) -> Self {
        if opus { Self::Opus } else { Self::Aac }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Ranging {
    Query,
    Header,
}

struct Inner {
    blocks: Vec<Option<Bytes>>,
    in_flight: Vec<bool>,
    want: usize,
    error: Option<String>,
    readers: usize,
    cancelled: bool,
    limit: usize,
    wants: Vec<(u64, usize)>,
    windowed: bool,
    ever: Vec<bool>,
    fetched: usize,
}

impl Inner {
    fn in_window(&self, i: usize) -> bool {
        !self.windowed || self.wants.is_empty() || self.wants.iter().any(|&(_, w)| i + BEHIND >= w && i < w + AHEAD)
    }
}

enum Claim {
    Block(usize),
    Wait,
    Done,
}

pub struct Shared {
    inner: Mutex<Inner>,
    ready: Condvar,
    pub total: u64,
    pub duration_ms: u64,
    pub loudness_db: Option<f32>,
    pub tempo: Arc<std::sync::OnceLock<Option<f32>>>,
    pub format: Format,
    pub info: Option<crate::hires::Info>,
    done: tokio::sync::watch::Sender<bool>,
    changed: tokio::sync::Notify,
}

impl Shared {
    pub fn new(total: u64, duration_ms: u64, loudness_db: Option<f32>, format: Format, info: Option<crate::hires::Info>, head_only: bool) -> Arc<Self> {
        let n = total.div_ceil(BLOCK) as usize;
        let limit = if head_only { HEAD_BLOCKS.min(n) } else { n };
        Arc::new(Self {
            inner: Mutex::new(Inner {
                blocks: vec![None; n],
                in_flight: vec![false; n],
                want: 0,
                error: None,
                readers: 0,
                cancelled: false,
                limit,
                wants: Vec::new(),
                windowed: total > WINDOWED_FROM,
                ever: vec![false; n],
                fetched: 0,
            }),
            ready: Condvar::new(),
            total,
            duration_ms,
            loudness_db,
            tempo: Arc::new(std::sync::OnceLock::new()),
            format,
            info,
            done: tokio::sync::watch::Sender::new(false),
            changed: tokio::sync::Notify::new(),
        })
    }

    pub fn from_bytes(data: Vec<u8>, duration_ms: u64, loudness_db: Option<f32>) -> Arc<Self> {
        Self::from_bytes_with_format(data, duration_ms, loudness_db, Format::Aac, None)
    }

    pub fn from_bytes_with_format(data: Vec<u8>, duration_ms: u64, loudness_db: Option<f32>, format: Format, info: Option<crate::hires::Info>) -> Arc<Self> {
        let total = data.len() as u64;
        let data = Bytes::from(data);
        let blocks: Vec<Option<Bytes>> =
            (0..total.div_ceil(BLOCK)).map(|i| Some(data.slice(i as usize * BLOCK as usize..((i + 1) * BLOCK).min(total) as usize))).collect();
        let n = blocks.len();
        let shared = Self::new(total, duration_ms, loudness_db, format, info, false);
        {
            let mut inner = shared.inner.lock().unwrap();
            inner.blocks = blocks;
            inner.windowed = false;
            inner.ever = vec![true; n];
            inner.fetched = n;
        }
        shared.done.send_replace(true);
        shared
    }

    pub fn bytes(&self) -> Option<Vec<u8>> {
        let inner = self.inner.lock().unwrap();
        let mut out = Vec::with_capacity(self.total as usize);
        for b in &inner.blocks {
            out.extend_from_slice(b.as_ref()?);
        }
        Some(out)
    }

    pub fn is_complete(&self) -> bool {
        self.inner.lock().unwrap().blocks.iter().all(Option::is_some)
    }

    pub async fn finished(&self) {
        let _ = self.done.subscribe().wait_for(|d| *d).await;
    }

    pub fn error(&self) -> Option<String> {
        self.inner.lock().unwrap().error.clone()
    }

    pub fn cancel(&self) {
        self.inner.lock().unwrap().cancelled = true;
        self.ready.notify_all();
        self.changed.notify_waiters();
    }

    fn claim(&self) -> Claim {
        let mut inner = self.inner.lock().unwrap();
        if inner.cancelled || inner.error.is_some() {
            return Claim::Done;
        }
        let n = inner.blocks.len();
        let start = inner.want.min(n.saturating_sub(1));
        let mut missing = false;
        for i in (start..n).chain(0..start) {
            if inner.blocks[i].is_some() || inner.in_flight[i] {
                continue;
            }
            missing = true;
            if i < inner.limit && inner.in_window(i) {
                inner.in_flight[i] = true;
                return Claim::Block(i);
            }
        }
        if missing || (inner.windowed && inner.readers > 0) { Claim::Wait } else { Claim::Done }
    }

    fn put(&self, idx: usize, data: Bytes) {
        let mut inner = self.inner.lock().unwrap();
        inner.blocks[idx] = Some(data);
        inner.in_flight[idx] = false;
        if !std::mem::replace(&mut inner.ever[idx], true) {
            inner.fetched += 1;
            if inner.fetched == inner.blocks.len() {
                self.done.send_replace(true);
            }
        }
        if inner.windowed && !inner.wants.is_empty() {
            for i in 0..inner.blocks.len() {
                if inner.blocks[i].is_some() && !inner.in_window(i) {
                    inner.blocks[i] = None;
                }
            }
        }
        self.ready.notify_all();
    }

    pub fn fail(&self, err: String) {
        log::error!("download failed: {err}");
        FAIL_FROM.store(usize::MAX, std::sync::atomic::Ordering::Relaxed);
        self.inner.lock().unwrap().error = Some(err);
        self.ready.notify_all();
    }

    fn range(&self, idx: usize) -> (u64, u64) {
        let start = idx as u64 * BLOCK;
        (start, (start + BLOCK).min(self.total) - 1)
    }
}

struct UrlSlot {
    url: tokio::sync::Mutex<(String, u32)>,
    refresh: Option<Refresh>,
}

impl UrlSlot {
    async fn renew(&self, seen: u32) -> bool {
        let mut slot = self.url.lock().await;
        if slot.1 != seen {
            return true;
        }
        let Some(refresh) = self.refresh.as_ref().filter(|_| slot.1 < MAX_REFRESHES) else { return false };
        match refresh().await {
            Ok(url) => {
                log::info!("stream URL refreshed after 403");
                *slot = (url, slot.1 + 1);
                true
            }
            Err(e) => {
                log::warn!("stream URL refresh failed: {e:#}");
                false
            }
        }
    }
}

pub async fn download(http: reqwest::Client, url: String, ua: String, shared: Arc<Shared>, refresh: Option<Refresh>, ranging: Ranging) {
    if ranging == Ranging::Header && !ranges_supported(&http, &url, &ua).await {
        download_whole(&http, &url, &ua, &shared).await;
        return;
    }
    let slot = Arc::new(UrlSlot { url: tokio::sync::Mutex::new((url, 0)), refresh });
    let mut set = tokio::task::JoinSet::new();
    for _ in 0..WORKERS {
        let (http, ua, shared, slot) = (http.clone(), ua.clone(), shared.clone(), slot.clone());
        set.spawn(async move {
            loop {
                let changed = shared.changed.notified();
                tokio::pin!(changed);
                changed.as_mut().enable();
                let idx = match shared.claim() {
                    Claim::Block(idx) => idx,
                    Claim::Wait => {
                        changed.await;
                        continue;
                    }
                    Claim::Done => break,
                };
                let (a, b) = shared.range(idx);
                let mut attempt = 0;
                loop {
                    let (url, version) = slot.url.lock().await.clone();
                    let client = if version > 0 { crate::proxy::http() } else { http.clone() };
                    let res = if idx >= FAIL_FROM.load(std::sync::atomic::Ordering::Relaxed) {
                        Err(anyhow::anyhow!("simulated network failure"))
                    } else {
                        match ranging {
                            Ranging::Query => fetch(&client, &format!("{url}&range={a}-{b}"), &ua, b - a + 1).await,
                            Ranging::Header => fetch_range(&client, &url, &ua, a, b, shared.total).await,
                        }
                    };
                    match res {
                        Ok(data) => {
                            shared.put(idx, data);
                            break;
                        }
                        Err(e) if is_forbidden(&e) && slot.renew(version).await => {}
                        Err(e) if attempt < RETRIES && !is_forbidden(&e) => {
                            attempt += 1;
                            log::warn!("range {a}-{b}: {e:#} (retry {attempt})");
                            tokio::time::sleep(Duration::from_millis(400 * (attempt * attempt) as u64)).await;
                        }
                        Err(e) => {
                            shared.fail(format!("range {a}-{b}: {e:#}"));
                            return;
                        }
                    }
                }
            }
        });
    }
    while set.join_next().await.is_some() {}
    shared.done.send_replace(true);
}

fn is_forbidden(e: &anyhow::Error) -> bool {
    e.downcast_ref::<reqwest::Error>()
        .and_then(reqwest::Error::status)
        .is_some_and(|s| s == reqwest::StatusCode::FORBIDDEN)
}

static FAIL_FROM: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(usize::MAX);

pub fn init_dev_hooks() {
    if let Some(b) = std::env::var("NUVX_STREAM_FAIL").ok().and_then(|v| v.parse().ok()) {
        FAIL_FROM.store(b, std::sync::atomic::Ordering::Relaxed);
    }
}

async fn fetch(http: &reqwest::Client, url: &str, ua: &str, expect: u64) -> anyhow::Result<Bytes> {
    let stalled = || anyhow::anyhow!("no data for {} s", STALL.as_secs());
    let mut resp = tokio::time::timeout(STALL, http.get(url).header("User-Agent", ua).send())
        .await
        .map_err(|_| stalled())?
        .and_then(reqwest::Response::error_for_status)
        .map_err(reqwest::Error::without_url)?;
    let mut buf = bytes::BytesMut::with_capacity(expect as usize);
    while let Some(chunk) = tokio::time::timeout(STALL, resp.chunk()).await.map_err(|_| stalled())?.map_err(reqwest::Error::without_url)? {
        buf.extend_from_slice(&chunk);
    }
    anyhow::ensure!(buf.len() as u64 == expect, "short read: {} of {expect} bytes", buf.len());
    Ok(buf.freeze())
}

async fn fetch_range(http: &reqwest::Client, url: &str, ua: &str, a: u64, b: u64, total: u64) -> anyhow::Result<Bytes> {
    let stalled = || anyhow::anyhow!("no data for {} s", STALL.as_secs());
    let mut resp = tokio::time::timeout(
        STALL,
        http.get(url)
            .header("User-Agent", ua)
            .header("Range", format!("bytes={a}-{b}"))
            .header("Accept-Encoding", "identity")
            .send(),
    )
    .await
    .map_err(|_| stalled())?
    .and_then(reqwest::Response::error_for_status)
    .map_err(reqwest::Error::without_url)?;
    let partial = resp.status() == reqwest::StatusCode::PARTIAL_CONTENT;
    let expect = if partial { b - a + 1 } else { total };
    let mut buf = bytes::BytesMut::with_capacity(expect as usize);
    while let Some(chunk) = tokio::time::timeout(STALL, resp.chunk()).await.map_err(|_| stalled())?.map_err(reqwest::Error::without_url)? {
        buf.extend_from_slice(&chunk);
    }
    anyhow::ensure!(buf.len() as u64 == expect, "short read: {} of {expect} bytes", buf.len());
    if partial {
        return Ok(buf.freeze());
    }
    Ok(buf.freeze().slice(a as usize..=b as usize))
}

async fn ranges_supported(http: &reqwest::Client, url: &str, ua: &str) -> bool {
    match tokio::time::timeout(STALL, http.get(url).header("User-Agent", ua).header("Range", "bytes=0-0").header("Accept-Encoding", "identity").send()).await {
        Ok(Ok(r)) => r.status() == reqwest::StatusCode::PARTIAL_CONTENT,
        _ => true,
    }
}

async fn download_whole(http: &reqwest::Client, url: &str, ua: &str, shared: &Arc<Shared>) {
    let stalled = || anyhow::anyhow!("no data for {} s", STALL.as_secs());
    let result = async {
        let mut resp = tokio::time::timeout(
            STALL,
            http.get(url).header("User-Agent", ua).header("Accept-Encoding", "identity").send(),
        )
        .await
        .map_err(|_| stalled())?
        .and_then(reqwest::Response::error_for_status)
        .map_err(reqwest::Error::without_url)?;
        let block = BLOCK as usize;
        let blocks = shared.inner.lock().unwrap().blocks.len();
        let mut buf = bytes::BytesMut::with_capacity(block);
        let (mut idx, mut received) = (0usize, 0u64);
        while let Some(chunk) = tokio::time::timeout(STALL, resp.chunk()).await.map_err(|_| stalled())?.map_err(reqwest::Error::without_url)? {
            received += chunk.len() as u64;
            buf.extend_from_slice(&chunk);
            while buf.len() >= block && idx < blocks {
                shared.put(idx, buf.split_to(block).freeze());
                idx += 1;
            }
            if shared.inner.lock().unwrap().cancelled {
                return anyhow::Ok(());
            }
        }
        anyhow::ensure!(received == shared.total, "short read: {received} of {} bytes", shared.total);
        if idx < blocks {
            shared.put(idx, buf.split().freeze());
        }
        anyhow::Ok(())
    }
    .await;
    match result {
        Ok(()) => {
            shared.done.send_replace(true);
        }
        Err(e) => {
            if !shared.inner.lock().unwrap().cancelled {
                shared.fail(format!("stream: {e:#}"));
            }
        }
    }
}

pub struct StreamReader {
    shared: Arc<Shared>,
    pos: u64,
    id: u64,
    block: usize,
}

impl StreamReader {
    pub fn new(shared: Arc<Shared>) -> Self {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let id = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        {
            let mut inner = shared.inner.lock().unwrap();
            inner.readers += 1;
            inner.limit = inner.blocks.len();
            inner.wants.push((id, 0));
        }
        shared.changed.notify_waiters();
        Self { shared, pos: 0, id, block: 0 }
    }
}

impl Drop for StreamReader {
    fn drop(&mut self) {
        let mut inner = self.shared.inner.lock().unwrap();
        inner.readers -= 1;
        inner.wants.retain(|&(id, _)| id != self.id);
        if inner.readers == 0 {
            inner.cancelled = true;
        }
        self.shared.changed.notify_waiters();
    }
}

impl Read for StreamReader {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if out.is_empty() || self.pos >= self.shared.total {
            return Ok(0);
        }
        let idx = (self.pos / BLOCK) as usize;
        let mut inner = self.shared.inner.lock().unwrap();
        inner.want = idx;
        if idx != self.block {
            self.block = idx;
            if let Some(w) = inner.wants.iter_mut().find(|(id, _)| *id == self.id) {
                w.1 = idx;
            }
            if inner.windowed {
                self.shared.changed.notify_waiters();
            }
        }
        while inner.blocks[idx].is_none() {
            if let Some(e) = &inner.error {
                return Err(io::Error::other(e.clone()));
            }
            if inner.cancelled {
                return Err(io::Error::other("download cancelled"));
            }
            inner = self.shared.ready.wait(inner).unwrap();
        }
        let block = inner.blocks[idx].as_ref().unwrap();
        let off = (self.pos - idx as u64 * BLOCK) as usize;
        let n = out.len().min(block.len() - off);
        out[..n].copy_from_slice(&block[off..off + n]);
        self.pos += n as u64;
        Ok(n)
    }
}

impl Seek for StreamReader {
    fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
        let total = self.shared.total as i64;
        let new = match to {
            SeekFrom::Start(p) => p as i64,
            SeekFrom::End(off) => total + off,
            SeekFrom::Current(off) => self.pos as i64 + off,
        };
        if new < 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "seek before start"));
        }
        self.pos = new as u64;
        Ok(self.pos)
    }
}
