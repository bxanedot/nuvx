use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::Value;

use super::lrc::{lrc_fixers, lrc_songwriters, parse_lrc, parse_plain};
use super::qrc::{parse_qrc, qrc_songwriters};
use super::ttml::parse_ttml;
use super::{Line, Lyrics, LyricsSet, Provider};

const API: &str = "https://lyrics.api.dacubeking.com/";
const UNISON_API: &str = "https://unison.betterlyrics.org/lyrics";
const LRCRED_API: &str = "https://lrc.red/";

const LRCRED_UA: &str = concat!("nuvx/", env!("CARGO_PKG_VERSION"), " (+https://github.com/bxanedot/nuvx)");

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
