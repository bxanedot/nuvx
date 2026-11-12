use crate::model::{Link, Track};
use crate::ytmapi::Api;
use super::{Filter, list_item, search_as, search_songs};
pub(super) fn same_song(video: &Track, hit: &Track) -> bool {
    let fits = match (video.duration, hit.duration) {
        (Some(v), Some(s)) => s + 30.max(v / 5) >= v && s <= v * 2,
        _ => false,
    };
    named_alike(video, hit) && fits
}

pub async fn cover_song(api: &Api, video: &Track) -> anyhow::Result<Option<Track>> {
    let title = without_artist(&song_title(&video.title), video);
    let Some(a) = video.artists.first() else { return Ok(None) };
    let hits = search_songs(api, &format!("{title} {}", a.name), false).await?;
    Ok(hits.into_iter().take(5).find(|h| named_alike(video, h)))
}

pub async fn video_of(api: &Api, song: &Track) -> anyhow::Result<Option<Track>> {
    let Some(a) = song.artists.first() else { return Ok(None) };
    let hits = search_as(api, &format!("{} {}", song.title, a.name), Filter::Videos, false).await?;
    Ok(hits.iter().filter_map(|i| list_item(&i["musicResponsiveListItemRenderer"])).take(5).find(|v| v.music_video && named_alike(v, song)))
}

pub(super) fn named_alike(video: &Track, hit: &Track) -> bool {
    let title = without_artist(&song_title(&video.title), video);
    let base = base_title(&title);
    !hit.music_video
        && !base.is_empty()
        && base == base_title(&hit.title)
        && variants(&video.title) == variants(&hit.title)
        && qualifiers_agree(&title, &hit.title)
        && !video.artists.is_empty()
        && video.artists.iter().all(|a| hit.artists.iter().any(|b| same_artist(a, b)))
}

fn qualifiers_agree(a: &str, b: &str) -> bool {
    let harmless = |q: &String| {
        let w: Vec<&str> = q.split(' ').collect();
        matches!(w.first(), Some(&("feat" | "ft" | "featuring" | "with" | "from")))
            || w.iter().any(|w| w.starts_with("remaster"))
            || w.iter().all(|w| w.bytes().all(|c| c.is_ascii_digit()))
    };
    let (qa, qb) = (qualifiers(a), qualifiers(b));
    qa.iter().filter(|q| !qb.contains(q)).chain(qb.iter().filter(|q| !qa.contains(q))).all(harmless)
}

fn qualifiers(title: &str) -> Vec<String> {
    let mut out = Vec::new();
    let (mut depth, mut part) = (0u32, String::new());
    for c in title.chars() {
        match c {
            '(' | '[' => {
                if depth == 0 {
                    part.clear();
                }
                depth += 1;
            }
            ')' | ']' if depth > 0 => {
                depth -= 1;
                if depth == 0 {
                    out.push(words(&part));
                }
            }
            _ if depth > 0 => part.push(c),
            _ => {}
        }
    }
    out
}

pub(super) fn without_artist(title: &str, video: &Track) -> String {
    let Some((lead, rest)) = title.split_once(" - ") else { return title.to_owned() };
    let lead = words(lead);
    if !rest.trim().is_empty() && video.artists.iter().any(|a| words(&a.name) == lead) { rest.trim().to_owned() } else { title.to_owned() }
}

fn same_artist(a: &Link, b: &Link) -> bool {
    if a.id.is_some() && a.id == b.id {
        return true;
    }
    let name = words(&a.name);
    let lower = b.name.to_lowercase();
    !name.is_empty() && [" | ", " & ", ", ", " x ", " feat. ", " ft. ", " and ", " with "].iter().fold(vec![lower.as_str()], |parts, sep| parts.iter().flat_map(|p| p.split(sep)).collect()).into_iter().any(|p| words(p) == name)
}

pub(super) fn song_title(title: &str) -> String {
    const VIDEO_WORDS: &[&str] = &[
        "official", "video", "music", "lyric", "lyrics", "lyrical", "audio", "visualizer", "visualiser", "hd", "4k", "hq", "mv", "clip", "videoclip",
        "officiel", "full", "song", "with",
    ];
    const TRAILING: &[&str] = &["official music video", "official lyric video", "official video", "official audio", "official visualizer", "music video", "lyric video", "lyrical video", "video song", "full video"];
    let mut t = title.split(" | ").next().unwrap_or(title).to_owned();
    let mut out = String::new();
    let mut rest = t.as_str();
    while let Some(open) = rest.find(['(', '[']) {
        let close = if rest[open..].starts_with('(') { ')' } else { ']' };
        let Some(len) = rest[open..].find(close) else { break };
        let inner = &rest[open + 1..open + len];
        out.push_str(&rest[..open]);
        if !words(inner).split(' ').all(|w| VIDEO_WORDS.contains(&w)) {
            out.push_str(&rest[open..=open + len]);
        }
        rest = &rest[open + len + 1..];
    }
    out.push_str(rest);
    t = out.trim().to_owned();
    loop {
        let ends_with = |p: &&str| t.len() >= p.len() && t.is_char_boundary(t.len() - p.len()) && t[t.len() - p.len()..].eq_ignore_ascii_case(p);
        let Some(cut) = TRAILING.iter().find(|p| ends_with(p)).map(|p| t.len() - p.len()) else { break };
        let shorter = t[..cut].trim_end_matches(|c: char| c.is_whitespace() || "-–|:\"'".contains(c)).to_owned();
        if shorter.is_empty() {
            break;
        }
        t = shorter;
    }
    t
}

fn base_title(title: &str) -> String {
    let mut out = String::new();
    let mut depth = 0u32;
    for c in title.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = depth.saturating_sub(1),
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    words(&out)
}

fn variants(title: &str) -> Vec<String> {
    const VARIANTS: &[&str] = &[
        "live", "acoustic", "remix", "remixed", "cover", "instrumental", "karaoke", "sped", "slowed", "reverb", "nightcore", "8d", "demo",
        "unplugged", "orchestral", "extended", "version", "mashup", "rehearsal", "edit", "mix", "rework", "bootleg",
    ];
    let mut found: Vec<String> = words(title).split(' ').filter(|w| VARIANTS.contains(w)).map(str::to_owned).collect();
    found.sort();
    found.dedup();
    found
}

fn words(s: &str) -> String {
    let kept: String = s.to_lowercase().chars().filter(|c| c.is_alphanumeric() || c.is_whitespace()).collect();
    kept.split_whitespace().collect::<Vec<_>>().join(" ")
}

pub(super) fn same_title(a: &str, b: &str) -> bool {
    let (a, b) = (words(without_guests(a)), words(without_guests(b)));
    !a.is_empty() && a == b
}

fn without_guests(s: &str) -> &str {
    let s = s.trim_end();
    if let Some(open) = s.rfind(['(', '['])
        && s.ends_with([')', ']'])
    {
        let inner = s[open + 1..].to_lowercase();
        if ["feat", "ft."].iter().any(|p| inner.starts_with(p)) {
            return s[..open].trim_end();
        }
    }
    s
}
