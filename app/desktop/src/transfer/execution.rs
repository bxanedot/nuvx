use super::*;

impl Transfer {
    async fn checkpoint(&self) -> Result<()> {
        loop {
            if self.cancel.load(Ordering::Relaxed) {
                bail!("cancelled");
            }
            if self.pinned.lock().unwrap().as_ref().is_some_and(|(api, epoch)| api.epoch() != *epoch) {
                bail!("Stopped: the YouTube Music account or channel changed");
            }
            if !self.with_job(|j| j.paused) {
                return Ok(());
            }
            tokio::time::sleep(Duration::from_millis(250)).await;
        }
    }

    pub(super) async fn run(&self, api: &Api) -> Result<()> {
        anyhow::ensure!(api.account().is_some(), "Sign in to YouTube Music first");
        let source = self.with_job(|j| j.source.clone());

        let dest = self.with_job(|j| j.dest.clone());
        match self.load_saved(&source).filter(|s| s.dest == dest) {
            Some(s) => self.with_job(|j| {
                j.read = (s.tracks.len(), s.tracks.len());
                j.name = s.name;
                j.tracks = s.tracks;
                j.results = s.results;
                j.not_songs = s.not_songs;
                j.created = s.created;
                j.added = s.added;
                j.liked = s.liked;
                j.reviewed = s.reviewed;
            }),
            None => {
                let t = Instant::now();
                let cookie = self.cookie();
                anyhow::ensure!(cookie.is_some() || source != Source::Liked, "Sign in to Spotify to copy your Liked Songs");
                let client = spotify::Client::connect(cookie.as_deref(), &self.data_dir).await?;
                let progress = |done: usize, total: usize| {
                    self.with_job(|j| j.read = (done, total));
                    self.wake();
                };
                let playlist = match &source {
                    Source::Playlist(id) => client.playlist(id, progress).await?,
                    Source::Liked => client.liked(progress).await?,
                };
                let mut playlist = playlist;
                if let Some(n) = std::env::var("NUVX_TRANSFER_LIMIT").ok().and_then(|n| n.parse().ok()) {
                    playlist.tracks.truncate(n);
                }
                self.with_job(|j| {
                    j.read_secs = Some(t.elapsed().as_secs_f32());
                    j.read = (playlist.tracks.len(), playlist.tracks.len());
                    j.results = vec![Search::Pending; playlist.tracks.len()];
                    j.name = playlist.name;
                    j.tracks = playlist.tracks;
                    j.not_songs = playlist.skipped;
                });
                self.save();
            }
        }

        self.with_job(|j| j.phase = Phase::Searching);
        self.wake();
        for pass in 0..3 {
            if pass > 0 {
                tokio::time::sleep(Duration::from_secs(3 * pass)).await;
            }
            if self.search_pass(api).await? == 0 {
                break;
            }
        }
        let pending = self.with_job(|j| j.count(|r| *r == Search::Pending));
        anyhow::ensure!(pending == 0, "{pending} songs couldn't be searched (no connection, or YouTube Music is limiting requests)");

        let doubtful: Vec<usize> = self.with_job(|j| {
            j.phase = Phase::Checking;
            if j.reviewed {
                return Vec::new();
            }
            (0..j.results.len()).filter(|&i| matches!(j.results[i], Search::Unsure(_) | Search::Missing)).collect()
        });
        self.wake();
        let tracks: Vec<Track> = self.with_job(|j| doubtful.iter().map(|&i| j.tracks[i].clone()).collect());
        let mut found: Vec<(usize, Vec<(YtTrack, bool)>)> = stream::iter(doubtful.iter().copied().zip(tracks))
            .map(|(i, t)| async move {
                if self.checkpoint().await.is_err() {
                    return (i, Vec::new());
                }
                (i, candidates(api, &t).await.unwrap_or_default())
            })
            .buffer_unordered(CONCURRENCY)
            .collect()
            .await;
        self.checkpoint().await?;
        found.sort_by_key(|(i, _)| *i);
        self.with_job(|j| {
            for (i, cands) in found {
                let t = &j.tracks[i];
                let songs: Vec<YtTrack> = cands.iter().filter(|(_, video)| !video).map(|(h, _)| h.clone()).collect();
                if let Some((hit, sure)) = best(&songs, t) {
                    if sure {
                        j.results[i] = Search::Found(hit);
                        continue;
                    }
                    if j.results[i].track().is_none_or(|cur| score(&hit, t) > score(cur, t)) {
                        j.results[i] = Search::Unsure(hit);
                    }
                }
                let (candidates, videos) = cands.into_iter().unzip();
                j.doubts.push(Doubt { index: i, candidates, videos, decision: None, loading: false });
            }
        });
        self.save();

        if self.with_job(|j| !j.doubts.is_empty()) {
            self.with_job(|j| j.phase = Phase::Review);
            self.wake();
            while self.with_job(|j| j.current_doubt().is_some()) {
                self.review_done.notified().await;
                self.checkpoint().await?;
            }
            self.with_job(|j| {
                for d in std::mem::take(&mut j.doubts) {
                    match d.decision {
                        Some(Decision::Pick(k)) if k < d.candidates.len() => j.results[d.index] = Search::Found(d.candidates[k].clone()),
                        Some(Decision::Skip) => j.results[d.index] = Search::Skipped,
                        _ => {}
                    }
                }
            });
        }
        self.with_job(|j| j.reviewed = true);
        self.save();

        self.with_job(|j| j.phase = Phase::Saving);
        self.wake();
        let (ids, picked, dest) = self.with_job(|j| {
            if j.dest == Dest::Playlist(String::new()) {
                j.dest = Dest::Playlist(format!("{} from Spotify", j.name));
            }
            let mut seen = HashSet::new();
            let ids: Vec<String> = j.results.iter().filter_map(Search::track).filter(|t| seen.insert(t.id.clone())).map(|t| t.id.clone()).collect();
            (ids, j.results.iter().filter(|r| r.track().is_some()).count(), j.dest.clone())
        });
        anyhow::ensure!(!ids.is_empty(), "None of the songs were found on YouTube Music");
        let duplicates = picked - ids.len();
        let dry = std::env::var_os("NUVX_TRANSFER_DRY").is_some();
        let (playlists, already) = match &dest {
            _ if dry => (Vec::new(), 0),
            Dest::Playlist(title) => (self.create_playlists(api, title, &ids).await?, 0),
            Dest::Liked => (Vec::new(), self.like_all(api, &ids).await?),
        };

        let elapsed = self.with_job(|j| j.started.elapsed());
        self.with_job(|j| {
            let added = if dest == Dest::Liked && !dry { j.liked } else { ids.len() };
            j.summary = Some(Summary { added, duplicates, already, playlists, elapsed });
            j.phase = Phase::Done;
        });
        self.forget(&source);
        Ok(())
    }

    async fn search_pass(&self, api: &Api) -> Result<usize> {
        let todo: Vec<(usize, Track)> = self.with_job(|j| {
            let todo: Vec<(usize, Track)> = (0..j.tracks.len()).filter(|&i| j.results[i] == Search::Pending).map(|i| (i, j.tracks[i].clone())).collect();
            j.search_started = Some((Instant::now(), todo.len()));
            todo
        });
        let mut results = stream::iter(todo)
            .map(|(i, t)| async move {
                if let Err(e) = self.checkpoint().await {
                    return (i, Err(e));
                }
                (i, search_song(api, &t).await)
            })
            .buffer_unordered(SEARCHES);
        let (mut failed, mut last_save, mut last_wake) = (0, Instant::now(), Instant::now());
        while let Some((i, res)) = results.next().await {
            self.checkpoint().await?;
            let result = match res {
                Ok(Some((t, true))) => Search::Found(t),
                Ok(Some((t, false))) => Search::Unsure(t),
                Ok(None) => Search::Missing,
                Err(e) if signed_out(&e) => return Err(e.context("YouTube Music signed you out; sign in again and try again")),
                Err(e) => {
                    log::debug!("transfer search: {e:#}");
                    failed += 1;
                    Search::Pending
                }
            };
            self.with_job(|j| j.results[i] = result);
            if last_save.elapsed() > SAVE_EVERY {
                self.save();
                last_save = Instant::now();
            }
            if last_wake.elapsed() > Duration::from_millis(120) {
                self.wake();
                last_wake = Instant::now();
            }
        }
        self.save();
        self.wake();
        Ok(failed)
    }

    async fn create_playlists(&self, api: &Api, title: &str, ids: &[String]) -> Result<Vec<String>> {
        const BATCH: usize = 200;
        let parts: Vec<&[String]> = ids.chunks(PLAYLIST_MAX).collect();
        self.with_job(|j| j.saving = (j.added.min(ids.len()), ids.len()));
        for (n, part) in parts.iter().enumerate() {
            let base = n * PLAYLIST_MAX;
            let id = match self.with_job(|j| j.created.get(n).cloned()) {
                Some(id) => id,
                None => {
                    self.checkpoint().await?;
                    let part_title = if parts.len() > 1 { format!("{title} ({})", n + 1) } else { title.to_owned() };
                    let first = &part[..part.len().min(BATCH)];
                    let id = ytm::create_playlist_with(api, &part_title, first).await.context("Creating the playlist failed")?;
                    self.with_job(|j| {
                        j.created.push(id.clone());
                        j.added = base + first.len();
                        j.saving.0 = j.added;
                    });
                    self.save();
                    self.wake();
                    id
                }
            };
            loop {
                let done = self.with_job(|j| j.added.saturating_sub(base)).min(part.len());
                if done == part.len() {
                    break;
                }
                self.checkpoint().await?;
                let batch = &part[done..(done + BATCH).min(part.len())];
                retry(|| ytm::add_videos(api, &id, batch)).await.context("Adding songs to the playlist failed")?;
                self.with_job(|j| {
                    j.added = base + done + batch.len();
                    j.saving.0 = j.added;
                });
                self.save();
                self.wake();
            }
        }
        Ok(self.with_job(|j| j.created.clone()))
    }

    async fn like_all(&self, api: &Api, ids: &[String]) -> Result<usize> {
        let _turn = match crate::backend::LIKES.try_lock() {
            Ok(turn) => turn,
            Err(_) => {
                self.with_job(|j| j.waiting = true);
                self.wake();
                let turn = crate::backend::LIKES.lock().await;
                self.with_job(|j| j.waiting = false);
                turn
            }
        };
        let read_liked = || async {
            let lm = retry(|| ytm::playlist(api, "LM", true, Some(20_000))).await.context("Reading your Liked Music failed")?;
            anyhow::Ok(lm.tracks.into_iter().map(|t| t.id).collect::<HashSet<String>>())
        };
        let liked = read_liked().await?;
        let todo: Vec<&String> = ids.iter().rev().filter(|id| !liked.contains(*id)).collect();
        log::debug!("transfer: {} matched, Liked Music lists {}, {} of the matched already in it, {} to like", ids.len(), liked.len(), ids.len() - todo.len(), todo.len());
        let earlier = self.with_job(|j| {
            j.saving = (j.liked, j.liked + todo.len());
            j.liked
        });
        let mut last_save = Instant::now();
        for id in &todo {
            self.like_one(api, id, true).await?;
            tokio::time::sleep(crate::backend::LIKE_GAP).await;
            if last_save.elapsed() > SAVE_EVERY {
                self.save();
                last_save = Instant::now();
            }
        }
        let (mut gone, mut before) = (0, usize::MAX);
        for round in 0..3 {
            tokio::time::sleep(Duration::from_secs(2)).await;
            let now = read_liked().await?;
            let missing: Vec<&String> = ids.iter().rev().filter(|id| !now.contains(*id)).collect();
            if round == 0 {
                gone = missing.iter().filter(|id| liked.contains(**id)).count();
                self.with_job(|j| j.saving.1 += gone);
            }
            log::debug!("transfer: re-check {round}: Liked Music lists {}, {} missing ({gone} skipped as liked)", now.len(), missing.len());
            if missing.is_empty() || missing.len() >= before {
                break;
            }
            before = missing.len();
            self.with_job(|j| j.redo = Some((0, missing.len())));
            self.wake();
            for (i, id) in missing.iter().enumerate() {
                self.like_one(api, id, round == 0 && liked.contains(*id)).await?;
                self.with_job(|j| j.redo = Some((i + 1, missing.len())));
                tokio::time::sleep(crate::backend::LIKE_GAP).await;
            }
        }
        self.with_job(|j| j.redo = None);
        self.save();
        Ok((ids.len() - todo.len() - gone).saturating_sub(earlier))
    }

    async fn like_one(&self, api: &Api, id: &str, count: bool) -> Result<()> {
        self.checkpoint().await?;
        let (mut tries, mut hung) = (0, 0);
        loop {
            let Ok(sent) = tokio::time::timeout(crate::backend::LIKE_WAIT, ytm::set_like(api, id, true)).await else {
                log::debug!("like {id}: no answer in {:?}; sending it again", crate::backend::LIKE_WAIT);
                hung += 1;
                if hung == 10 {
                    bail!("Liking songs failed: YouTube Music stopped answering");
                }
                continue;
            };
            tries += 1;
            match sent {
                Ok(()) => break,
                Err(e) if signed_out(&e) => return Err(e.context("YouTube Music signed you out; sign in again and try again")),
                Err(e) if tries < 4 => {
                    log::debug!("like {id}: {e:#}");
                    tokio::time::sleep(Duration::from_millis(800 * tries)).await;
                }
                Err(e) => return Err(e.context("Liking songs failed")),
            }
        }
        if count {
            self.with_job(|j| {
                j.liked += 1;
                j.saving.0 = j.liked;
            });
        }
        self.wake();
        let n = self.with_job(|j| j.liked);
        if count && n.is_multiple_of(25) {
            log::debug!("transfer: {n} liked");
        }
        Ok(())
    }
}
