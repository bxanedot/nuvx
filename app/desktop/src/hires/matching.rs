use std::collections::BTreeSet;

use serde_json::Value;

use super::json_is_atmos;

#[derive(Clone)]
pub(super) struct Candidate {
    pub(super) id: String,
    pub(super) title: String,
    pub(super) artist: String,
    pub(super) duration: Option<u32>,
    pub(super) quality: String,
    pub(super) format: String,
    pub(super) atmos: bool,
    pub(super) stream_url: Option<String>,
}

pub(super) fn candidate(v: &Value) -> Option<Candidate> {
    let id = v.get("id").and_then(Value::as_str).unwrap_or("").trim();
    let title = v.get("title").and_then(Value::as_str).unwrap_or("").trim();
    if id.is_empty() || title.is_empty() {
        return None;
    }
    Some(Candidate {
        id: id.to_owned(),
        title: title.to_owned(),
        artist: v.get("artist").and_then(Value::as_str).unwrap_or("").trim().to_owned(),
        duration: seconds(v.get("duration").unwrap_or(&Value::Null)),
        quality: v.get("audioQuality").and_then(Value::as_str).unwrap_or("").trim().to_owned(),
        format: v.get("format").and_then(Value::as_str).unwrap_or("").trim().to_owned(),
        atmos: json_is_atmos(v),
        stream_url: v.get("streamURL").and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned),
    })
}

fn seconds(v: &Value) -> Option<u32> {
    if let Some(n) = v.as_f64() {
        return (n > 0.0).then_some(n as u32);
    }
    let text = v.as_str()?.trim();
    if let Some((m, s)) = text.split_once(':') {
        let m: u32 = m.parse().ok()?;
        let s: f64 = s.parse().ok()?;
        return Some(m * 60 + s as u32);
    }
    text.parse::<f64>().ok().filter(|n| *n > 0.0).map(|n| n as u32)
}

pub(super) fn rank(mut candidates: Vec<Candidate>, title: &str, artist: &str, duration: Option<u32>, prefer_atmos: bool) -> Vec<Candidate> {
    let mut scored: Vec<(Candidate, i32)> = candidates.drain(..).filter_map(|c| score(&c, title, artist, duration, prefer_atmos).map(|s| (c, s))).collect();
    scored.sort_by(|a, b| b.1.cmp(&a.1));
    scored.into_iter().map(|(c, _)| c).collect()
}

fn score(candidate: &Candidate, title: &str, artist: &str, duration: Option<u32>, prefer_atmos: bool) -> Option<i32> {
    let wanted = title_parts(title, artist);
    let got = title_parts(&candidate.title, &candidate.artist);
    if wanted.0.is_empty() || wanted.0 != got.0 || wanted.1 != got.1 {
        return None;
    }
    let target_artists = artist_names(artist);
    let candidate_artists = artist_names(&candidate.artist);
    if !target_artists.is_empty()
        && (candidate_artists.is_empty() || !target_artists.iter().any(|w| candidate_artists.iter().any(|g| same_artist(w, g))))
    {
        return None;
    }
    if let (Some(wanted), Some(got)) = (duration, candidate.duration)
        && wanted.abs_diff(got) > 30
    {
        return None;
    }
    let artist_score = match (target_artists.is_empty() || candidate_artists.is_empty(), target_artists == candidate_artists) {
        (true, _) => 0,
        (false, true) => 30,
        (false, false) => 20,
    };
    let duration_score = match (duration, candidate.duration) {
        (Some(wanted), Some(got)) => (20 - wanted.abs_diff(got) as i32).max(0),
        _ => 0,
    };
    let atmos_score = if prefer_atmos && candidate.atmos { 8 } else { 0 };
    Some(100 + artist_score + duration_score + atmos_score)
}

pub(super) fn searchable_title(title: &str, artist: &str) -> String {
    let (words, versions) = title_parts(title, artist);
    let mut out = words;
    for version in versions {
        out.push(' ');
        out.push_str(&version);
    }
    out.trim().to_owned()
}

pub(super) fn primary_artist(artist: &str) -> String {
    artist.to_ascii_lowercase().split([',', '&', '/', ';', '·', '|']).next().unwrap_or("").trim().to_owned()
}

type TitleParts = (String, Vec<String>);

fn title_parts(raw: &str, artist: &str) -> TitleParts {
    let mut versions = BTreeSet::new();
    let mut text = raw.to_ascii_lowercase().replace('&', " and ");
    for _ in 0..3 {
        let Some((start, end)) = bracket_span(&text) else { break };
        classify(&text[start + 1..end], &mut versions);
        text.replace_range(start..=end, " ");
    }
    if let Some(at) = text.find(['(', '[']) {
        classify(&text[at..], &mut versions);
        text.truncate(at);
    }
    for _ in 0..3 {
        let Some(at) = text.find(" - ") else { break };
        let (head, tail) = (text[..at].to_owned(), text[at + 3..].to_owned());
        if is_artist_name(&head, artist) {
            classify(&head, &mut versions);
            text = tail;
        } else if is_artist_name(&tail, artist) {
            classify(&tail, &mut versions);
            text = head;
        } else {
            classify(&tail, &mut versions);
            text = head;
        }
    }
    for marker in [" feat.", " feat ", " ft.", " ft ", " featuring ", " with "] {
        if let Some(at) = text.find(marker) {
            text.truncate(at);
        }
    }
    let mut words: Vec<String> = text.split([' ', '.', '·']).map(clean).filter(|w| !w.is_empty() && w != "and").collect();
    while words.len() > 1 && matches!(words.last().map(String::as_str), Some("song" | "songs" | "video" | "audio" | "lyrics" | "official" | "full" | "hd" | "hq" | "4k" | "mp3" | "ost" | "soundtrack")) {
        words.pop();
    }
    (words.join(" "), versions.into_iter().collect())
}

fn bracket_span(text: &str) -> Option<(usize, usize)> {
    let open = text.find(['(', '['])?;
    let close = text[open..].find([')', ']'])? + open;
    Some((open, close))
}

fn classify(segment: &str, versions: &mut BTreeSet<String>) {
    let words: Vec<String> = segment.split([' ', '.', '·']).map(clean).filter(|w| !w.is_empty()).collect();
    if words.is_empty() {
        return;
    }
    let joined = words.join("");
    if matches!(joined.as_str(), "albumversion" | "originalversion" | "originalmix" | "singleversion" | "radioversion" | "radioedit" | "stereoversion" | "monoversion" | "studioversion" | "fullversion" | "standardversion" | "explicitversion" | "deluxeversion" | "originaltrack") {
        return;
    }
    for word in words {
        if VERSION_WORDS.contains(&word.as_str()) {
            versions.insert(word);
        }
    }
}

fn is_artist_name(text: &str, artist: &str) -> bool {
    if artist.is_empty() {
        return false;
    }
    let words: Vec<String> = text.split([' ', '.', '·']).map(clean).filter(|w| !w.is_empty()).collect();
    if words.is_empty() {
        return false;
    }
    let credited: Vec<String> = artist.to_ascii_lowercase().split([' ', '.', '·']).map(clean).filter(|w| !w.is_empty()).collect();
    words.iter().all(|w| credited.contains(w))
}

fn artist_names(value: &str) -> Vec<Vec<String>> {
    let normalized = value.to_ascii_lowercase().trim().to_owned();
    if normalized.is_empty() || matches!(normalized.as_str(), "unknown" | "unknown artist" | "unknown artist(s)") {
        return Vec::new();
    }
    normalized
        .split([',', '&', '/', ';', '·', '|'])
        .map(|name| {
            let mut parts: Vec<String> = name.split([' ', '.']).map(clean).filter(|w| w.len() > 1).collect();
            if parts.is_empty() {
                parts = vec![name.trim().to_owned()];
            }
            parts
        })
        .collect::<Vec<Vec<String>>>()
        .into_iter()
        .filter(|parts| parts.iter().any(|w| !w.is_empty()))
        .collect()
}

fn same_artist(first: &[String], second: &[String]) -> bool {
    if contains_words(first, second) || contains_words(second, first) {
        return true;
    }
    ordered_subset(first, second) || ordered_subset(second, first)
}

fn ordered_subset(shorter: &[String], longer: &[String]) -> bool {
    if shorter.is_empty() || shorter.len() >= longer.len() {
        return false;
    }
    let mut at = 0;
    for word in shorter {
        let Some(pos) = longer[at..].iter().position(|w| w == word) else { return false };
        at += pos + 1;
    }
    true
}

fn contains_words(outer: &[String], inner: &[String]) -> bool {
    !inner.is_empty() && inner.len() <= outer.len() && (0..=outer.len() - inner.len()).any(|start| outer[start..start + inner.len()] == *inner)
}

fn clean(word: &str) -> String {
    word.chars().filter(|c| c.is_ascii_alphanumeric()).collect()
}

const VERSION_WORDS: &[&str] = &[
    "remix", "remixes", "rmx", "refix", "flip", "bootleg", "mashup", "medley", "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke", "vocals", "vocal",
    "acapella", "acappella", "backing", "stems", "stem", "cover", "demo", "reprise", "remake", "rework", "extended", "edit", "version", "mix", "dub", "vip", "session",
    "sessions", "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "symphonic", "part", "pt", "chapter",
];
