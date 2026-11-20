//! The portable nuvx backup: one JSON file with the playlists made in nuvx. The same format the Android app writes
//! (`Backup.kt`), so a backup made on the phone restores on the computer and the other way round.
//! The envelope is `{ app: "nuvx", version: 1, versionName, exportedAt, settings, listening, playlists }`; settings
//! and listening are per-app and are left alone here, `playlists` is shared. Shared case: `core/contracts/backup-v1.json`.

use crate::model::{Link, Thumb, Track};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

pub const APP_TAG: &str = "nuvx";
pub const SCHEMA_VERSION: u32 = 1;
/// A backup bigger than this is refused: nobody's playlists are that large, and it bounds memory.
pub const MAX_BYTES: usize = 20 * 1024 * 1024;
const MAX_PLAYLISTS: usize = 2_000;
const MAX_SONGS_PER_PLAYLIST: usize = 20_000;

/// A playlist as the apps keep it: a stable id, a title and its songs in order.
#[derive(Clone, Debug, PartialEq)]
pub struct Playlist {
    pub id: String,
    pub title: String,
    pub tracks: Vec<Track>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct File {
    #[serde(default = "app_tag")]
    app: String,
    #[serde(default = "one")]
    version: u32,
    #[serde(default)]
    version_name: String,
    #[serde(default)]
    exported_at: String,
    #[serde(default)]
    settings: Map<String, Value>,
    #[serde(default)]
    listening: Vec<Value>,
    #[serde(default)]
    playlists: Vec<BackupPlaylist>,
}

fn app_tag() -> String {
    APP_TAG.to_owned()
}

fn one() -> u32 {
    1
}

#[derive(Serialize, Deserialize)]
struct BackupPlaylist {
    id: String,
    title: String,
    #[serde(default)]
    songs: Vec<BackupSong>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BackupSong {
    video_id: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    artist: String,
    #[serde(default)]
    thumbnail_url: Option<String>,
    #[serde(default)]
    duration_text: Option<String>,
    #[serde(default)]
    artist_id: Option<String>,
    #[serde(default)]
    album_id: Option<String>,
    #[serde(default)]
    album_name: Option<String>,
    #[serde(default)]
    is_video: bool,
}

/// The backup file's text for these playlists. `exported_at` is an ISO-8601 time (see [`iso_utc`]).
pub fn export(playlists: &[Playlist], version_name: &str, exported_at: &str) -> String {
    let file = File {
        app: APP_TAG.into(),
        version: SCHEMA_VERSION,
        version_name: version_name.into(),
        exported_at: exported_at.into(),
        settings: Map::new(),
        listening: Vec::new(),
        playlists: playlists
            .iter()
            .map(|p| BackupPlaylist {
                id: p.id.clone(),
                title: p.title.clone(),
                songs: p.tracks.iter().map(to_song).collect(),
            })
            .collect(),
    };
    serde_json::to_string_pretty(&file).unwrap_or_default()
}

/// The playlists in a backup, or why it cannot be read. Songs without a valid YouTube id are left out.
pub fn import(text: &str) -> Result<Vec<Playlist>, String> {
    if text.len() > MAX_BYTES {
        return Err("That file is too large for a backup".into());
    }
    let file: File = serde_json::from_str(text)
        .map_err(|_| "That doesn't look like a nuvx backup".to_owned())?;
    if file.app != APP_TAG {
        return Err("That backup is from another app".into());
    }
    if file.version > SCHEMA_VERSION {
        return Err("That backup was written by a newer version of nuvx".into());
    }
    // Never silently drop playlists or songs by truncating an imported backup.
    // Reject the entire file before any caller can apply a partial restore.
    if file.playlists.len() > MAX_PLAYLISTS
        || file.playlists.iter().any(|p| p.songs.len() > MAX_SONGS_PER_PLAYLIST)
    {
        return Err("That backup exceeds the supported playlist or song limit".into());
    }
    Ok(file
        .playlists
        .into_iter()
        .take(MAX_PLAYLISTS)
        .filter(|p| !p.id.trim().is_empty() && p.id.len() <= 200)
        .map(|p| Playlist {
            tracks: p
                .songs
                .into_iter()
                .take(MAX_SONGS_PER_PLAYLIST)
                .filter_map(from_song)
                .collect(),
            id: p.id,
            title: p.title,
        })
        .collect())
}

fn to_song(t: &Track) -> BackupSong {
    BackupSong {
        video_id: t.id.clone(),
        title: t.title.clone(),
        artist: t.artist_line(),
        thumbnail_url: t.thumbs.first().map(|th| th.url.clone()),
        duration_text: t.duration.map(duration_text),
        artist_id: t.artists.first().and_then(|a| a.id.clone()),
        album_id: t.album.as_ref().and_then(|a| a.id.clone()),
        album_name: t.album.as_ref().map(|a| a.name.clone()),
        is_video: t.music_video,
    }
}

fn from_song(s: BackupSong) -> Option<Track> {
    if s.video_id.len() != 11
        || !s
            .video_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return None;
    }
    let (w, h) = match &s.thumbnail_url {
        // YouTube video thumbnails are 4:3 frames with bars; album art is square.
        Some(u) if u.contains("ytimg.com/vi/") => (480, 360),
        _ => (544, 544),
    };
    Some(Track {
        id: s.video_id,
        title: s.title,
        artists: if s.artist.is_empty() {
            Vec::new()
        } else {
            vec![Link {
                name: s.artist,
                id: s.artist_id,
            }]
        },
        album: s.album_name.map(|name| Link {
            name,
            id: s.album_id,
        }),
        duration: s.duration_text.as_deref().and_then(parse_duration),
        thumbs: s
            .thumbnail_url
            .map(|url| {
                vec![Thumb {
                    url,
                    width: w,
                    height: h,
                }]
            })
            .unwrap_or_default(),
        track_nr: None,
        plays: None,
        music_video: s.is_video,
    })
}

/// `225` -> `3:45`, `3725` -> `1:02:05`.
fn duration_text(secs: u32) -> String {
    let (h, m, s) = (secs / 3600, secs / 60 % 60, secs % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

/// `3:45` -> `225`; anything else -> none.
fn parse_duration(text: &str) -> Option<u32> {
    let parts: Vec<u32> = text
        .trim()
        .split(':')
        .map(|p| p.parse().ok())
        .collect::<Option<_>>()?;
    match parts.as_slice() {
        [m, s] if *s < 60 => Some(m * 60 + s),
        [h, m, s] if *m < 60 && *s < 60 => Some(h * 3600 + m * 60 + s),
        _ => None,
    }
}

/// `2023-11-14T22:13:20Z` for a Unix time in seconds (UTC), no date library needed.
pub fn iso_utc(unix_secs: u64) -> String {
    let (days, rem) = (unix_secs / 86_400, unix_secs % 86_400);
    // Days since 1970-01-01 to a civil date (Howard Hinnant's algorithm).
    let z = days as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        rem / 60 % 60,
        rem % 60
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(id: &str, title: &str, artist: &str, secs: Option<u32>) -> Track {
        Track {
            id: id.into(),
            title: title.into(),
            artists: vec![Link {
                name: artist.into(),
                id: Some("UC1".into()),
            }],
            album: Some(Link {
                name: "Album".into(),
                id: Some("MPRE1".into()),
            }),
            duration: secs,
            thumbs: vec![Thumb {
                url: "https://lh3.googleusercontent.com/a=w544".into(),
                width: 544,
                height: 544,
            }],
            track_nr: None,
            plays: None,
            music_video: false,
        }
    }

    #[test]
    fn a_backup_round_trips_playlists_songs_and_order() {
        let list = vec![
            Playlist {
                id: "nuvx_local_1".into(),
                title: "Road".into(),
                tracks: vec![
                    track("dQw4w9WgXcQ", "Never", "Rick", Some(213)),
                    track("kJQP7kiw5Fk", "Despacito", "Luis", Some(3725)),
                ],
            },
            Playlist {
                id: "nuvx_local_2".into(),
                title: "Empty".into(),
                tracks: vec![],
            },
        ];
        let text = export(&list, "1.0.3", "2026-11-19T10:00:00Z");
        let back = import(&text).unwrap();
        assert_eq!(back, list);
        assert!(
            text.contains("\"app\": \"nuvx\"")
                && text.contains("\"3:33\"")
                && text.contains("\"1:02:05\"")
        );
    }

    #[test]
    fn empty_local_playlist_export_contains_no_other_private_categories() {
        let out = export(&[], "1.0.4", "2026-11-20T00:00:00Z");
        let value: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(value["app"], "nuvx");
        assert_eq!(value["version"], 1);
        assert_eq!(value["playlists"].as_array().unwrap().len(), 0);
        assert!(value["listening"].as_array().unwrap().is_empty());
        assert!(value["settings"].as_object().unwrap().is_empty());
        assert!(import(&out).unwrap().is_empty());
    }

    #[test]
    fn one_playlist_export_round_trips_without_private_categories() {
        let empty = Playlist { id: "local_empty".into(), title: "Ø Empty 🧭".into(), tracks: vec![] };
        assert_eq!(import(&export(&[empty.clone()], "1.0.4", "2026-11-20T00:00:00Z")).unwrap(), vec![empty]);
        let tracks: Vec<_> = (0..2500).map(|i| {
            track(&format!("{i:011}"), "Emoji 🎵 \"quoted\" and newline", "Artiste é", Some(123))
        }).collect();
        let single = Playlist { id: "local_unicode".into(), title: "Tést 🦊 Road".into(), tracks };
        let encoded = export(&[single.clone()], "1.0.4", "2026-11-20T00:00:00Z");
        assert!(encoded.len() < MAX_BYTES);
        assert_eq!(import(&encoded).unwrap(), vec![single]);
        let v: Value = serde_json::from_str(&encoded).unwrap();
        assert_eq!(v["playlists"].as_array().unwrap().len(), 1);
        assert!(v["settings"].as_object().unwrap().is_empty());
        assert!(v["listening"].as_array().unwrap().is_empty());
    }

    #[test]
    fn reads_the_shared_android_format_case() {
        let fixture = include_str!("../../../contracts/backup-v1.json");
        let list = import(fixture).unwrap();
        let expect: Value = serde_json::from_str(fixture).unwrap();
        assert_eq!(list.len(), expect["playlists"].as_array().unwrap().len());
        let first = &list[0];
        assert_eq!(
            (first.id.as_str(), first.title.as_str()),
            ("nuvx_local_a1", "Phone mix")
        );
        assert_eq!(
            first
                .tracks
                .iter()
                .map(|t| t.id.as_str())
                .collect::<Vec<_>>(),
            ["dQw4w9WgXcQ", "kJQP7kiw5Fk"],
            "the song with a bad id is left out"
        );
        assert_eq!(first.tracks[0].duration, Some(213));
        assert_eq!(first.tracks[0].artist_line(), "Rick Astley");
        // The settings and listening parts of the phone's backup are ignored, not an error.
    }

    #[test]
    fn oversized_imports_are_rejected_instead_of_truncated() {
        let too_many_songs = serde_json::json!({
            "app": "nuvx", "version": 1, "playlists": [{
                "id": "large", "title": "Large",
                "songs": (0..=MAX_SONGS_PER_PLAYLIST)
                    .map(|_| serde_json::json!({"videoId": "abcdefghijk"}))
                    .collect::<Vec<_>>()
            }]
        });
        assert!(import(&too_many_songs.to_string()).unwrap_err().contains("limit"));
        let too_many_playlists = serde_json::json!({
            "app": "nuvx", "version": 1,
            "playlists": (0..=MAX_PLAYLISTS)
                .map(|i| serde_json::json!({"id": format!("p{i}"), "title": "T"}))
                .collect::<Vec<_>>()
        });
        assert!(import(&too_many_playlists.to_string()).unwrap_err().contains("limit"));
    }

    #[test]
    fn refuses_what_is_not_a_usable_backup() {
        assert_eq!(
            import("not json").unwrap_err(),
            "That doesn't look like a nuvx backup"
        );
        assert_eq!(
            import(r#"{"app":"other","version":1}"#).unwrap_err(),
            "That backup is from another app"
        );
        assert_eq!(
            import(r#"{"app":"nuvx","version":2}"#).unwrap_err(),
            "That backup was written by a newer version of nuvx"
        );
        assert!(
            import(&" ".repeat(MAX_BYTES + 1))
                .unwrap_err()
                .contains("too large")
        );
        assert!(
            import(r#"{"app":"nuvx","version":1,"playlists":[{"id":" ","title":"x"}]}"#)
                .unwrap()
                .is_empty(),
            "blank ids are dropped"
        );
    }

    #[test]
    fn utc_time_text_is_right() {
        assert_eq!(iso_utc(0), "1970-01-01T00:00:00Z");
        assert_eq!(iso_utc(1_700_000_000), "2023-11-14T22:13:20Z");
        assert_eq!(iso_utc(951_782_400), "2000-02-29T00:00:00Z", "a leap day");
        assert_eq!(iso_utc(4_102_444_799), "2099-12-31T23:59:59Z");
    }

    #[test]
    fn durations_read_and_write_like_the_phone() {
        assert_eq!(duration_text(59), "0:59");
        assert_eq!(parse_duration("3:45"), Some(225));
        assert_eq!(parse_duration("1:02:05"), Some(3725));
        assert_eq!(parse_duration("3:75"), None);
        assert_eq!(parse_duration("abc"), None);
    }
}
