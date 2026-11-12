use std::path::{Path, PathBuf};
use std::time::Duration;

mod ttml;
mod qrc;
mod lrc;
#[allow(unused_imports)]
pub use ttml::{Ttml, ttml_time};
pub use ttml::parse_ttml;
pub use qrc::parse_qrc;
pub use lrc::{lrc_fixers, parse_lrc, parse_plain};
use lrc::lrc_songwriters;
use qrc::qrc_songwriters;

use serde_json::Value;

const API: &str = "https://lyrics.api.dacubeking.com/";
const UNISON_API: &str = "https://unison.betterlyrics.org/lyrics";
const LRCRED_API: &str = "https://lrc.red/";

const LRCRED_UA: &str = concat!("nuvx/", env!("CARGO_PKG_VERSION"), " (+https://github.com/bxanedot/nuvx)");

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Provider {
    BLyricsRich,
    UnisonRich,
    BinimumRich,
    UnisonWord,
    PortatoRich,
    MusixmatchRich,
    LrcredRich,
    BLyricsSynced,
    UnisonSynced,
    BinimumSynced,
    LrclibSynced,
    LegatoSynced,
    MusixmatchSynced,
    LrcredSynced,
    UnisonPlain,
    LrclibPlain,
}

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum SyncKind {
    Syllable,
    Word,
    Line,
    Unsynced,
}

impl Provider {
    pub const ALL: [Provider; 16] = [
        Provider::LrcredRich,
        Provider::LrcredSynced,
        Provider::BinimumRich,
        Provider::BinimumSynced,
        Provider::BLyricsRich,
        Provider::BLyricsSynced,
        Provider::PortatoRich,
        Provider::LegatoSynced,
        Provider::MusixmatchRich,
        Provider::MusixmatchSynced,
        Provider::UnisonRich,
        Provider::UnisonWord,
        Provider::UnisonSynced,
        Provider::UnisonPlain,
        Provider::LrclibSynced,
        Provider::LrclibPlain,
    ];

    pub fn key(self) -> &'static str {
        match self {
            Provider::BLyricsRich => "bLyrics-richsynced",
            Provider::UnisonRich => "unison-richsynced",
            Provider::BinimumRich => "binimum-richsynced",
            Provider::UnisonWord => "unison-wordsynced",
            Provider::PortatoRich => "portato-richsynced",
            Provider::MusixmatchRich => "musixmatch-richsync",
            Provider::LrcredRich => "lrcred-richsynced",
            Provider::BLyricsSynced => "bLyrics-synced",
            Provider::UnisonSynced => "unison-synced",
            Provider::BinimumSynced => "binimum-synced",
            Provider::LrclibSynced => "lrclib-synced",
            Provider::LegatoSynced => "legato-synced",
            Provider::MusixmatchSynced => "musixmatch-synced",
            Provider::LrcredSynced => "lrcred-synced",
            Provider::UnisonPlain => "unison-plain",
            Provider::LrclibPlain => "lrclib-plain",
        }
    }

    pub fn from_key(key: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|p| p.key() == key)
    }

    pub fn name(self) -> &'static str {
        match self {
            Provider::BLyricsRich | Provider::BLyricsSynced => "Better Lyrics",
            Provider::UnisonRich | Provider::UnisonWord | Provider::UnisonSynced | Provider::UnisonPlain => "Unison",
            Provider::BinimumRich | Provider::BinimumSynced => "BiniLyrics",
            Provider::PortatoRich => "Better Lyrics Portato",
            Provider::MusixmatchRich | Provider::MusixmatchSynced => "Musixmatch",
            Provider::LrclibSynced | Provider::LrclibPlain => "LRCLib",
            Provider::LrcredRich | Provider::LrcredSynced => "lrc.red",
            Provider::LegatoSynced => "Better Lyrics Legato",
        }
    }

    pub fn sync(self) -> SyncKind {
        match self {
            Provider::BLyricsRich | Provider::UnisonRich | Provider::BinimumRich => SyncKind::Syllable,
            Provider::UnisonWord | Provider::PortatoRich | Provider::MusixmatchRich | Provider::LrcredRich => SyncKind::Word,
            Provider::BLyricsSynced
            | Provider::UnisonSynced
            | Provider::BinimumSynced
            | Provider::LrclibSynced
            | Provider::LegatoSynced
            | Provider::MusixmatchSynced
            | Provider::LrcredSynced => SyncKind::Line,
            Provider::UnisonPlain | Provider::LrclibPlain => SyncKind::Unsynced,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum Sync {
    None,
    Line,
    Word,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Part {
    pub start: i32,
    pub dur: i32,
    pub text: String,
    pub bg: bool,
}

#[derive(Clone, Debug, Default)]
pub struct Line {
    pub start: i32,
    pub dur: i32,
    pub text: String,
    pub parts: Vec<Part>,
    pub agent: Option<String>,
    pub instrumental: bool,
    pub translations: Vec<(String, String)>,
    pub romanization: Option<String>,
    pub timed_roman: Vec<Part>,
}

impl Line {
    pub fn end(&self) -> i32 {
        self.start + self.dur
    }
}

#[derive(Clone, Debug)]
pub struct Lyrics {
    pub lines: Vec<Line>,
    pub sync: Sync,
    pub provider: Provider,
    pub language: Option<String>,
    pub songwriters: Vec<String>,
}

impl Lyrics {
    fn new(provider: Provider, lines: Vec<Line>, language: Option<String>, songwriters: Vec<String>) -> Option<Self> {
        if lines.is_empty() {
            return None;
        }
        let sync = derive_sync(&lines);
        Some(Self { lines, sync, provider, language, songwriters })
    }
}

fn derive_sync(lines: &[Line]) -> Sync {
    if lines.iter().any(|l| !l.instrumental && l.parts.iter().any(|p| p.dur != 0)) {
        Sync::Word
    } else if lines.iter().all(|l| l.start == 0) {
        Sync::None
    } else {
        Sync::Line
    }
}

#[derive(Clone, Debug, Default)]
pub struct LyricsSet {
    pub sources: Vec<Lyrics>,
}

impl LyricsSet {
    pub fn get(&self, p: Provider) -> Option<&Lyrics> {
        self.sources.iter().find(|l| l.provider == p)
    }
}

pub struct Client {
    http: reqwest::Client,
    jwt_file: PathBuf,
    webview_profile: PathBuf,
    jwt: tokio::sync::Mutex<Option<String>>,
    challenge_failed: std::sync::Mutex<Option<std::time::Instant>>,
    recent: std::sync::Mutex<std::collections::VecDeque<(String, LyricsSet)>>,
}

const CHALLENGE_RETRY: Duration = Duration::from_secs(15 * 60);
const RECENT: usize = 8;

pub struct Query<'a> {
    pub video_id: &'a str,
    pub song: &'a str,
    pub artist: &'a str,
    pub album: Option<&'a str>,
    pub duration: Option<u32>,
}

impl Client {
    pub fn new(http: reqwest::Client, data_dir: &Path) -> Self {
        let jwt_file = data_dir.join("lyrics_jwt");
        let cached = std::fs::read_to_string(&jwt_file).ok().filter(|t| jwt_seconds_left(t) > 3600);
        Self {
            http,
            jwt_file,
            webview_profile: data_dir.join("webview-lyrics"),
            jwt: tokio::sync::Mutex::new(cached),
            challenge_failed: Default::default(),
            recent: Default::default(),
        }
    }

    pub async fn fetch(&self, q: &Query<'_>) -> anyhow::Result<LyricsSet> {
        if let Some((_, l)) = self.recent.lock().unwrap().iter().find(|(id, _)| id == q.video_id) {
            return Ok(l.clone());
        }
        let (stream, unison, lrcred) = tokio::join!(self.fetch_stream(q), self.fetch_unison(q), self.fetch_lrcred(q));
        let mut sources = Vec::new();
        match stream {
            Ok(s) => sources.extend(s),
            Err(e) => log::warn!("better lyrics failed: {e:#}"),
        }
        match unison {
            Ok(s) => sources.extend(s),
            Err(e) => log::info!("unison: {e:#}"),
        }
        match lrcred {
            Ok(s) => sources.extend(s),
            Err(e) => log::info!("lrc.red: {e:#}"),
        }
        if sources.is_empty() {
            return Ok(LyricsSet::default());
        }
        sources.sort_by_key(|l| Provider::ALL.iter().position(|p| *p == l.provider));
        let set = LyricsSet { sources };
        let mut recent = self.recent.lock().unwrap();
        if recent.len() == RECENT {
            recent.pop_front();
        }
        recent.push_back((q.video_id.to_owned(), set.clone()));
        Ok(set)
    }

    async fn fetch_stream(&self, q: &Query<'_>) -> anyhow::Result<Vec<Lyrics>> {
        let mut jwt = self.token(false).await?;
        for attempt in 0..2 {
            let mut form = vec![("videoId", q.video_id.to_owned())];
            if !q.song.is_empty() {
                form.push(("song", q.song.to_owned()));
            }
            if !q.artist.is_empty() {
                form.push(("artist", q.artist.to_owned()));
            }
            if let Some(d) = q.duration.filter(|d| *d > 0) {
                form.push(("duration", d.to_string()));
            }
            if let Some(a) = q.album {
                form.push(("album", a.to_owned()));
            }
            form.push(("alwaysFetchMetadata", "false".to_owned()));
            form.push(("token", jwt.clone()));
            let resp = self.http.post(format!("{API}v2/lyrics")).form(&form).timeout(Duration::from_secs(20)).send().await?;
            if resp.status() == reqwest::StatusCode::FORBIDDEN && attempt == 0 {
                jwt = self.token(true).await?;
                continue;
            }
            let body = resp.error_for_status()?.text().await?;
            return Ok(from_stream(&body, q));
        }
        unreachable!()
    }

    async fn fetch_unison(&self, q: &Query<'_>) -> anyhow::Result<Vec<Lyrics>> {
        let mut params = vec![("v", q.video_id.to_owned()), ("song", q.song.to_owned()), ("artist", q.artist.to_owned())];
        params.push(("duration", q.duration.unwrap_or(0).to_string()));
        if let Some(a) = q.album {
            params.push(("album", a.to_owned()));
        }
        let resp = self.http.get(UNISON_API).query(&params).timeout(Duration::from_secs(10)).send().await?;
        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(vec![]);
        }
        let v: Value = resp.error_for_status()?.json().await?;
        Ok(from_unison(&v["data"], q.duration.unwrap_or(0) as i32 * 1000))
    }

    async fn fetch_lrcred(&self, q: &Query<'_>) -> anyhow::Result<Vec<Lyrics>> {
        if q.song.trim().is_empty() {
            return Ok(vec![]);
        }
        let query = match q.artist.trim().is_empty() {
            true => q.song.to_owned(),
            false => format!("{} {}", q.song, q.artist),
        };
        let hits: Value = self
            .http
            .get(format!("{LRCRED_API}search.json"))
            .query(&[("q", query)])
            .header(reqwest::header::USER_AGENT, LRCRED_UA)
            .timeout(Duration::from_secs(8))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let Some(hit) = best_lrcred_hit(hits["hits"].as_array().map(Vec::as_slice).unwrap_or_default(), q) else {
            return Ok(vec![]);
        };
        let Some(isrc) = hit["isrc"].as_str().filter(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_alphanumeric())) else {
            return Ok(vec![]);
        };
        let text = self
            .http
            .get(format!("{LRCRED_API}s/{isrc}.ttml"))
            .header(reqwest::header::USER_AGENT, LRCRED_UA)
            .timeout(Duration::from_secs(8))
            .send()
            .await?
            .error_for_status()?
            .text()
            .await?;
        let t = parse_ttml(&text, q.duration.unwrap_or(0) as i32 * 1000);
        let word = if text.contains("lrc:timing=\"Line\"") {
            false
        } else if text.contains("lrc:timing=\"Word\"") || text.contains("lrc:timing=\"Syllable\"") {
            true
        } else {
            t.word_synced
        };
        let p = if word { Provider::LrcredRich } else { Provider::LrcredSynced };
        Ok(Lyrics::new(p, t.lines, t.language, t.songwriters).into_iter().collect())
    }

    async fn token(&self, force_new: bool) -> anyhow::Result<String> {
        let mut slot = self.jwt.lock().await;
        if let Some(t) = slot.as_ref().filter(|t| !force_new && jwt_seconds_left(t) > 60) {
            return Ok(t.clone());
        }
        if self.challenge_failed.lock().unwrap().is_some_and(|at| at.elapsed() < CHALLENGE_RETRY) {
            anyhow::bail!("lyrics verification failed recently; retrying later");
        }
        let profile = self.webview_profile.clone();
        let turnstile = match tokio::task::spawn_blocking(move || crate::turnstile::obtain(profile)).await? {
            Ok(t) => t,
            Err(e) => {
                *self.challenge_failed.lock().unwrap() = Some(std::time::Instant::now());
                return Err(e);
            }
        };
        let resp: Value = self
            .http
            .post(format!("{API}verify-turnstile"))
            .json(&serde_json::json!({ "token": turnstile }))
            .timeout(Duration::from_secs(15))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let jwt = resp["jwt"].as_str().ok_or_else(|| anyhow::anyhow!("no JWT in response"))?.to_owned();
        let _ = std::fs::write(&self.jwt_file, &jwt);
        log::info!("lyrics: new access token ({}h)", jwt_seconds_left(&jwt) / 3600);
        *slot = Some(jwt.clone());
        Ok(jwt)
    }
}

fn jwt_seconds_left(jwt: &str) -> i64 {
    use base64::Engine;
    let Some(payload) = jwt.split('.').nth(1) else { return 0 };
    let Ok(bytes) = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(payload.trim_end_matches('=')) else {
        return 0;
    };
    let exp = serde_json::from_slice::<Value>(&bytes).ok().and_then(|v| v["exp"].as_i64()).unwrap_or(0);
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64);
    exp - now
}

pub fn from_stream(body: &str, q: &Query) -> Vec<Lyrics> {
    let mut out: Vec<Lyrics> = Vec::new();
    let mut dur_ms = q.duration.unwrap_or(0) as i32 * 1000;
    let (mut title, mut artist) = (q.song.to_owned(), q.artist.to_owned());
    for block in body.split("\n\n").flat_map(|b| b.split("\r\n\r\n")) {
        let mut event = "";
        let mut data = String::new();
        for line in block.lines() {
            if let Some(e) = line.strip_prefix("event:") {
                event = e.trim();
            } else if let Some(d) = line.strip_prefix("data:") {
                data.push_str(d.trim());
            }
        }
        if data.is_empty() || data == "[DONE]" {
            continue;
        }
        let Ok(v) = serde_json::from_str::<Value>(&data) else { continue };
        if event == "metadata" {
            if let Some(d) = v["duration"].as_str().and_then(|d| d.parse::<f64>().ok()).or_else(|| v["duration"].as_f64()) {
                dur_ms = (d * 1000.0) as i32;
            }
            if let Some(s) = v["song"].as_str() {
                title = s.to_owned();
            }
            if let Some(a) = v["artist"].as_str() {
                artist = a.to_owned();
            }
            continue;
        }
        let r = &v["results"];
        let s = |k: &str| r[k].as_str().filter(|s| !s.is_empty());
        let mut push = |p: Provider, lines: Vec<Line>, lang: Option<String>, writers: Vec<String>| {
            if let Some(l) = Lyrics::new(p, lines, lang, writers) {
                out.retain(|o| o.provider != p);
                out.push(l);
            }
        };
        match v["provider"].as_str().unwrap_or_default() {
            "musixmatch" => {
                if let Some(t) = s("wordByWord") {
                    let mut lines = parse_lrc(t, dur_ms);
                    lrc_fixers(&mut lines);
                    push(Provider::MusixmatchRich, lines, None, lrc_songwriters(t));
                }
                if let Some(t) = s("synced") {
                    push(Provider::MusixmatchSynced, parse_lrc(t, dur_ms), None, lrc_songwriters(t));
                }
            }
            "lrclib" => {
                if let Some(t) = s("synced") {
                    push(Provider::LrclibSynced, parse_lrc(t, dur_ms), None, lrc_songwriters(t));
                }
                if let Some(t) = s("plain") {
                    push(Provider::LrclibPlain, parse_plain(t), None, vec![]);
                }
            }
            "kugou" => {
                if let Some(t) = s("lyrics").and_then(|t| serde_json::from_str::<Value>(t).ok()).and_then(|j| j["lyrics"].as_str().map(str::to_owned)) {
                    push(Provider::LegatoSynced, parse_lrc(&t, dur_ms), None, lrc_songwriters(&t));
                }
            }
            "qq" => {
                if let Some(t) = s("lyrics").and_then(|t| serde_json::from_str::<Value>(t).ok()).and_then(|j| j["lyrics"].as_str().map(str::to_owned)) {
                    push(Provider::PortatoRich, parse_qrc(&t, dur_ms, Some((&title, &artist))), None, qrc_songwriters(&t));
                }
            }
            "golyrics" => {
                if let Some(raw) = s("lyrics") {
                    let ttml = serde_json::from_str::<Value>(raw).ok().and_then(|j| j["ttml"].as_str().map(str::to_owned)).unwrap_or_else(|| raw.to_owned());
                    let t = parse_ttml(&ttml, dur_ms);
                    let p = if t.word_synced { Provider::BLyricsRich } else { Provider::BLyricsSynced };
                    push(p, t.lines, t.language, t.songwriters);
                }
            }
            "binimum" => {
                if let Some(raw) = s("lyrics") {
                    let t = parse_ttml(raw, dur_ms);
                    let word = match r["timingType"].as_str() {
                        Some("syllable") => true,
                        Some("line") => false,
                        _ => t.word_synced,
                    };
                    let p = if word { Provider::BinimumRich } else { Provider::BinimumSynced };
                    push(p, t.lines, t.language, t.songwriters);
                }
            }
            _ => {}
        }
    }
    out
}

fn from_unison(data: &Value, dur_ms: i32) -> Vec<Lyrics> {
    let (Some(format), Some(text)) = (data["format"].as_str(), data["lyrics"].as_str()) else { return vec![] };
    let lyrics = match format {
        "ttml" => {
            let t = parse_ttml(text, dur_ms);
            let p = if t.word_synced { Provider::UnisonRich } else { Provider::UnisonSynced };
            Lyrics::new(p, t.lines, t.language, t.songwriters)
        }
        "lrc" => {
            let p = if data["syncType"] == "richsync" { Provider::UnisonWord } else { Provider::UnisonSynced };
            Lyrics::new(p, parse_lrc(text, dur_ms), data["language"].as_str().map(str::to_owned), lrc_songwriters(text))
        }
        "plain" => Lyrics::new(Provider::UnisonPlain, parse_plain(text), None, vec![]),
        _ => None,
    };
    lyrics.into_iter().collect()
}

fn best_lrcred_hit<'a>(hits: &'a [Value], q: &Query<'_>) -> Option<&'a Value> {
    let title = fold_title(q.song);
    let artist = fold(q.artist);
    let dur = q.duration.filter(|d| *d > 0).map(f64::from);
    let mut best: Option<(i32, &Value)> = None;
    for h in hits {
        let ht = fold_title(h["title"].as_str().unwrap_or_default());
        let ha = fold(h["artist"].as_str().unwrap_or_default());
        let title_ok = !title.is_empty() && (ht == title || ht.contains(&title) || title.contains(&ht));
        let artist_ok = artist.is_empty() || ha == artist || ha.contains(&artist) || artist.contains(&ha);
        if !title_ok || !artist_ok {
            continue;
        }
        let diff = match (dur, h["duration"].as_f64()) {
            (Some(a), Some(b)) => (a - b).abs(),
            _ => 0.0,
        };
        if diff > 20.0 {
            continue;
        }
        let base = if ht == title { 100 } else { 60 };
        let score = base + (20.0 - diff) as i32;
        if best.is_none_or(|(s, _)| score > s) {
            best = Some((score, h));
        }
    }
    best.map(|(_, h)| h)
}

fn fold(s: &str) -> String {
    s.chars().map(|c| if c.is_alphanumeric() { c.to_ascii_lowercase() } else { ' ' }).collect::<String>().split_whitespace().collect::<Vec<_>>().join(" ")
}

fn fold_title(s: &str) -> String {
    let s = s.split(" (").next().unwrap_or(s);
    let s = s.split(" [").next().unwrap_or(s);
    let s = s.split(" - ").next().unwrap_or(s);
    fold(s)
}

const INSTRUMENTAL_GAP_MS: i32 = 5000;

fn insert_instrumentals(lines: Vec<Line>, song_ms: i32) -> Vec<Line> {
    if lines.is_empty() {
        return lines;
    }
    let gap = |start: i32, dur: i32| Line { start, dur, instrumental: true, ..Default::default() };
    let mut out = Vec::with_capacity(lines.len() + 4);
    if lines[0].start > INSTRUMENTAL_GAP_MS {
        out.push(gap(0, lines[0].start));
    }
    let n = lines.len();
    let last_end = lines[n - 1].end();
    for (i, l) in lines.iter().enumerate() {
        out.push(l.clone());
        if i + 1 < n {
            let end = l.end();
            let f = lines[i + 1].start - end;
            if f > INSTRUMENTAL_GAP_MS {
                out.push(gap(end, f));
            }
        }
    }
    if song_ms - last_end > INSTRUMENTAL_GAP_MS {
        out.push(gap(last_end, song_ms - last_end));
    }
    out
}

const WRITER_TAGS: &[&str] = &["词", "詞", "作词", "作詞", "曲", "作曲", "词曲", "詞曲", "作词作曲", "作詞作曲", "writtenby", "lyricsby", "composedby", "lyricist", "composer"];
const CREDIT_TAGS: &[&str] = &[
    "编曲", "編曲", "和声", "和聲", "混音", "吉他", "制作人", "製作人", "演唱", "原唱", "翻唱", "后期", "後期", "和音", "录音", "錄音", "策划", "策劃", "伴奏", "美工", "海报", "海報", "旁白", "producedby",
    "arrangedby", "mixing", "mastering", "vocal", "vocals", "guitar", "bass", "drums", "producer", "arranger",
];
const CREDIT_SUFFIXES: &[char] = &['词', '詞', '曲', '声', '聲', '音'];

fn norm_tag(t: &str) -> String {
    t.to_lowercase().split_whitespace().collect()
}

fn is_credit_tag(t: &str) -> bool {
    WRITER_TAGS.contains(&t) || CREDIT_TAGS.contains(&t) || (t.chars().count() <= 4 && t.ends_with(CREDIT_SUFFIXES))
}

fn tag_pieces(n: &str) -> Vec<String> {
    let no_latin: String = n.chars().filter(|c| !c.is_ascii_lowercase()).collect();
    no_latin.split(['/', '&', '、', ',', '，', '・', '·']).filter(|s| !s.is_empty()).map(str::to_owned).collect()
}

fn is_credit_label(t: &str) -> bool {
    let n = norm_tag(t);
    if WRITER_TAGS.contains(&n.as_str()) || CREDIT_TAGS.contains(&n.as_str()) {
        return true;
    }
    let pieces = tag_pieces(&n);
    !pieces.is_empty() && pieces.iter().all(|p| is_credit_tag(p))
}

fn is_writer_label(t: &str) -> bool {
    let n = norm_tag(t);
    WRITER_TAGS.contains(&n.as_str()) || (is_credit_label(t) && tag_pieces(&n).iter().any(|p| WRITER_TAGS.contains(&p.as_str())))
}

fn split_label(t: &str) -> Option<(&str, &str)> {
    let i = t.find([':', '：'])?;
    let sep = t[i..].chars().next()?.len_utf8();
    let (name, rest) = (&t[..i], t[i + sep..].trim_start());
    (!name.is_empty() && !rest.is_empty()).then_some((name, rest))
}

fn is_credit_line(t: &str) -> bool {
    split_label(t.trim()).is_some_and(|(name, _)| is_credit_label(name))
}

fn split_names(t: &str) -> Vec<String> {
    t.split(['/', '、', '，', ',']).map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned).collect()
}

fn writers_on(t: &str) -> Vec<String> {
    match split_label(t.trim()) {
        Some((name, rest)) if is_writer_label(name) => split_names(rest),
        _ => vec![],
    }
}

fn dedup(names: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for n in names {
        let n = n.trim().to_owned();
        if !n.is_empty() && !out.contains(&n) {
            out.push(n);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enhanced_lrc_parts() {
        let l = parse_lrc("[00:31.69] <00:31.69> Like <00:35.63>   <00:35.64> the <00:35.69>\n[00:40.00] Next", 60_000);
        assert_eq!(l[0].text, "Like the");
        assert_eq!(l[0].parts[0].start, 31690);
        assert_eq!(l[0].parts[0].text, "Like");
        assert_eq!(l[0].parts[1].text, " ");
        assert_eq!(l[0].parts[0].dur, 3940);
        assert_eq!(l[1].start, 40000);
        assert_eq!(l[1].dur, 20000);
    }

    #[test]
    fn lrc_skips_credits_and_reads_offset() {
        let l = parse_lrc("[offset:+0.5]\n[00:01.00]作词 : 野田洋次郎\n[00:05.00]Hello\n[00:07.00]World", 10_000);
        assert_eq!(l.len(), 2);
        assert_eq!(l[0].start, 4500);
        assert_eq!(lrc_songwriters("[00:01.00]作词 : 野田洋次郎\n"), vec!["野田洋次郎"]);
    }

    #[test]
    fn lrc_fixers_move_space_time_to_word() {
        let mut l = parse_lrc("[00:01.00]<00:01.00>a<00:01.50> <00:02.00>b<00:02.50>\n[00:03.00]x", 5000);
        lrc_fixers(&mut l);
        assert_eq!(l[0].parts[0].dur, 1000);
    }

    #[test]
    fn qrc_drops_title_and_reads_syllables() {
        let xml = "<Lyric_1 LyricContent=\"[ti:x]\n[0,6350]Song - Artist(0,198)\n[1000,2000]Get(1000,500) (1500,10)Lucky(1510,490)\n\"/>";
        let l = parse_qrc(xml, 3000, Some(("Song", "Artist")));
        let sung: Vec<&Line> = l.iter().filter(|l| !l.instrumental).collect();
        assert_eq!(sung.len(), 1);
        assert_eq!(sung[0].text, "Get Lucky");
        assert_eq!(sung[0].parts[2].start, 1510);
    }

    #[test]
    fn ttml_parts_background_agents_and_extras() {
        let xml = r#"<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="ja"><head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><translations><translation xml:lang="en"><text for="L1">Hello world</text></translation></translations><songwriters><songwriter>野田洋次郎</songwriter></songwriters><transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="1.0" end="2.0">kon</span> <span begin="2.0" end="3.0">nichiwa</span></text></transliteration></transliterations></iTunesMetadata></metadata></head><body dur="20.0"><div>
<p begin="1.0" end="3.0" itunes:key="L1" ttm:agent="v1"><span begin="1.0" end="1.5">こん</span><span begin="1.5" end="2.0">に</span> <span begin="2.0" end="3.0">ちは</span><span ttm:role="x-bg"><span begin="2.5" end="3.5">(yeah)</span></span></p>
<p begin="10.0" end="11.0" itunes:key="L2" ttm:agent="v2">Line two</p></div></body></tt>"#;
        let t = parse_ttml(xml, 0);
        assert!(t.word_synced);
        assert_eq!(t.language.as_deref(), Some("ja"));
        assert_eq!(t.songwriters, vec!["野田洋次郎"]);
        let l = &t.lines[0];
        assert_eq!(l.text, "こんに ちは(yeah)");
        assert_eq!(l.parts.len(), 5);
        assert_eq!(l.parts[2], Part { start: 2000, dur: 0, text: " ".into(), bg: false });
        assert!(l.parts[4].bg && l.parts[4].start == 2500);
        assert_eq!(l.agent.as_deref(), Some("v1"));
        assert_eq!(l.translations, vec![("en".to_owned(), "Hello world".to_owned())]);
        assert_eq!(l.romanization.as_deref(), Some("kon nichiwa"));
        assert_eq!(l.timed_roman.len(), 3);
        assert!(t.lines[1].instrumental && t.lines[1].start == 3000);
        assert_eq!(t.lines[2].agent.as_deref(), Some("v2"));
        assert!(t.lines[2].parts.is_empty());
        assert!(t.lines.last().unwrap().instrumental);
    }

    #[test]
    fn ttml_times() {
        assert_eq!(ttml_time("1:02.345"), 62345);
        assert_eq!(ttml_time("12.5s"), 12500);
        assert_eq!(ttml_time("250ms"), 250);
        assert_eq!(ttml_time("01:00:01.000"), 3_601_000);
    }

    #[test]
    fn real_stream_parses_every_provider() {
        let Ok(body) = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/research/nandemonaiya.sse")) else { return };
        let q = Query { video_id: "n89SKAymNfA", song: "Nandemonaiya - movie ver.", artist: "RADWIMPS", album: None, duration: Some(343) };
        let got = from_stream(&body, &q);
        let keys: Vec<&str> = got.iter().map(|l| l.provider.key()).collect();
        assert!(keys.contains(&"bLyrics-richsynced"), "{keys:?}");
        let bl = got.iter().find(|l| l.provider == Provider::BLyricsRich).unwrap();
        assert_eq!(bl.sync, Sync::Word);
        assert!(bl.lines.iter().any(|l| l.timed_roman.len() > 3));
        assert_eq!(bl.songwriters, vec!["野田洋次郎"]);
    }
}
