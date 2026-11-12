mod catalog;
mod dispatch;
mod playback;
mod streaming;
mod messages;
mod likes;

pub use likes::{like_many, LIKE_GAP, LIKE_WAIT, LIKES};
pub use messages::{ArtistPage, Channels, Collection, Explore, Library, Req, Resp, SearchResults};

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::Ordering;

use crossbeam_channel::Sender;
use tokio::runtime::Runtime;

use crate::audio::{AudioCmd, AudioHandle};
use crate::lyrics::{self, LyricsSet};
use crate::model::{self, Card, Target, Track, sized};
use crate::stream::{self, Shared};
use crate::{hires, innertube, jsc, webmusic, ytdlp, ytm, ytmapi};



type StreamSlot = Arc<std::sync::Mutex<Option<(String, Arc<Shared>)>>>;
type SongCell = Arc<tokio::sync::OnceCell<Option<Track>>>;

const PLAY_SONG_WAIT: std::time::Duration = std::time::Duration::from_millis(2500);
const PREFETCH_SONG_WAIT: std::time::Duration = std::time::Duration::from_secs(20);
const HIRES_WAIT: std::time::Duration = std::time::Duration::from_secs(12);

#[derive(Clone)]
pub struct Backend {
    rt: Arc<Runtime>,
    api: Arc<ytmapi::Api>,
    tx: Sender<Resp>,
    audio: AudioHandle,
    wake: Arc<dyn Fn() + Send + Sync>,
    data_dir: PathBuf,
    lyrics: Arc<lyrics::Client>,
    translate: Arc<crate::translate::Client>,
    prefetched: StreamSlot,
    current: StreamSlot,
    jsc: Arc<jsc::Solver>,
    premium: Arc<tokio::sync::Mutex<Result<bool, Option<std::time::Instant>>>>,
    hq: Arc<std::sync::atomic::AtomicBool>,
    hires: Arc<std::sync::RwLock<Vec<Arc<hires::Client>>>>,
    atmos: Arc<std::sync::atomic::AtomicBool>,
    reverted: Arc<std::sync::Mutex<std::collections::HashSet<String>>>,
    songs: Arc<std::sync::Mutex<std::collections::HashMap<String, SongCell>>>,
}

impl Backend {
    pub fn new(
        storage: PathBuf,
        tx: Sender<Resp>,
        audio: AudioHandle,
        wake: Arc<dyn Fn() + Send + Sync>,
    ) -> anyhow::Result<Self> {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("net")
            .enable_all()
            .build()?;
        std::fs::create_dir_all(&storage)?;
        let data_dir = storage;
        let http = crate::proxy::http();
        let lyrics = Arc::new(lyrics::Client::new(http.clone(), &data_dir));
        let translate = Arc::new(crate::translate::Client::new(http, &data_dir));
        let jsc = Arc::new(jsc::Solver::new(&data_dir));
        let api = Arc::new(ytmapi::Api::new(&data_dir));
        if crate::proxy::needs_relay()
            && let Err(e) = rt.block_on(crate::proxy::start_relay())
        {
            log::warn!("proxy relay: {e:#}");
        }
        let backend = Self { rt: Arc::new(rt), api, tx, audio, wake, prefetched: Default::default(), current: Default::default(), data_dir, lyrics, translate, jsc, premium: Arc::new(tokio::sync::Mutex::new(Err(None))), hq: Default::default(), hires: Default::default(), atmos: Default::default(), reverted: Default::default(), songs: Default::default() };
        backend.keep_signed_in();
        Ok(backend)
    }

    fn keep_signed_in(&self) {
        const EVERY: std::time::Duration = std::time::Duration::from_secs(10 * 60);
        let this = self.clone();
        self.rt.spawn(async move {
            loop {
                if this.api.account().is_some() {
                    this.api.ensure_visitor().await;
                    match this.api.rotate().await {
                        Ok(()) => log::debug!("session cookies refreshed"),
                        Err(e) if e.is::<webmusic::Revoked>() => this.check_session().await,
                        Err(e) => log::info!("refreshing session cookies failed: {e:#}"),
                    }
                }
                tokio::time::sleep(EVERY).await;
            }
        });
    }

    async fn check_session(&self) {
        static CHECKING: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());
        let _one = CHECKING.lock().await;
        if self.api.account().is_none() {
            return;
        }
        for attempt in 0..2 {
            if let Err(e) = self.api.rotate().await {
                log::info!("rotating cookies while checking the session: {e:#}");
            }
            let Some(account) = self.api.account() else { return };
            match self.api.session_from_cookie(&account.cookie).await {
                Ok(_) => return,
                Err(e) if e.is::<ytmapi::NotSignedIn>() => {
                    log::warn!("signed-in session looks signed out ({}): {e:#}", if attempt == 0 { "checking again in a minute" } else { "twice" });
                    if attempt == 0 {
                        tokio::time::sleep(std::time::Duration::from_secs(60)).await;
                    }
                }
                Err(e) => {
                    log::info!("couldn't check the session: {e:#}");
                    return;
                }
            }
        }
        log::warn!("signed-in session no longer valid");
        self.forget_session().await;
        let _ = self.tx.send(Resp::LoggedIn(false));
        let _ = self.tx.send(Resp::Error("Your YouTube sign-in expired. Sign in again to get your library back".into()));
        self.wake();
    }

    async fn switch_channel(&self, page_id: Option<String>, epoch: u64) -> anyhow::Result<u64> {
        let _likes = LIKES.try_lock().map_err(|_| anyhow::anyhow!("Wait until the likes going through are done"))?;
        let mut premium = self.premium.lock().await;
        let epoch = self.api.switch_channel(page_id, epoch)?;
        *premium = Err(None);
        drop(premium);
        self.warm_signed_in();
        log::info!("switched channel");
        Ok(epoch)
    }

    async fn forget_session(&self) {
        self.api.sign_out();
        *self.premium.lock().await = Err(None);
        let profile = self.login_profile();
        let _ = tokio::task::spawn_blocking(move || crate::login::forget(&profile)).await;
    }

    pub fn api(&self) -> Arc<ytmapi::Api> {
        self.api.clone()
    }

    pub fn http(&self) -> reqwest::Client {
        crate::proxy::http()
    }

    pub fn spawn<F: Future<Output = ()> + Send + 'static>(&self, f: F) {
        self.rt.spawn(f);
    }

    pub fn wake(&self) {
        (self.wake)()
    }

    pub fn request(&self, req: Req) {
        let (what, quiet) = (req.name(), req.quiet());
        let play_gen = match &req {
            Req::Play { generation, .. } => Some(*generation),
            _ => None,
        };
        let collection = match &req {
            Req::Album { id, load } | Req::Playlist { id, load, .. } => Some((id.clone(), *load)),
            _ => None,
        };
        let this = self.clone();
        let t0 = std::time::Instant::now();
        let epoch = self.api.epoch();
        let task = self.rt.spawn(async move {
            let r = this.handle(req).await;
            log::debug!("{what}: done in {:?}", t0.elapsed());
            r
        });
        let this = self.clone();
        self.rt.spawn(async move {
            let resp = match task.await {
                Ok(resp) => resp,
                Err(e) if e.is_panic() => {
                    log::error!("{what}: request panicked");
                    match play_gen {
                        Some(generation) => Some(Resp::PlayError { generation, msg: "YouTube didn't respond properly".into() }),
                        None => (!quiet).then(|| Resp::Error(format!("{what} failed: YouTube didn't respond properly"))),
                    }
                }
                Err(_) => None,
            };
            if let Some((id, load)) = collection
                && !matches!(resp, Some(Resp::Collection { .. } | Resp::CollectionMore { .. }))
            {
                let _ = this.tx.send(Resp::ended(id, load));
            }
            let resp = resp.filter(|r| {
                let stale = this.api.epoch() != epoch && r.is_account_scoped();
                if stale {
                    log::debug!("{what}: answer for the previous account or channel dropped");
                }
                !stale
            });
            if let Some(resp) = resp {
                let _ = this.tx.send(resp);
                this.wake();
            }
            if this.api.take_signed_out() {
                this.check_session().await;
            }
        });
    }

    async fn prefetch(&self, video_id: String, song_of: Option<Track>, track: Option<Track>) -> anyhow::Result<()> {
        let generation = self.audio.status.wanted_gen.load(Ordering::Relaxed);
        let song = self.song_version(&video_id, song_of.as_ref(), PREFETCH_SONG_WAIT).await;
        let meta = song.clone().or_else(|| song_of.clone()).or_else(|| track);
        let video_id = song.map_or(video_id, |s| s.id);
        let playing = self.current.lock().unwrap().as_ref().map(|(_, s)| s.clone());
        if let Some(s) = playing {
            s.finished().await;
        }
        if self.audio.status.wanted_gen.load(Ordering::Relaxed) != generation
            || self.prefetched.lock().unwrap().as_ref().is_some_and(|(id, _)| *id == video_id)
        {
            return Ok(());
        }
        log::debug!("prefetch {video_id}");
        let shared = self.open_stream(&video_id, true, meta.as_ref()).await?;
        if let Some((_, old)) = self.prefetched.lock().unwrap().replace((video_id, shared)) {
            old.cancel();
        }
        Ok(())
    }

    async fn lyrics_for(&self, t: &Track) -> LyricsSet {
        let artist = t.artist_line();
        let query = lyrics::Query {
            video_id: &t.id,
            song: &t.title,
            artist: &artist,
            album: t.album.as_ref().map(|a| a.name.as_str()),
            duration: match t.duration {
                Some(d) => Some(d),
                None => self.stream_duration(&t.id).await,
            },
        };
        let mut set = self.lyrics.fetch(&query).await.unwrap_or_else(|e| {
            log::warn!("lyrics failed: {e:#}");
            LyricsSet::default()
        });
        if let Ok(fail) = std::env::var("NUVX_LYRICS_FAIL") {
            set.sources.retain(|l| !fail.split(',').any(|k| k.trim() == l.provider.key()));
        }
        set
    }

    async fn stream_duration(&self, video_id: &str) -> Option<u32> {
        for _ in 0..150 {
            let ms = self.current.lock().unwrap().as_ref().filter(|(id, _)| id == video_id).map(|(_, s)| s.duration_ms);
            let ms = ms.filter(|ms| *ms > 0).or_else(|| self.audio.status.duration().map(|d| d.as_millis() as u64));
            if let Some(ms) = ms.filter(|ms| *ms > 0) {
                return Some((ms / 1000) as u32);
            }
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        }
        log::info!("lyrics: no duration for {video_id}");
        None
    }

    async fn set_cookie(&self, cookie: &str) -> anyhow::Result<()> {
        let cookie = cookie.trim();
        anyhow::ensure!(!cookie.is_empty(), "no youtube.com cookies found");
        let session = self.api.session_from_cookie(cookie).await.inspect_err(|e| {
            let names: Vec<&str> = cookie.split(';').filter_map(|kv| kv.trim().split('=').next()).take(40).collect();
            log::warn!("cookie sign-in rejected: {e:#}; cookie names: {names:?}");
        })?;
        self.api.sign_in(session);
        let profile = self.login_profile();
        let _ = tokio::task::spawn_blocking(move || crate::login::forget(&profile)).await;
        Ok(())
    }

    pub fn warm_signed_in(&self) {
        let this = self.clone();
        self.rt.spawn(async move {
            let Some(session) = this.api.account() else { return };
            if this.is_premium(&session).await
                && let Err(e) = this.jsc.warm(&this.http()).await
            {
                log::warn!("solver warm-up failed: {e:#}");
            }
        });
    }

    fn known_not_premium(&self) -> bool {
        self.premium.try_lock().is_ok_and(|p| matches!(*p, Ok(false)))
    }

    async fn is_premium(&self, session: &webmusic::Session) -> bool {
        const PREMIUM_RETRY: std::time::Duration = std::time::Duration::from_secs(300);
        let mut premium = self.premium.lock().await;
        match *premium {
            Ok(p) => return p,
            Err(Some(at)) if at.elapsed() < PREMIUM_RETRY => return false,
            Err(_) => {}
        }
        match session.is_premium(&self.http()).await {
            Ok(p) => {
                log::info!("account has Premium: {p}");
                *premium = Ok(p);
                p
            }
            Err(e) => {
                log::warn!("Premium check failed: {e:#}");
                *premium = Err(Some(std::time::Instant::now()));
                false
            }
        }
    }

    async fn video_file(&self, id: &str, height: u32) -> anyhow::Result<PathBuf> {
        let dir = std::env::temp_dir().join("nuvx-video");
        let path = dir.join(format!("{id}-{height}.mp4"));
        if path.exists() {
            return Ok(path);
        }
        let vd = self.api.visitor_data(false).await?;
        let (url, size) = innertube::video_stream(&self.http(), id, &vd, height).await?;
        let shared = stream::Shared::new(size, 0, None, stream::Format::Aac, None, false);
        stream::download(self.http(), url, innertube::UA.to_owned(), shared.clone(), None, stream::Ranging::Query).await;
        let mut bytes = shared.bytes().ok_or_else(|| anyhow::anyhow!("download incomplete"))?;
        crate::motion::drop_edit_lists(&mut bytes);
        std::fs::create_dir_all(&dir)?;
        let mut old: Vec<_> = std::fs::read_dir(&dir)?.flatten().filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path()))).collect();
        old.sort();
        for (_, p) in old.iter().rev().skip(2) {
            let _ = std::fs::remove_file(p);
        }
        std::fs::write(&path, bytes)?;
        log::info!("video {id}: {:.1} MB saved", size as f64 / 1e6);
        Ok(path)
    }

    async fn visionos_stream(&self, video_id: &str, opus: bool) -> anyhow::Result<innertube::Stream> {
        let vd = self.api.visitor_data(false).await?;
        match innertube::audio_stream(&self.http(), video_id, &vd, opus).await {
            Ok(s) => Ok(s),
            Err(e) if needs_account(&e) => Err(e),
            Err(e) => {
                log::info!("visionOS player: {e:#}; retrying with new visitor data");
                let vd = self.api.visitor_data(true).await?;
                innertube::audio_stream(&self.http(), video_id, &vd, opus).await
            }
        }
    }

    pub fn login_profile(&self) -> PathBuf {
        self.data_dir.join("webview")
    }

    pub fn report_error(&self, msg: String) {
        let _ = self.tx.send(Resp::Error(msg));
        self.wake();
    }

    pub fn data_dir(&self) -> &std::path::Path {
        &self.data_dir
    }

    pub fn set_high_quality(&self, on: bool) {
        self.hq.store(on, Ordering::Relaxed);
    }

    pub fn set_prefer_atmos(&self, on: bool) {
        self.atmos.store(on, Ordering::Relaxed);
    }

    pub fn revert_quality(&self, video_id: &str) {
        self.reverted.lock().unwrap().insert(video_id.to_owned());
    }

    pub fn upgrade_quality(&self, video_id: &str) {
        self.reverted.lock().unwrap().remove(video_id);
    }

    pub fn set_hires_source(&self, links: &str) {
        let mut clients: Vec<Arc<hires::Client>> = Vec::new();
        let mut seen = std::collections::HashSet::new();
        let mut rejected = 0;
        for line in links.lines() {
            let line = line.trim();
            if line.is_empty() || !seen.insert(line.to_owned()) {
                continue;
            }
            match hires::Client::new(line) {
                Some(client) => clients.push(Arc::new(client)),
                None => rejected += 1,
            }
        }
        let hosts: Vec<String> = clients.iter().map(|c| c.host()).collect();
        match clients.len() {
            0 if rejected == 0 => log::info!("hi-res sources: none"),
            0 => log::warn!("hi-res sources: none usable ({rejected} not an http(s) link)"),
            1 => log::info!("hi-res source set: {}", hosts[0]),
            n => log::info!("{n} hi-res sources set: {}", hosts.join(", ")),
        }
        if rejected > 0 && !clients.is_empty() {
            log::warn!("hi-res sources: {rejected} entry(s) aren't http(s) links and were skipped");
        }
        *self.hires.write().unwrap() = clients;
    }

    async fn like_many(&self, ids: Vec<String>, like: bool) -> anyhow::Result<Resp> {
        let total = ids.len();
        let (done, failed) = like_many(&self.api, ids, like, |taken, n| {
            let _ = self.tx.send(Resp::LikeProgress { taken: taken.into_iter().map(str::to_owned).collect(), done: n, total, like });
            self.wake();
        })
        .await;
        Ok(Resp::LikedMany { video_ids: done, failed, like })
    }

    async fn stream_playlist(&self, id: String, load: u64, me: bool) -> anyhow::Result<Resp> {
        let (c, mut token, limit) = ytm::playlist_start(&self.api, &id, me, None).await?;
        let mut have = c.tracks.len();
        let mut resp = Resp::Collection { load, c, more: token.is_some() && have < limit };
        while let Some(t) = token.take().filter(|_| have < limit) {
            let _ = self.tx.send(resp);
            self.wake();
            let (mut tracks, mut set_ids, next) = ytm::playlist_more(&self.api, &t, me).await?;
            tracks.truncate(limit - have);
            set_ids.truncate(limit - have);
            have += tracks.len();
            token = next.filter(|_| !tracks.is_empty());
            resp = Resp::CollectionMore { id: id.clone(), load, more: token.is_some() && have < limit, tracks, set_ids };
        }
        Ok(resp)
    }

    pub fn is_logged_in(&self) -> bool {
        self.api.account().is_some()
    }
}

fn reports_plays() -> bool {
    std::env::var_os("NUVX_BACKGROUND").is_none() || std::env::var_os("NUVX_REPORT_PLAYS").is_some()
}

#[derive(Clone, Copy, Debug)]
enum Source {
    VisionOs,
    WebMusic,
    YtDlp,
    HiRes,
}

struct ResolvedStream {
    url: String,
    size: u64,
    duration_ms: u64,
    user_agent: String,
    loudness_db: Option<f32>,
    format: stream::Format,
    info: Option<hires::Info>,
    ranging: stream::Ranging,
    data: Option<Vec<u8>>,
}

fn needs_account(e: &anyhow::Error) -> bool {
    let msg = e.to_string();
    msg.contains("LOGIN_REQUIRED") || msg.contains("Premium")
}

fn popular_new_albums(albums: &[Card], ranked_artists: &[String], preferred_artists: &[String]) -> Vec<Card> {
    let chart_ids: std::collections::HashSet<&str> = ranked_artists.iter().chain(preferred_artists).map(String::as_str).collect();
    let mut seen = std::collections::HashSet::new();
    albums
        .iter()
        .filter(|album| album.links.iter().any(|a| a.id.as_deref().is_some_and(|id| chart_ids.contains(id))))
        .filter(|album| seen.insert(match &album.target {
            Target::Album(id) => id.as_str(),
            _ => album.title.as_str(),
        }))
        .take(16)
        .cloned()
        .collect()
}

fn saved_playlist_visible(card: &Card) -> bool {
    card.target != Target::Playlist("SE".into()) || card.subtitle.split_whitespace().any(|w| w.parse::<u64>().is_ok_and(|n| n > 0))
}

pub fn compact(n: u64) -> String {
    let (v, unit) = match n {
        0..1_000 => return n.to_string(),
        1_000..1_000_000 => (n as f64 / 1e3, "K"),
        1_000_000..1_000_000_000 => (n as f64 / 1e6, "M"),
        _ => (n as f64 / 1e9, "B"),
    };
    let s = if v >= 100.0 { format!("{v:.0}") } else { format!("{v:.1}") };
    format!("{}{unit}", s.strip_suffix(".0").unwrap_or(&s))
}

pub fn art_url(t: &Track) -> Option<String> {
    model::pick(&t.thumbs, 120).map(|u| sized(u, 544))
}

#[cfg(test)]
mod explore_tests {
    use super::*;

    fn card(title: &str, target: Target, artist_id: Option<&str>, subtitle: &str) -> Card {
        Card {
            title: title.into(),
            subtitle: subtitle.into(),
            thumbs: vec![],
            target,
            round: false,
            links: artist_id.map(|id| model::Link { name: "Artist".into(), id: Some(id.into()) }).into_iter().collect(),
        }
    }

    #[test]
    fn new_releases_only_include_charting_artists_and_deduplicate() {
        let album = |id: &str, artist_id: &str| card(id, Target::Album(id.into()), Some(artist_id), "Album");
        let albums = vec![album("obscure", "other"), album("favorite-release", "favorite"), album("first-release", "popular"), album("second-release", "popular"), album("first-release", "popular")];
        let found = popular_new_albums(&albums, &["popular".into(), "popular".into()], &["favorite".into()]);
        assert_eq!(found.iter().map(|c| c.title.as_str()).collect::<Vec<_>>(), ["favorite-release", "first-release", "second-release"]);
    }

    #[test]
    fn empty_episodes_for_later_is_hidden() {
        let item = |id: &str, subtitle: &str| card("Episodes for later", Target::Playlist(id.into()), None, subtitle);
        assert!(!saved_playlist_visible(&item("SE", "Auto playlist")));
        assert!(!saved_playlist_visible(&item("SE", "Auto playlist • 0 episodes")));
        assert!(saved_playlist_visible(&item("SE", "Auto playlist • 2 episodes")));
        assert!(saved_playlist_visible(&item("PLcustom", "Playlist")));
    }
}
