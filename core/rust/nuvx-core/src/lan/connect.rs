//! Nuvx Connect over LAN Sync: a paired phone sees what this computer plays and controls it, or hands its own
//! song over. It rides the LAN Sync channel only, so it inherits pairing, encryption, replay protection, the
//! local-peer filter and revocation; group listening (Listen Together) is a separate feature.

use serde_json::Value;

/// Commands queued for the window, which owns the player. Bounded so a phone cannot pile them up.
pub const MAX_QUEUED: usize = 16;
const MAX_TEXT: usize = 300;
const MAX_POSITION_MS: u64 = 24 * 60 * 60 * 1000;

#[derive(Clone, Debug, PartialEq)]
pub enum Command {
    Toggle,
    Play,
    Pause,
    Next,
    Prev,
    Seek(u64),
    /// Append one validated YouTube track without moving or replacing the existing queue.
    Enqueue(Item),
    /// The phone moved playback here: these songs (the current one first, then what follows in its queue),
    /// the current one from `position_ms`, playing or ready to play.
    Load {
        tracks: Vec<Item>,
        position_ms: u64,
        paused: bool,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Item {
    pub id: String,
    pub title: String,
    pub artist: String,
    /// Cover art: the phone's, if it is a YouTube image host; otherwise the video's own thumbnail.
    pub art: String,
}

pub const MAX_TRACKS: usize = 20;

impl Command {
    /// `{"action": "toggle" | "play" | "pause" | "next" | "previous" | "seek" | "playTrack" | "enqueue", ...}`; anything else is refused.
    pub fn parse(body: &Value) -> Option<Self> {
        let position = || {
            body.get("positionMs")
                .and_then(Value::as_u64)
                .filter(|ms| *ms <= MAX_POSITION_MS)
        };
        Some(match body.get("action")?.as_str()? {
            "toggle" => Command::Toggle,
            "play" => Command::Play,
            "pause" => Command::Pause,
            "next" => Command::Next,
            "previous" => Command::Prev,
            "seek" => Command::Seek(position()?),
            "enqueue" => Command::Enqueue(item(body)?),
            "playTrack" => Command::Load {
                tracks: vec![item(body)?],
                position_ms: position().unwrap_or(0),
                paused: false,
            },
            "load" => {
                let list = body
                    .get("tracks")?
                    .as_array()
                    .filter(|l| !l.is_empty() && l.len() <= MAX_TRACKS)?;
                let tracks = list.iter().map(item).collect::<Option<Vec<_>>>()?;
                let paused = body.get("paused").and_then(Value::as_bool).unwrap_or(false);
                Command::Load {
                    tracks,
                    position_ms: position().unwrap_or(0),
                    paused,
                }
            }
            _ => return None,
        })
    }
}

/// One song: a YouTube video id plus the title and artist to show until the computer has looked it up.
fn item(v: &Value) -> Option<Item> {
    let field = |k: &str| match v.get(k) {
        None | Some(Value::Null) => Some(String::new()),
        Some(Value::String(t)) if t.trim().len() <= MAX_TEXT => Some(t.trim().to_owned()),
        _ => None,
    };
    let id = field("videoId").filter(|id| is_video_id(id))?;
    let title = field("title")?;
    let art = field("art")?;
    let art = if art_host_ok(&art) {
        art
    } else {
        format!("https://i.ytimg.com/vi/{id}/hqdefault.jpg")
    };
    Some(Item {
        title: if title.is_empty() { id.clone() } else { title },
        artist: field("artist")?,
        id,
        art,
    })
}

/// Only YouTube's own image hosts: the computer fetches this URL, so a phone must not point it anywhere else.
pub fn art_host_ok(url: &str) -> bool {
    let Ok(u) = url::Url::parse(url) else {
        return false;
    };
    let host = u.host_str().unwrap_or("");
    u.scheme() == "https"
        && ["ytimg.com", "googleusercontent.com", "ggpht.com"]
            .iter()
            .any(|h| host == *h || host.ends_with(&format!(".{h}")))
}

/// What a paired phone plays, as it last told this computer. Phones report while their player has a song, and pick
/// up the computer's commands in the reply; the phone keeps its own queue.
#[derive(Clone, Debug, PartialEq)]
pub struct PhoneNow {
    pub title: String,
    pub artist: String,
    pub playing: bool,
    pub position_ms: u64,
    pub duration_ms: Option<u64>,
    pub at_ms: i64,
}

/// A report older than this means the phone stopped playing or left the network.
pub const PHONE_FRESH_MS: i64 = 8_000;

/// What the computer may tell a phone to do.
pub const PHONE_ACTIONS: [&str; 5] = ["toggle", "play", "pause", "next", "previous"];

impl PhoneNow {
    /// `{"track": {"title", "artist", "durationMs"} | null, "playing", "positionMs"}`; `None` when nothing plays.
    pub fn parse(body: &Value, now_ms: i64) -> Option<Self> {
        let track = body.get("track").filter(|t| t.is_object())?;
        let text = |k: &str| {
            track
                .get(k)
                .and_then(Value::as_str)
                .map(|t| t.trim().chars().take(MAX_TEXT).collect::<String>())
                .unwrap_or_default()
        };
        let ms = |v: Option<&Value>| {
            v.and_then(Value::as_u64)
                .filter(|ms| *ms <= MAX_POSITION_MS)
        };
        Some(Self {
            title: text("title"),
            artist: text("artist"),
            playing: body
                .get("playing")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            position_ms: ms(body.get("positionMs")).unwrap_or(0),
            duration_ms: ms(track.get("durationMs")).filter(|d| *d > 0),
            at_ms: now_ms,
        })
    }
}

fn is_video_id(id: &str) -> bool {
    id.len() == 11
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn known_actions_parse_and_bad_input_is_refused() {
        assert_eq!(
            Command::parse(&json!({ "action": "next" })),
            Some(Command::Next)
        );
        assert_eq!(
            Command::parse(&json!({ "action": "seek", "positionMs": 61_000 })),
            Some(Command::Seek(61_000))
        );
        let thumb = |id: &str| format!("https://i.ytimg.com/vi/{id}/hqdefault.jpg");
        let never = Item {
            id: "dQw4w9WgXcQ".into(),
            title: "Never".into(),
            artist: "Rick".into(),
            art: thumb("dQw4w9WgXcQ"),
        };
        assert_eq!(
            Command::parse(
                &json!({ "action": "playTrack", "videoId": "dQw4w9WgXcQ", "title": "Never", "artist": "Rick", "positionMs": 5000 })
            ),
            Some(Command::Load {
                tracks: vec![never.clone()],
                position_ms: 5000,
                paused: false
            })
        );
        assert_eq!(
            Command::parse(
                &json!({ "action": "load", "paused": true, "positionMs": 1, "tracks": [
                { "videoId": "dQw4w9WgXcQ", "title": "Never", "artist": "Rick" }, { "videoId": "kJQP7kiw5Fk" }
            ] })
            ),
            Some(Command::Load {
                tracks: vec![
                    never,
                    Item {
                        id: "kJQP7kiw5Fk".into(),
                        title: "kJQP7kiw5Fk".into(),
                        artist: String::new(),
                        art: thumb("kJQP7kiw5Fk")
                    }
                ],
                position_ms: 1,
                paused: true
            })
        );
        assert_eq!(
            Command::parse(&json!({ "action": "load", "tracks": [] })),
            None,
            "nothing to play"
        );
        assert_eq!(
            Command::parse(&json!({ "action": "load", "tracks": [{ "videoId": "bad" }] })),
            None,
            "one bad song refuses all"
        );
        let many: Vec<Value> = (0..=MAX_TRACKS)
            .map(|_| json!({ "videoId": "dQw4w9WgXcQ" }))
            .collect();
        assert_eq!(
            Command::parse(&json!({ "action": "load", "tracks": many })),
            None,
            "bounded"
        );
        assert_eq!(
            Command::parse(&json!({ "action": "seek" })),
            None,
            "seek needs a position"
        );
        assert_eq!(
            Command::parse(&json!({ "action": "seek", "positionMs": -1 })),
            None
        );
        assert_eq!(
            Command::parse(&json!({ "action": "playTrack", "videoId": "../../etc" })),
            None
        );
        assert_eq!(
            Command::parse(
                &json!({ "action": "playTrack", "videoId": "dQw4w9WgXcQ", "title": "x".repeat(301) })
            ),
            None
        );
        assert_eq!(Command::parse(&json!({ "action": "shutdown" })), None);
        assert_eq!(Command::parse(&json!("next")), None);
    }

    #[test]
    fn enqueue_only_accepts_one_valid_youtube_track() {
        let expected = Item {
            id: "dQw4w9WgXcQ".into(),
            title: "First".into(),
            artist: "Artist".into(),
            art: "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg".into(),
        };
        assert_eq!(
            Command::parse(&json!({ "action": "enqueue", "videoId": "dQw4w9WgXcQ", "title": "First", "artist": "Artist" })),
            Some(Command::Enqueue(expected))
        );
        assert_eq!(Command::parse(&json!({ "action": "enqueue", "videoId": "file:///local.wav" })), None);
        assert_eq!(Command::parse(&json!({ "action": "enqueue", "tracks": [{ "videoId": "dQw4w9WgXcQ" }] })), None);
        assert_eq!(Command::parse(&json!({ "action": "enqueue", "videoId": "dQw4w9WgXcQ", "title": "x".repeat(301) })), None);
    }

    #[test]
    fn phone_reports_are_bounded() {
        let now = PhoneNow::parse(&json!({ "track": { "title": "x".repeat(400), "artist": "A", "durationMs": 1000 }, "playing": true, "positionMs": 5 }), 7).unwrap();
        assert_eq!(
            (
                now.title.chars().count(),
                now.artist.as_str(),
                now.playing,
                now.position_ms,
                now.duration_ms,
                now.at_ms
            ),
            (300, "A", true, 5, Some(1000), 7)
        );
        assert_eq!(
            PhoneNow::parse(&json!({ "track": null, "playing": false }), 0),
            None,
            "nothing plays"
        );
        assert_eq!(
            PhoneNow::parse(&json!({ "track": {}, "positionMs": -4 }), 0)
                .unwrap()
                .position_ms,
            0
        );
        assert_eq!(PhoneNow::parse(&json!("x"), 0), None);
    }

    #[test]
    fn cover_art_only_from_youtube_image_hosts() {
        let art = |url: &str| match Command::parse(
            &json!({ "action": "load", "tracks": [{ "videoId": "dQw4w9WgXcQ", "art": url }] }),
        ) {
            Some(Command::Load { tracks, .. }) => tracks[0].art.clone(),
            _ => panic!("refused"),
        };
        let fallback = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg";
        assert_eq!(
            art("https://lh3.googleusercontent.com/abc=w544"),
            "https://lh3.googleusercontent.com/abc=w544"
        );
        assert_eq!(
            art("http://lh3.googleusercontent.com/abc"),
            fallback,
            "https only"
        );
        assert_eq!(art("https://evil.example/x.jpg"), fallback);
        assert_eq!(
            art("https://googleusercontent.com.evil.example/x"),
            fallback
        );
        assert_eq!(
            art("https://192.168.1.1/router"),
            fallback,
            "never the local network"
        );
    }
}
