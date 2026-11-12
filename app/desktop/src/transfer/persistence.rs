use super::*;

impl Transfer {
    fn dir(&self) -> PathBuf {
        self.data_dir.join("transfers")
    }

    pub(super) fn find_resumable(&self) -> Option<Resumable> {
        let newest = std::fs::read_dir(self.dir()).ok()?.flatten().filter(|e| e.path().extension().is_some_and(|x| x == "json")).max_by_key(|e| e.metadata().and_then(|m| m.modified()).ok())?;
        let s: Saved = serde_json::from_slice(&std::fs::read(newest.path()).ok()?).ok()?;
        let source = if s.key == "liked" { Source::Liked } else { Source::Playlist(s.key.clone()) };
        Some(Resumable {
            source,
            name: s.name,
            dest: s.dest,
            searched: s.results.iter().filter(|r| **r != Search::Pending).count(),
            total: s.tracks.len(),
        })
    }

    fn path(&self, source: &Source) -> PathBuf {
        self.dir().join(format!("{}.json", source.key()))
    }

    pub(super) fn forget(&self, source: &Source) {
        let _ = std::fs::remove_file(self.path(source));
    }

    pub(super) fn save(&self) {
        let saved = {
            let guard = self.job.lock().unwrap();
            let Some(j) = guard.as_ref() else { return };
            if j.tracks.is_empty() {
                return;
            }
            Saved {
                version: SAVED_VERSION,
                key: j.source.key().to_owned(),
                name: j.name.clone(),
                dest: j.dest.clone(),
                tracks: j.tracks.clone(),
                results: j.results.clone(),
                not_songs: j.not_songs,
                created: j.created.clone(),
                added: j.added,
                liked: j.liked,
                reviewed: j.reviewed,
            }
        };
        let path = self.path(&Source::parse_key(&saved.key));
        let write = || -> Result<()> {
            std::fs::create_dir_all(self.dir())?;
            let tmp = path.with_extension("json.tmp");
            std::fs::write(&tmp, serde_json::to_vec(&saved)?)?;
            std::fs::rename(&tmp, &path)?;
            Ok(())
        };
        if let Err(e) = write() {
            log::warn!("couldn't save transfer progress: {e:#}");
        }
    }

    pub(super) fn load_saved(&self, source: &Source) -> Option<Saved> {
        let s: Saved = serde_json::from_slice(&std::fs::read(self.path(source)).ok()?).ok()?;
        (s.version == SAVED_VERSION && s.key == source.key() && s.results.len() == s.tracks.len()).then_some(s)
    }
}
