use std::io::{self, Read, Seek, SeekFrom};
use std::sync::Arc;

use bytes::Bytes;
use serde::Deserialize;

use crate::model::{Link, Thumb, Track};
use crate::stream::Shared;

pub const PREFIX: &str = "radio:";

const HOSTS: [&str; 3] = ["all.api.radio-browser.info", "de1.api.radio-browser.info", "fi1.api.radio-browser.info"];
const AGENT: &str = concat!("nuvx/", env!("CARGO_PKG_VERSION"));

#[derive(Clone, Debug, Deserialize)]
pub struct Country {
    pub name: String,
    #[serde(rename = "iso_3166_1")]
    pub code: String,
    #[serde(rename = "stationcount", default)]
    pub stations: u32,
}

impl Country {
    pub fn flag(&self) -> String {
        let up = self.code.to_ascii_uppercase();
        if up.len() != 2 || !up.bytes().all(|c| c.is_ascii_uppercase()) {
            return String::new();
        }
        up.chars().filter_map(|c| char::from_u32(0x1F1E6 + (c as u32 - 'A' as u32))).collect()
    }
}

#[derive(Clone, Debug, Deserialize)]
pub struct Station {
    #[serde(rename = "stationuuid")]
    pub id: String,
    pub name: String,
    #[serde(rename = "url_resolved")]
    pub stream: String,
    #[serde(default)]
    pub favicon: String,
    #[serde(default)]
    pub country: String,
    #[serde(default)]
    pub tags: String,
    #[serde(default)]
    pub codec: String,
    #[serde(default)]
    pub bitrate: u32,
    #[serde(default)]
    pub hls: u8,
}

impl Station {
    pub fn subtitle(&self) -> String {
        let tags: Vec<String> = self.tags.split(',').map(str::trim).filter(|t| !t.is_empty()).take(2).map(capitalize).collect();
        let fmt = [self.codec.trim().to_owned(), if self.bitrate > 0 { format!("{} kbps", self.bitrate) } else { String::new() }]
            .into_iter()
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
            .join(" ");
        [tags.join(" · "), fmt].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" · ")
    }

    pub fn track(&self) -> Track {
        let subtitle = self.subtitle();
        Track {
            id: format!("{PREFIX}{}", self.stream),
            title: self.name.trim().to_owned(),
            artists: vec![Link { name: if self.country.trim().is_empty() { subtitle } else { self.country.trim().to_owned() }, id: None }],
            album: None,
            duration: None,
            thumbs: self.favicon.starts_with("http").then(|| Thumb { url: self.favicon.clone(), width: 0, height: 0 }).into_iter().collect(),
            track_nr: None,
            plays: None,
            music_video: false,
        }
    }

    fn playable(&self) -> bool {
        let codec = self.codec.to_ascii_uppercase();
        self.hls == 0 && self.stream.starts_with("http") && (codec.is_empty() || codec == "UNKNOWN" || codec.starts_with("MP3") || codec.starts_with("AAC"))
    }
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    c.next().map(|f| f.to_uppercase().chain(c).collect()).unwrap_or_default()
}

pub fn home_country() -> Option<String> {
    #[cfg(windows)]
    {
        use windows::Win32::System::Registry::{HKEY_CURRENT_USER, RRF_RT_REG_SZ, RegGetValueW};
        use windows::core::w;
        let mut buf = [0u16; 16];
        let mut size = (buf.len() * 2) as u32;
        let ok = unsafe { RegGetValueW(HKEY_CURRENT_USER, w!(r"Control Panel\International\Geo"), w!("Name"), RRF_RT_REG_SZ, None, Some(buf.as_mut_ptr().cast()), Some(&mut size)) };
        let code = String::from_utf16_lossy(&buf[..(size as usize / 2).saturating_sub(1)]);
        if ok.is_ok() && code.len() == 2 {
            return Some(code);
        }
    }
    let lang = std::env::var("LC_ALL").or_else(|_| std::env::var("LANG")).ok()?;
    let code = lang.split(['.', '@']).next()?.split('_').nth(1)?;
    (code.len() == 2).then(|| code.to_ascii_uppercase())
}

pub fn is_station(id: &str) -> bool {
    id.starts_with(PREFIX)
}

async fn get(http: &reqwest::Client, path: &str, query: &[(&str, &str)]) -> anyhow::Result<Bytes> {
    let mut last = None;
    for host in HOSTS {
        let res = async {
            let resp = http.get(format!("https://{host}/json/{path}")).query(query).header(reqwest::header::USER_AGENT, AGENT).send().await?;
            resp.error_for_status()?.bytes().await
        }
        .await;
        match res {
            Ok(b) => return Ok(b),
            Err(e) => last = Some(e),
        }
    }
    Err(last.map_or_else(|| anyhow::anyhow!("Radio Browser unreachable"), Into::into))
}

pub async fn countries(http: &reqwest::Client) -> anyhow::Result<Vec<Country>> {
    let b = get(http, "countries", &[("order", "stationcount"), ("reverse", "true"), ("hidebroken", "true")]).await?;
    let mut list: Vec<Country> = serde_json::from_slice(&b)?;
    list.retain(|c| c.stations > 0 && c.code.len() == 2);
    Ok(list)
}

pub async fn stations(http: &reqwest::Client, country: Option<&str>, query: Option<&str>) -> anyhow::Result<Vec<Station>> {
    let mut q = vec![("hidebroken", "true"), ("order", "clickcount"), ("reverse", "true")];
    let path = match (query, country) {
        (Some(name), _) => {
            q.extend([("limit", "100"), ("name", name)]);
            "stations/search".to_owned()
        }
        (None, Some(cc)) => {
            q.push(("limit", "150"));
            format!("stations/bycountrycodeexact/{}", cc.to_ascii_uppercase())
        }
        (None, None) => anyhow::bail!("no country or search"),
    };
    let list: Vec<Station> = serde_json::from_slice(&get(http, &path, &q).await?)?;
    Ok(dedup(list))
}

fn dedup(list: Vec<Station>) -> Vec<Station> {
    let mut seen = std::collections::HashSet::new();
    list.into_iter().filter(|s| s.playable() && seen.insert(s.stream.clone())).collect()
}

pub async fn count_click(http: &reqwest::Client, id: &str) {
    let _ = get(http, &format!("url/{id}"), &[]).await;
}

pub struct LiveReader {
    rx: crossbeam_channel::Receiver<Bytes>,
    chunk: Bytes,
    pos: u64,
}

impl Read for LiveReader {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        while self.chunk.is_empty() {
            match self.rx.recv() {
                Ok(c) => self.chunk = c,
                Err(_) => return Ok(0),
            }
        }
        let n = out.len().min(self.chunk.len());
        out[..n].copy_from_slice(&self.chunk.split_to(n));
        self.pos += n as u64;
        Ok(n)
    }
}

impl Seek for LiveReader {
    fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
        match to {
            SeekFrom::Current(0) => Ok(self.pos),
            _ => Err(io::Error::new(io::ErrorKind::Unsupported, "a live stream can't seek")),
        }
    }
}

const STALL: std::time::Duration = std::time::Duration::from_secs(20);

#[derive(Default)]
struct Adts {
    pending: Vec<u8>,
    relabel: Option<bool>,
}

impl Adts {
    fn fix(&mut self, chunk: Bytes) -> Bytes {
        if self.relabel == Some(false) {
            return chunk;
        }
        self.pending.extend_from_slice(&chunk);
        let mut at = 0;
        while self.pending.len() - at >= 7 {
            let h = &mut self.pending[at..];
            if h[0] != 0xFF || h[1] != 0xF9 {
                self.relabel = Some(false);
                at = self.pending.len();
                break;
            }
            self.relabel = Some(true);
            let len = (usize::from(h[3] & 0x03) << 11) | (usize::from(h[4]) << 3) | usize::from(h[5] >> 5);
            if len < 7 {
                self.relabel = Some(false);
                at = self.pending.len();
                break;
            }
            if h.len() < len {
                break;
            }
            h[1] = 0xF1;
            at += len;
        }
        Bytes::from(self.pending.drain(..at).collect::<Vec<u8>>())
    }
}

pub async fn open(http: reqwest::Client, url: &str, shared: Arc<Shared>) -> anyhow::Result<(LiveReader, &'static str)> {
    let resp = http.get(url).header(reqwest::header::USER_AGENT, AGENT).send().await?.error_for_status()?;
    let mime = resp.headers().get(reqwest::header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or_default().to_ascii_lowercase();
    let hint = if mime.contains("aac") || mime.contains("mp4") { "aac" } else { "mp3" };
    let (tx, rx) = crossbeam_channel::unbounded();
    tokio::spawn(async move {
        let mut resp = resp;
        let mut adts = (hint == "aac").then(Adts::default);
        loop {
            let next = tokio::time::timeout(STALL, resp.chunk()).await.unwrap_or_else(|_| Ok(None));
            match next {
                Ok(Some(c)) => {
                    let c = match &mut adts {
                        Some(a) => a.fix(c),
                        None => c,
                    };
                    if !c.is_empty() && tx.send(c).is_err() {
                        return;
                    }
                }
                Ok(None) => break shared.fail("the station stopped sending".into()),
                Err(e) => break shared.fail(e.to_string()),
            }
        }
    });
    Ok((LiveReader { rx, chunk: Bytes::new(), pos: 0 }, hint))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn station(codec: &str, hls: u8, stream: &str) -> Station {
        Station { id: "1".into(), name: " Jazz FM ".into(), stream: stream.into(), favicon: String::new(), country: String::new(), tags: "jazz, smooth jazz,soul".into(), codec: codec.into(), bitrate: 128, hls }
    }

    #[test]
    fn stations_become_tracks_and_unplayable_ones_drop() {
        let s = station("MP3", 0, "http://a/live");
        assert_eq!(s.subtitle(), "Jazz · Smooth jazz · MP3 128 kbps");
        let t = s.track();
        assert_eq!((t.id.as_str(), t.title.as_str()), ("radio:http://a/live", "Jazz FM"));
        assert!(is_station(&t.id));
        let kept = dedup(vec![s.clone(), station("MP3", 0, "http://a/live"), station("OGG", 0, "http://b"), station("AAC+", 1, "http://c"), station("AAC+", 0, "http://d")]);
        assert_eq!(kept.iter().map(|s| s.stream.as_str()).collect::<Vec<_>>(), ["http://a/live", "http://d"]);
        assert_eq!(Country { name: "Portugal".into(), code: "pt".into(), stations: 1 }.flag(), "🇵🇹");
    }

    #[test]
    fn live_reader_reads_chunks_then_ends() {
        let (tx, rx) = crossbeam_channel::unbounded();
        tx.send(Bytes::from_static(b"abc")).unwrap();
        tx.send(Bytes::from_static(b"de")).unwrap();
        drop(tx);
        let mut r = LiveReader { rx, chunk: Bytes::new(), pos: 0 };
        let mut all = Vec::new();
        r.read_to_end(&mut all).unwrap();
        assert_eq!(all, b"abcde");
        assert_eq!(r.seek(SeekFrom::Current(0)).unwrap(), 5);
        assert!(r.seek(SeekFrom::Start(0)).is_err());
    }

    #[test]
    fn mpeg2_adts_is_relabelled_across_chunks_and_others_pass() {
        let frame = [0xFF, 0xF9, 0x4C, 0x80, 0x01, 0x20, 0x00, 0xF9, 0xAA];
        let stream: Vec<u8> = frame.iter().chain(&frame).copied().collect();
        let mut a = Adts::default();
        let mut out = a.fix(Bytes::copy_from_slice(&stream[..5])).to_vec();
        out.extend(a.fix(Bytes::copy_from_slice(&stream[5..])));
        let mut want = stream.clone();
        want[1] = 0xF1;
        want[10] = 0xF1;
        assert_eq!(out, want, "headers relabelled, payload untouched");
        let mp4 = Bytes::from_static(&[0xFF, 0xF1, 0x4C, 0x80, 0x01, 0x20, 0x00, 0xF9, 0xAA]);
        assert_eq!(Adts::default().fix(mp4.clone()), mp4);
    }

    #[tokio::test]
    #[ignore]
    async fn radio_browser_live() {
        let http = reqwest::Client::new();
        let c = countries(&http).await.unwrap();
        assert!(c.len() > 50);
        let s = stations(&http, Some("PT"), None).await.unwrap();
        println!("{} PT stations; first {:?}", s.len(), s.first().map(|s| (&s.name, &s.codec, &s.stream)));
        assert!(!s.is_empty());
        assert!(!stations(&http, None, Some("jazz")).await.unwrap().is_empty());
        for codec in ["MP3", "AAC", "AAC+"] {
            let s = s.iter().find(|s| s.codec.eq_ignore_ascii_case(codec)).expect(codec);
            let shared = Shared::new(0, 0, None, crate::stream::Format::File { hint: "mp3", mime: None }, None, false);
            let (reader, hint) = open(http.clone(), &s.stream, shared).await.unwrap();
            let n = tokio::task::spawn_blocking(move || {
                let d = rodio::Decoder::builder().with_data(reader).with_seekable(false).with_hint(hint).build().unwrap();
                let rate = rodio::Source::sample_rate(&d).get() as usize * rodio::Source::channels(&d).get() as usize;
                d.take(rate).count() == rate
            })
            .await
            .unwrap();
            println!("{codec} {} ({hint}): decoded a second: {n}", s.name);
            assert!(n);
        }
    }
}

