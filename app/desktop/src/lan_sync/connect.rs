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
    /// Hand-off: play this YouTube song from this position (the phone pauses itself).
    PlayTrack { id: String, title: String, artist: String, position_ms: u64 },
}

impl Command {
    /// `{"action": "toggle" | "play" | "pause" | "next" | "previous" | "seek" | "playTrack", ...}`; anything else is refused.
    pub fn parse(body: &Value) -> Option<Self> {
        let position = || body.get("positionMs").and_then(Value::as_u64).filter(|ms| *ms <= MAX_POSITION_MS);
        let text = |k: &str| body.get(k).and_then(Value::as_str).map(str::trim).filter(|t| t.len() <= MAX_TEXT).map(str::to_owned);
        Some(match body.get("action")?.as_str()? {
            "toggle" => Command::Toggle,
            "play" => Command::Play,
            "pause" => Command::Pause,
            "next" => Command::Next,
            "previous" => Command::Prev,
            "seek" => Command::Seek(position()?),
            "playTrack" => {
                let too_long = |k: &str| body.get(k).and_then(Value::as_str).is_some_and(|t| t.trim().len() > MAX_TEXT);
                if too_long("title") || too_long("artist") {
                    return None;
                }
                let id = text("videoId").filter(|id| is_video_id(id))?;
                let title = text("title").filter(|t| !t.is_empty()).unwrap_or_else(|| id.clone());
                Command::PlayTrack { id, title, artist: text("artist").unwrap_or_default(), position_ms: position().unwrap_or(0) }
            }
            _ => return None,
        })
    }
}

fn is_video_id(id: &str) -> bool {
    id.len() == 11 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn known_actions_parse_and_bad_input_is_refused() {
        assert_eq!(Command::parse(&json!({ "action": "next" })), Some(Command::Next));
        assert_eq!(Command::parse(&json!({ "action": "seek", "positionMs": 61_000 })), Some(Command::Seek(61_000)));
        assert_eq!(
            Command::parse(&json!({ "action": "playTrack", "videoId": "dQw4w9WgXcQ", "title": "Never", "artist": "Rick", "positionMs": 5000 })),
            Some(Command::PlayTrack { id: "dQw4w9WgXcQ".into(), title: "Never".into(), artist: "Rick".into(), position_ms: 5000 })
        );
        assert_eq!(Command::parse(&json!({ "action": "seek" })), None, "seek needs a position");
        assert_eq!(Command::parse(&json!({ "action": "seek", "positionMs": -1 })), None);
        assert_eq!(Command::parse(&json!({ "action": "playTrack", "videoId": "../../etc" })), None);
        assert_eq!(Command::parse(&json!({ "action": "playTrack", "videoId": "dQw4w9WgXcQ", "title": "x".repeat(301) })), None);
        assert_eq!(Command::parse(&json!({ "action": "shutdown" })), None);
        assert_eq!(Command::parse(&json!("next")), None);
    }
}
