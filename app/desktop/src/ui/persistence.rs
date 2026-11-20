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
