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

impl Session {
    /// Sessions contain at most a bounded window of tracks. Reject oversized
    /// payloads before allocating nested JSON objects during a corrupt restore.
    pub(super) fn parse(json: &str) -> Option<Self> {
        const MAX_SESSION_BYTES: usize = 8 * 1024 * 1024;
        if json.len() > MAX_SESSION_BYTES { return None; }
        let session: Self = serde_json::from_str(json).ok()?;
        session.restored_index()?;
        Some(session)
    }

    /// Restore a valid queue even if an older/corrupt session saved an out-of-range
    /// cursor. Empty queues cannot select a current song.
    pub(super) fn restored_index(&self) -> Option<usize> {
        (!self.queue.is_empty()).then(|| self.index.min(self.queue.len() - 1))
    }

    /// The stream may have been replaced with a shorter version since the
    /// session was saved. An unknown duration (0) must not erase the position.
    pub(super) fn resume_position_ms(&self, duration_ms: u64) -> u64 {
        if duration_ms == 0 { self.position_ms } else { self.position_ms.min(duration_ms) }
    }
}

#[cfg(test)]
mod session_tests {
    use super::*;

    #[test]
    fn saved_queue_identity_and_order_survive_a_fresh_disk_read() {
        use std::io::Write;

        let tracks = ["first", "second", "third", "fourth"]
            .into_iter()
            .map(|id| Track {
                id: id.to_owned(), title: id.to_owned(), artists: vec![], album: None,
                duration: None, thumbs: vec![], track_nr: None, plays: None,
                music_video: false,
            })
            .collect::<Vec<_>>();
        let mut original = super::super::queue::Queue::new(super::super::Repeat::Off);
        assert!(original.replace(tracks, 2, None));
        let (saved, index) = original.saved().expect("a populated queue is saved");
        let session = Session {
            queue: saved.to_vec(), index, position_ms: 7_000,
            duration_ms: 0, listened_at: 0, playing_as: None,
        };

        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!(
            "nuvx-queue-roundtrip-{}-{unique}.json", std::process::id()
        ));
        {
            let mut file = std::fs::File::create(&path).unwrap();
            file.write_all(serde_json::to_string(&session).unwrap().as_bytes()).unwrap();
            file.sync_all().unwrap();
        }
        drop(original);
        drop(session);

        // Read from the filesystem and reconstruct a new queue; do not reuse the
        // in-memory objects or a serialization result as a substitute for persistence.
        let data = std::fs::read_to_string(&path).unwrap();
        std::fs::remove_file(&path).unwrap();
        let recovered: Session = serde_json::from_str(&data).unwrap();
        let mut restored = super::super::queue::Queue::new(super::super::Repeat::Off);
        restored.adopt(recovered.queue, recovered.index);
        assert_eq!(
            restored.tracks.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(),
            ["first", "second", "third", "fourth"]
        );
        assert_eq!(restored.index, Some(2));
        assert_eq!(restored.current().unwrap().id, "third");
    }

    #[test]
    fn restored_index_selects_middle_last_and_clamps_stale_cursor() {
        let track = |id: &str| Track {
            id: id.into(), title: id.into(), artists: vec![], album: None,
            duration: None, thumbs: vec![], track_nr: None, plays: None, music_video: false,
        };
        let mut s = Session {
            queue: vec![track("a"), track("b"), track("c")],
            index: 1, position_ms: 0, duration_ms: 0,
            listened_at: 0, playing_as: None,
        };
        assert_eq!(s.queue[s.restored_index().unwrap()].id, "b");
        s.index = 2;
        assert_eq!(s.queue[s.restored_index().unwrap()].id, "c");
        s.index = usize::MAX;
        assert_eq!(s.restored_index(), Some(2));
        s.queue.clear();
        assert_eq!(s.restored_index(), None);
    }

    #[test]
    fn resume_position_is_bounded_by_known_duration_and_kept_if_unknown() {
        let mut s = Session {
            queue: vec![], index: 0, position_ms: 42_000,
            duration_ms: 120_000, listened_at: 0, playing_as: None,
        };
        assert_eq!(s.resume_position_ms(s.duration_ms), 42_000);
        assert_eq!(s.resume_position_ms(15_000), 15_000, "a shorter replacement must clamp seek");
        assert_eq!(s.resume_position_ms(0), 42_000, "unknown duration preserves a valid seek");
        s.position_ms = 0;
        assert_eq!(s.resume_position_ms(30_000), 0, "never force playback on restore");
    }

    #[test]
    fn malformed_truncated_and_unbounded_sessions_are_rejected_without_side_effects() {
        let valid = r#"{"queue":[{"id":"old","title":"Older","artists":[],"album":null,"duration":null,"thumbs":[],"track_nr":null,"plays":null}],"index":0,"position_ms":500,"duration_ms":0}"#;
        assert_eq!(Session::parse(valid).unwrap().queue[0].id, "old");
        for bad in [
            r#"{"queue":["#,
            r#"{"queue":[],"index":0,"position_ms":1,"duration_ms":0}"#,
            r#"{"queue":"not an array","index":0,"position_ms":1,"duration_ms":0}"#,
            r#"{"queue":[],"index":0,"position_ms":1,"duration_ms":0,"unknown":"old"}"#,
        ] {
            assert!(Session::parse(bad).is_none(), "corrupt session accepted: {bad}");
        }
        assert!(Session::parse(&" ".repeat(8 * 1024 * 1024 + 1)).is_none());
        // The caller only changes the current queue for Some(session).
        let mut active = super::super::queue::Queue::new(super::super::Repeat::Off);
        active.replace(vec![Track {
            id: "active".into(), title: "Current".into(), artists: vec![],
            album: None, duration: None, thumbs: vec![], track_nr: None,
            plays: None, music_video: false,
        }], 0, None);
        if let Some(invalid) = Session::parse("{truncated") {
            active.adopt(invalid.queue, invalid.index);
        }
        assert_eq!(active.current().unwrap().id, "active");
    }

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
