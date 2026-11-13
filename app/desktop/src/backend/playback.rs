use super::*;

impl Backend {
    pub(super) async fn start_playback(&self, listed: &str, song_of: Option<&Track>, carried: Option<Track>, track: Option<&Track>, generation: u64, at: std::time::Duration, report: bool, start_paused: bool) -> anyhow::Result<()> {
        if let Some(url) = listed.strip_prefix(crate::radio::PREFIX) {
            return self.play_station(url, generation).await;
        }
        let t0 = std::time::Instant::now();
        let was_carried = carried.is_some();
        let mut song = match carried {
            Some(song) => Some(song),
            None => self.song_version(listed, song_of, PLAY_SONG_WAIT).await,
        };
        let meta = song.clone().or_else(|| song_of.cloned()).or_else(|| track.cloned());
        if self.audio.status.wanted_gen.load(Ordering::Relaxed) != generation {
            return Ok(());
        }
        let mut video_id = song.as_ref().map_or(listed, |s| s.id.as_str()).to_owned();
        let prefetched = self.prefetched.lock().unwrap().take();
        let reverted = self.reverted.lock().unwrap().contains(&video_id);
        let replay = self.current.lock().unwrap().take().filter(|(id, s)| !reverted && *id == video_id && s.is_complete()).map(|(_, s)| s);
        let ready = match prefetched {
            Some((id, s)) if id == video_id => Some(s),
            other if replay.is_some() => {
                *self.prefetched.lock().unwrap() = other;
                replay
            }
            other => {
                if let Some((_, s)) = other {
                    s.cancel();
                }
                None
            }
        };
        let shared = match ready {
            Some(s) => {
                log::debug!("play {video_id} (gen {generation}): using buffered stream");
                s
            }
            None => {
                if self.audio.status.wanted_gen.load(Ordering::Relaxed) != generation {
                    return Ok(());
                }
                match self.open_stream(&video_id, false, meta.as_ref()).await {
                    Ok(s) => s,
                    Err(e) if song.is_some() => {
                        log::warn!("{listed}'s song {video_id} won't play ({e:#}); playing the music video");
                        (song, video_id) = (None, listed.to_owned());
                        if was_carried {
                            let _ = self.tx.send(Resp::PlayingAs { generation, listed: listed.to_owned(), song: None });
                        }
                        self.open_stream(listed, false, None).await?
                    }
                    Err(e) => return Err(e),
                }
            }
        };
        let fallback = song.is_some().then(|| (self.clone(), listed.to_owned(), report));
        if let Some(song) = song {
            log::info!("play {listed} as its song {video_id}");
            if !was_carried {
                let _ = self.tx.send(Resp::PlayingAs { generation, listed: listed.to_owned(), song: Some(song) });
                (self.wake)();
            }
        }
        let video_id = video_id.as_str();
        *self.current.lock().unwrap() = Some((video_id.to_owned(), shared.clone()));
        log::debug!("play {video_id} (gen {generation}): stream open in {:?}", t0.elapsed());
        if self.audio.status.wanted_gen.load(Ordering::Relaxed) != generation {
            shared.cancel();
            return Ok(());
        }

        let audio = self.audio.clone();
        let tx = self.tx.clone();
        let wake = self.wake.clone();
        let seek = !at.is_zero();
        let shared2 = shared.clone();
        let report = (report && self.is_logged_in() && reports_plays()).then(|| (self.clone(), video_id.to_owned()));
        let open = move || -> anyhow::Result<crate::audio::TrackDecoder> {
            let mut d = stream::decoder(shared.clone(), seek)?;
            if seek {
                rodio::Source::try_seek(&mut d, at).map_err(|e| anyhow::anyhow!("seek: {e}"))?;
            }
            Ok(d)
        };
        tokio::task::spawn_blocking(move || match open() {
            Ok(decoder) => {
                log::debug!("play gen {generation}: decoder ready at {at:?} in {:?}", t0.elapsed());
                if let Some(mut info) = shared2.info.clone() {
                    info.sample_rate = Some(rodio::Source::sample_rate(&decoder).get());
                    info.channels = Some(rodio::Source::channels(&decoder).get() as u8);
                    let _ = tx.send(Resp::AudioInfo { generation, info });
                    wake();
                }
                audio.send(AudioCmd::Start { generation, decoder, shared: shared2, at, start_paused });
                if let Some((this, id)) = report.filter(|_| audio.status.wanted_gen.load(Ordering::Relaxed) == generation) {
                    let rt = this.rt.clone();
                    rt.spawn(async move {
                        match ytm::report_play(&this.api, &id).await {
                            Ok(w) => {
                                log::debug!("reported play of {id}");
                                this.report_watchtime(&id, generation, w).await;
                            }
                            Err(e) => log::warn!("couldn't report play of {id}: {e:#}"),
                        }
                    });
                }
            }
            Err(e) => match fallback {
                Some((this, listed, report)) => {
                    log::warn!("{listed}'s song won't start ({e}); playing the music video");
                    let _ = tx.send(Resp::PlayingAs { generation, listed: listed.clone(), song: None });
                    this.request(Req::Play { video_id: listed, generation, at, report, start_paused, song_of: None, song: None, track: None });
                    wake();
                }
                None => {
                    let _ = tx.send(Resp::PlayError { generation, msg: format!("decode: {e}") });
                    wake();
                }
            },
        });
        Ok(())
    }

    pub(super) async fn report_watchtime(&self, id: &str, generation: u64, w: ytm::Watchtime) {
        let epoch = self.api.epoch();
        let status = &self.audio.status;
        let started = std::time::Instant::now();
        let mut flush = w.flush_at.iter().copied();
        let mut due = flush.next().unwrap_or(w.flush_every);
        let pos = || status.position().as_secs_f64();
        let (mut from, mut at) = (pos(), pos());
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            let gone = status.wanted_gen.load(Ordering::Relaxed) != generation;
            if !gone {
                let now = pos();
                if now < at || now > at + 5.0 {
                    from = now;
                }
                at = now;
            }
            let elapsed = started.elapsed().as_secs();
            if !gone && elapsed < due {
                continue;
            }
            if self.api.epoch() != epoch {
                log::debug!("stopped reporting listening time of {id}: the account or channel changed");
                return;
            }
            if at > from || gone {
                if let Err(e) = ytm::report_watchtime(&self.api, &w, from, at, started.elapsed().as_secs_f64(), gone).await {
                    log::warn!("couldn't report listening time of {id}: {e:#}");
                }
                from = at;
            }
            if gone {
                log::debug!("reported listening time of {id}");
                return;
            }
            due = flush.next().unwrap_or(due + w.flush_every);
        }
    }

    pub(super) async fn song_version(&self, listed: &str, song_of: Option<&Track>, wait: std::time::Duration) -> Option<Track> {
        let video = song_of.filter(|t| t.id == listed && t.music_video)?;
        if crate::downloads::path(listed).is_some() {
            return None;
        }
        if let Some(song) = crate::downloads::song_for(listed) {
            return Some(song);
        }
        let start = std::time::Instant::now();
        let premium = match self.api.account() {
            Some(session) => {
                let this = self.clone();
                let check = tokio::spawn(async move { this.is_premium(&session).await });
                tokio::time::timeout(wait, check).await.is_ok_and(|p| p.unwrap_or(false))
            }
            None => false,
        };
        if !premium && (video.duration.is_none() || video.artists.is_empty()) {
            return None;
        }
        let cell = {
            let mut songs = self.songs.lock().unwrap();
            let key = format!("{listed} {premium}");
            match songs.get(&key) {
                Some(cell) => cell.clone(),
                None => {
                    if songs.len() >= 1000 {
                        songs.retain(|_, c| !c.initialized());
                    }
                    songs.entry(key).or_default().clone()
                }
            }
        };
        let (this, video) = (self.clone(), video.clone());
        let lookup = tokio::spawn(async move {
            cell.get_or_try_init(|| async {
                let found = this.look_up_song(&video, premium).await?;
                match &found {
                    Some(s) => log::info!("song version of {} \"{}\": {} \"{}\"", video.id, video.title, s.id, s.title),
                    None => log::info!("song version of {} \"{}\": none found", video.id, video.title),
                }
                anyhow::Ok(found)
            })
            .await
            .cloned()
        });
        match tokio::time::timeout(wait.saturating_sub(start.elapsed()), lookup).await {
            Ok(Ok(Ok(found))) => found,
            Ok(Ok(Err(e))) => {
                log::debug!("song version of {listed}: {e:#}");
                None
            }
            Ok(Err(e)) => {
                log::debug!("song version of {listed}: lookup stopped: {e}");
                None
            }
            Err(_) => {
                log::debug!("song version of {listed}: not found in {wait:?}, playing the music video");
                None
            }
        }
    }

    pub(super) async fn look_up_song(&self, video: &Track, premium: bool) -> anyhow::Result<Option<Track>> {
        if premium {
            match ytm::paired_song(&self.api, &video.id).await {
                Ok(found) => return Ok(found),
                Err(e) => log::debug!("song pair of {}: {e:#}; searching instead", video.id),
            }
        }
        ytm::searched_song(&self.api, video).await
    }

    pub(super) async fn play_station(&self, url: &str, generation: u64) -> anyhow::Result<()> {
        let shared = Shared::new(0, 0, None, stream::Format::File { hint: "mp3", mime: None }, None, false);
        let (reader, hint) = crate::radio::open(self.http(), url, shared.clone()).await?;
        if self.audio.status.wanted_gen.load(Ordering::Relaxed) != generation {
            return Ok(());
        }
        let audio = self.audio.clone();
        tokio::task::spawn_blocking(move || -> anyhow::Result<()> {
            let decoder = rodio::Decoder::builder().with_data(reader).with_seekable(false).with_hint(hint).build()?;
            audio.send(AudioCmd::Start { generation, decoder: crate::audio::TrackDecoder::Live(decoder), shared, at: std::time::Duration::ZERO, start_paused: false });
            Ok(())
        })
        .await?
    }
}
