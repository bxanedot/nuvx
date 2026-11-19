//! Playlist files: extended M3U and CSV, the same formats the Android app writes and reads
//! (`PlaylistExport.kt`, `PlaylistImport.kt`), so a playlist moves between devices and other players.

use crate::model::{Link, Thumb, Track};

const WATCH: &str = "https://music.youtube.com/watch?v=";

pub fn m3u(title: &str, tracks: &[Track]) -> String {
    let mut out = format!("#EXTM3U\n#PLAYLIST:{}\n", one_line(title));
    for t in tracks {
        let name = [t.artist_line(), t.title.clone()]
            .into_iter()
            .filter(|s| !s.trim().is_empty())
            .collect::<Vec<_>>()
            .join(" - ");
        out.push_str(&format!(
            "#EXTINF:{},{}\n{WATCH}{}\n",
            t.duration.map_or(-1, i64::from),
            one_line(&name),
            t.id
        ));
    }
    out
}

pub fn csv(tracks: &[Track]) -> String {
    let mut out = String::from("title,artist,videoId,url\r\n");
    for t in tracks {
        let row = [
            t.title.clone(),
            t.artist_line(),
            t.id.clone(),
            format!("{WATCH}{}", t.id),
        ];
        out.push_str(
            &row.iter()
                .map(|f| csv_field(f))
                .collect::<Vec<_>>()
                .join(","),
        );
        out.push_str("\r\n");
    }
    out
}

/// What a file held: its title if it names one, the songs that have a YouTube id, and how many entries had none.
pub struct Parsed {
    pub title: Option<String>,
    pub tracks: Vec<Track>,
    pub unmatched: usize,
}

pub fn parse(text: &str) -> Parsed {
    let text = text.trim_start_matches('\u{feff}');
    if text.trim_start().starts_with("#EXTM3U")
        || text.lines().any(|l| l.trim().starts_with("http")) && !text.contains(',')
    {
        parse_m3u(text)
    } else {
        parse_csv(text)
    }
}

fn parse_m3u(text: &str) -> Parsed {
    let (mut title, mut tracks, mut unmatched) = (None, Vec::new(), 0);
    let mut pending: Option<(String, Option<u32>)> = None;
    for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
        if let Some(t) = line.strip_prefix("#PLAYLIST:") {
            title = Some(t.trim().to_owned()).filter(|t| !t.is_empty());
        } else if let Some(info) = line.strip_prefix("#EXTINF:") {
            let (secs, name) = info.split_once(',').unwrap_or((info, ""));
            pending = Some((
                name.trim().to_owned(),
                secs.trim()
                    .parse::<i64>()
                    .ok()
                    .filter(|s| *s > 0)
                    .map(|s| s as u32),
            ));
        } else if !line.starts_with('#') {
            let (name, duration) = pending.take().unwrap_or_default();
            let (artist, song) = name
                .split_once(" - ")
                .map_or((String::new(), name.clone()), |(a, s)| {
                    (a.to_owned(), s.to_owned())
                });
            match video_id(line) {
                Some(id) => tracks.push(track(&id, &song, &artist, duration)),
                None => unmatched += 1,
            }
        }
    }
    Parsed {
        title,
        tracks,
        unmatched,
    }
}

fn parse_csv(text: &str) -> Parsed {
    let mut rows = csv_rows(text).into_iter();
    let header: Vec<String> = rows
        .next()
        .unwrap_or_default()
        .iter()
        .map(|h| h.trim().to_ascii_lowercase())
        .collect();
    let col = |names: &[&str]| header.iter().position(|h| names.contains(&h.as_str()));
    let (title_c, artist_c, id_c, url_c) = (
        col(&["title", "track name", "name", "song"]),
        col(&["artist", "artist name(s)", "artists"]),
        col(&["videoid", "video id", "id"]),
        col(&["url", "link"]),
    );
    let (mut tracks, mut unmatched) = (Vec::new(), 0);
    for row in rows.filter(|r| r.iter().any(|c| !c.trim().is_empty())) {
        let cell = |c: Option<usize>| {
            c.and_then(|i| row.get(i))
                .map(|v| un_formula(v.trim()).to_owned())
                .unwrap_or_default()
        };
        let id = Some(cell(id_c))
            .filter(|i| is_video_id(i))
            .or_else(|| video_id(&cell(url_c)));
        match id {
            Some(id) => tracks.push(track(&id, &cell(title_c), &cell(artist_c), None)),
            None => unmatched += 1,
        }
    }
    Parsed {
        title: None,
        tracks,
        unmatched,
    }
}

fn track(id: &str, title: &str, artist: &str, duration: Option<u32>) -> Track {
    Track {
        id: id.to_owned(),
        title: if title.is_empty() {
            id.to_owned()
        } else {
            title.to_owned()
        },
        artists: if artist.is_empty() {
            Vec::new()
        } else {
            vec![Link {
                name: artist.to_owned(),
                id: None,
            }]
        },
        album: None,
        duration,
        thumbs: vec![Thumb {
            url: format!("https://i.ytimg.com/vi/{id}/hqdefault.jpg"),
            width: 480,
            height: 360,
        }],
        track_nr: None,
        plays: None,
        music_video: false,
    }
}

/// The video id from a YouTube or YouTube Music link (`watch?v=`, `youtu.be/`), or none.
fn video_id(url: &str) -> Option<String> {
    let u = url::Url::parse(url.trim()).ok()?;
    let host = u
        .host_str()?
        .trim_start_matches("www.")
        .trim_start_matches("m.");
    let id = match host {
        "youtu.be" => u.path().trim_start_matches('/').to_owned(),
        "youtube.com" | "music.youtube.com" => {
            u.query_pairs().find(|(k, _)| k == "v")?.1.into_owned()
        }
        _ => return None,
    };
    is_video_id(&id).then_some(id)
}

fn is_video_id(id: &str) -> bool {
    id.len() == 11
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn one_line(s: &str) -> String {
    s.split(['\r', '\n'])
        .filter(|p| !p.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
        .trim()
        .to_owned()
}

/// Titles come from YouTube, so a leading = + - @ must not run as a spreadsheet formula.
fn csv_field(s: &str) -> String {
    let safe = if s.starts_with(['=', '+', '-', '@', '\t', '\r']) {
        format!("'{s}")
    } else {
        s.to_owned()
    };
    if safe.contains([',', '"', '\r', '\n']) {
        format!("\"{}\"", safe.replace('"', "\"\""))
    } else {
        safe
    }
}

fn un_formula(s: &str) -> &str {
    match s.strip_prefix('\'') {
        Some(rest) if rest.starts_with(['=', '+', '-', '@', '\t', '\r']) => rest,
        _ => s,
    }
}

fn csv_rows(text: &str) -> Vec<Vec<String>> {
    let (mut rows, mut row, mut field, mut quoted) = (Vec::new(), Vec::new(), String::new(), false);
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        match (c, quoted) {
            ('"', true) if chars.peek() == Some(&'"') => {
                field.push('"');
                chars.next();
            }
            ('"', _) => quoted = !quoted,
            (',', false) => row.push(std::mem::take(&mut field)),
            ('\n', false) => {
                row.push(std::mem::take(&mut field));
                rows.push(std::mem::take(&mut row));
            }
            ('\r', false) => {}
            _ => field.push(c),
        }
    }
    if !field.is_empty() || !row.is_empty() {
        row.push(field);
        rows.push(row);
    }
    rows
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(id: &str, title: &str, artist: &str) -> Track {
        track(id, title, artist, Some(200))
    }

    #[test]
    fn m3u_round_trip_keeps_order_titles_and_artists() {
        let list = [
            t("dQw4w9WgXcQ", "Never", "Rick"),
            t("kJQP7kiw5Fk", "Despacito", "Luis"),
        ];
        let back = parse(&m3u("My\nmix", &list));
        assert_eq!(back.title.as_deref(), Some("My mix"));
        assert_eq!(
            back.tracks
                .iter()
                .map(|x| (x.id.as_str(), x.title.as_str(), x.artist_line()))
                .collect::<Vec<_>>(),
            vec![
                ("dQw4w9WgXcQ", "Never", "Rick".to_owned()),
                ("kJQP7kiw5Fk", "Despacito", "Luis".to_owned())
            ]
        );
        assert_eq!(back.tracks[0].duration, Some(200));
    }

    #[test]
    fn csv_round_trip_with_quotes_commas_and_formula_guard() {
        let list = [t("dQw4w9WgXcQ", "=HYPERLINK(\"x\")", "A, B")];
        let text = csv(&list);
        assert!(text.contains("\"'=HYPERLINK(\"\"x\"\")\""), "{text}");
        let back = parse(&text);
        assert_eq!(
            (back.tracks[0].title.as_str(), back.tracks[0].artist_line()),
            ("=HYPERLINK(\"x\")", "A, B".to_owned())
        );
    }

    #[test]
    fn entries_without_a_youtube_link_are_counted_not_guessed() {
        let back = parse(
            "#EXTM3U\n#EXTINF:-1,Local - Song\nC:\\music\\song.mp3\n#EXTINF:-1,X - Y\nhttps://youtu.be/dQw4w9WgXcQ\n",
        );
        assert_eq!((back.tracks.len(), back.unmatched), (1, 1));
        let csv = parse(
            "title,artist,url\nA,B,https://evil.example/watch?v=dQw4w9WgXcQ\nC,D,https://www.youtube.com/watch?v=kJQP7kiw5Fk\n",
        );
        assert_eq!(
            (csv.tracks.len(), csv.unmatched, csv.tracks[0].id.as_str()),
            (1, 1, "kJQP7kiw5Fk")
        );
    }

    #[test]
    fn reads_the_android_export() {
        let android = "#EXTM3U\n#PLAYLIST:Road\n#EXTINF:-1,Rick - Never\nhttps://music.youtube.com/watch?v=dQw4w9WgXcQ\n";
        let back = parse(android);
        assert_eq!(
            (
                back.title.as_deref(),
                back.tracks[0].id.as_str(),
                back.tracks[0].duration
            ),
            (Some("Road"), "dQw4w9WgXcQ", None)
        );
    }
}
