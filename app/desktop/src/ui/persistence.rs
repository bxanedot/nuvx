use super::*;

pub(super) const SESSION_KEY: &str = "session";

#[derive(serde::Serialize, serde::Deserialize)]
pub(super) struct AccountCache {
    pub(super) name: Option<String>,
    pub(super) avatar: Option<String>,
    pub(super) playlists: Vec<Card>,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub(super) struct Session {
    pub(super) queue: Vec<Track>,
    pub(super) index: usize,
    pub(super) position_ms: u64,
    pub(super) duration_ms: u64,
    #[serde(default)]
    pub(super) listened_at: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(super) playing_as: Option<Track>,
}

#[cfg(test)]
mod session_tests {
    use super::*;

    #[test]
    fn sessions_saved_before_song_versions_still_load() {
        let old = r#"{"queue":[{"id":"a","title":"A","artists":[],"album":null,"duration":null,"thumbs":[],"track_nr":null,"plays":null}],"index":0,"position_ms":5000,"duration_ms":0}"#;
        let s: Session = serde_json::from_str(old).unwrap();
        assert!(!s.queue[0].music_video && s.playing_as.is_none());
        let saved = serde_json::to_string(&s).unwrap();
        assert!(!saved.contains("music_video") && !saved.contains("playing_as"), "{saved}");
        let video = Track { music_video: true, ..s.queue[0].clone() };
        let back: Track = serde_json::from_str(&serde_json::to_string(&video).unwrap()).unwrap();
        assert!(back.music_video);
    }
}
