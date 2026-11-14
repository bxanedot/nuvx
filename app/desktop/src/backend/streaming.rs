use super::*;

impl Backend {
    pub(super) async fn open_stream(&self, video_id: &str, prefetch: bool, track: Option<&Track>) -> anyhow::Result<Arc<Shared>> {
        let id = video_id.to_owned();
        if let Some(shared) = tokio::task::spawn_blocking(move || crate::downloads::open(&id)).await? {
            return Ok(shared);
        }
        let (source, s) = self.resolve(video_id, false, track, true).await?;
        if let Some(data) = s.data {
            return Ok(Shared::from_bytes_with_format(data, s.duration_ms, s.loudness_db, s.format, s.info));
        }
        let shared = Shared::new(s.size, s.duration_ms, s.loudness_db, s.format.clone(), s.info, prefetch);
        self.rt.spawn(stream::download(
            self.http(),
            s.url,
            s.user_agent,
            shared.clone(),
            Some(self.refresher(source, video_id, s.format, track.cloned())),
            s.ranging,
        ));
        Ok(shared)
    }

    pub(super) fn refresher(&self, source: Source, video_id: &str, format: stream::Format, track: Option<Track>) -> stream::Refresh {
        let (this, id) = (self.clone(), video_id.to_owned());
        Arc::new(move || {
            let (this, id, track) = (this.clone(), id.clone(), track.clone());
            let format = format.clone();
            Box::pin(async move {
                let s = this.resolve_from(source, &id, format.clone(), track.as_ref()).await?;
                anyhow::ensure!(s.format == format, "the stream changed format");
                Ok(s.url)
            })
        })
    }

    pub(super) async fn video_offset(&self, source_id: &str, target_id: &str) -> Option<i64> {
        if let Some(offset) = crate::video_alignment::cached_offset(source_id, target_id) {
            return Some(offset);
        }
        let (source, target) = tokio::join!(
            self.resolve_from(Source::VisionOs, source_id, stream::Format::Aac, None),
            self.resolve_from(Source::VisionOs, target_id, stream::Format::Aac, None),
        );
        let (Ok(source), Ok(target)) = (source, target) else {
            log::info!("video alignment unavailable for {source_id} -> {target_id}");
            return None;
        };
        let (source, target) = tokio::join!(self.alignment_audio(source_id, source), self.alignment_audio(target_id, target));
        let (Some(source), Some(target)) = (source, target) else {
            log::info!("video alignment audio unavailable for {source_id} -> {target_id}");
            return None;
        };
        let aligned = tokio::task::spawn_blocking(move || {
            let source = crate::video_alignment::decode_envelope(source.0, source.1, source.2).ok()?;
            let target = crate::video_alignment::decode_envelope(target.0, target.1, target.2).ok()?;
            crate::video_alignment::offset_ms(&source, &target)
        })
        .await
        .ok()
        .flatten();
        if let Some(offset) = aligned {
            crate::video_alignment::remember_offset(source_id, target_id, offset);
            log::info!("video alignment for {source_id} -> {target_id}: {offset} ms");
            Some(offset)
        } else {
            log::info!("video alignment not confident for {source_id} -> {target_id}");
            None
        }
    }

    async fn alignment_audio(&self, video_id: &str, s: ResolvedStream) -> Option<(Vec<u8>, u64, stream::Format)> {
        const MAX_BYTES: u64 = 32 * 1024 * 1024;
        let ResolvedStream { url, size, duration_ms, user_agent, format, ranging, .. } = s;
        if size == 0 || size > MAX_BYTES || !matches!(format, stream::Format::Aac | stream::Format::Opus) {
            return None;
        }
        let shared = Shared::new(size, duration_ms, None, format.clone(), None, false);
        let result = tokio::time::timeout(
            std::time::Duration::from_secs(25),
            stream::download(
                self.http(),
                url,
                user_agent,
                shared.clone(),
                Some(self.refresher(Source::VisionOs, video_id, format.clone(), None)),
                ranging,
            ),
        )
        .await;
        if result.is_err() {
            shared.cancel();
            return None;
        }
        shared.bytes().map(|bytes| (bytes, duration_ms, format))
    }

    pub(super) async fn download(&self, t: &Track, for_video: Option<String>) -> anyhow::Result<PathBuf> {
        static SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(3);
        let _slot = SLOTS.acquire().await?;
        let (source, s) = self.resolve(&t.id, true, Some(t), false).await?;
        log::info!("download {}: {} bytes from {source:?}", t.id, s.size);
        let shared = Shared::new(s.size, s.duration_ms, s.loudness_db, s.format.clone(), s.info, false);
        stream::download(self.http(), s.url, s.user_agent, shared.clone(), Some(self.refresher(source, &t.id, s.format, None)), s.ranging).await;
        if let Some(e) = shared.error() {
            anyhow::bail!("{e}");
        }
        let audio = shared.bytes().ok_or_else(|| anyhow::anyhow!("the download stopped early"))?;
        let cover = match model::pick(&t.thumbs, 120).map(|u| sized(u, 1200)) {
            Some(url) => async { self.http().get(url).send().await?.error_for_status()?.bytes().await }.await.ok().map(|b| b.to_vec()),
            None => None,
        };
        let (t, duration_ms, loudness) = (t.clone(), shared.duration_ms, shared.loudness_db);
        tokio::task::spawn_blocking(move || crate::downloads::save(&t, &audio, duration_ms, loudness, cover, for_video)).await?
    }

    pub(super) async fn resolve(&self, video_id: &str, best: bool, track: Option<&Track>, allow_hires: bool) -> anyhow::Result<(Source, ResolvedStream)> {
        let reverted = self.reverted.lock().unwrap().contains(video_id);
        let hires = if allow_hires && !reverted { self.hires.read().unwrap().clone() } else { Vec::new() };
        if let Some(track) = track
            && !hires.is_empty()
        {
            let started = std::time::Instant::now();
            match tokio::time::timeout(HIRES_WAIT, self.from_hires(&hires, track)).await {
                Ok(Some(s)) => {
                    if let Some(info) = s.info.as_ref() {
                        log::info!("{video_id}: {} audio from {}", info.badge(), info.source);
                    }
                    return Ok((Source::HiRes, s));
                }
                Ok(None) => log::info!("{video_id}: no hi-res source has a copy ({:.1}s)", started.elapsed().as_secs_f32()),
                Err(_) => log::info!("{video_id}: the hi-res sources gave no answer in {HIRES_WAIT:?}"),
            }
        }
        let hq = best || self.hq.load(Ordering::Relaxed);
        if best
            && !self.jsc.ready()
            && let Some(session) = self.api.account()
            && self.is_premium(&session).await
            && let Err(e) = self.jsc.warm(&self.http()).await
        {
            log::warn!("solver warm-up failed: {e:#}");
        }
        let solver_ready = self.jsc.ready();
        if hq && !solver_ready && self.api.account().is_some() && !self.known_not_premium() {
            log::info!("{video_id}: solver not ready yet, standard quality for now");
            self.warm_signed_in();
        }
        if hq
            && solver_ready
            && let Some(session) = self.api.account()
            && self.is_premium(&session).await
        {
            match self.resolve_from(Source::WebMusic, video_id, stream::Format::Aac, None).await {
                Ok(s) => return Ok((Source::WebMusic, s)),
                Err(e) => log::warn!("high quality stream failed for {video_id} ({e:#}); using standard quality"),
            }
        }
        let opus = if best { stream::Format::Aac } else { stream::Format::Opus };
        match self.resolve_from(Source::VisionOs, video_id, opus, None).await {
            Ok(s) => Ok((Source::VisionOs, s)),
            Err(e) if needs_account(&e) => {
                log::info!("{video_id} needs a signed-in player ({e:#})");
                let session = self.api.account().ok_or_else(|| anyhow::anyhow!("sign in to play this song"))?;
                if self.is_premium(&session).await {
                    match self.resolve_from(Source::WebMusic, video_id, stream::Format::Aac, None).await {
                        Ok(s) => return Ok((Source::WebMusic, s)),
                        Err(e) => log::warn!("web music player failed ({e:#}); using yt-dlp"),
                    }
                }
                Ok((Source::YtDlp, self.resolve_from(Source::YtDlp, video_id, stream::Format::Aac, None).await?))
            }
            Err(e) => Err(e),
        }
    }

    pub(super) async fn resolve_from(&self, source: Source, video_id: &str, format: stream::Format, track: Option<&Track>) -> anyhow::Result<ResolvedStream> {
        let plain = |s: innertube::Stream, ua: &str| ResolvedStream {
            url: s.url,
            size: s.size,
            duration_ms: s.duration_ms,
            user_agent: ua.to_owned(),
            loudness_db: s.loudness_db,
            format: stream::Format::from_opus(s.opus),
            info: None,
            ranging: stream::Ranging::Query,
            data: None,
        };
        Ok(match source {
            Source::VisionOs => plain(self.visionos_stream(video_id, format == stream::Format::Opus).await?, innertube::UA),
            Source::WebMusic => {
                let session = self.api.account().ok_or_else(|| anyhow::anyhow!("signed out"))?;
                plain(session.audio_stream(&self.http(), &self.jsc, video_id).await?, webmusic::UA)
            }
            Source::YtDlp => {
                let session = self.api.account().ok_or_else(|| anyhow::anyhow!("sign in to play this song"))?;
                let s = ytdlp::resolve(&self.http(), &self.data_dir, &session.cookie, video_id).await?;
                self.api.keep(&session, s.replaced.iter().map(String::as_str));
                ResolvedStream { url: s.url, size: s.size, duration_ms: s.duration_ms, user_agent: s.user_agent, loudness_db: None, format: stream::Format::Aac, info: None, ranging: stream::Ranging::Query, data: None }
            }
            Source::HiRes => {
                let track = track.ok_or_else(|| anyhow::anyhow!("no song to look up"))?;
                let sources = self.hires.read().unwrap().clone();
                anyhow::ensure!(!sources.is_empty(), "no hi-res source is set");
                self.from_hires(&sources, track).await.ok_or_else(|| anyhow::anyhow!("no hi-res source has a copy"))?
            }
        })
    }

    pub(super) async fn from_hires(&self, sources: &[Arc<hires::Client>], track: &Track) -> Option<ResolvedStream> {
        let atmos = self.atmos.load(Ordering::Relaxed);
        let mut tasks = tokio::task::JoinSet::new();
        for client in sources {
            let (http, track, client) = (self.http(), track.clone(), client.clone());
            tasks.spawn(async move {
                let found = client.find(&http, &track, atmos).await;
                (client, found)
            });
        }
        while let Some(joined) = tasks.join_next().await {
            match joined {
                Ok((_, Ok(Some(found)))) => {
                    return Some(ResolvedStream {
                        url: found.url,
                        size: found.size,
                        duration_ms: found.duration_ms,
                        user_agent: hires::USER_AGENT.to_owned(),
                        loudness_db: None,
                        format: found.format,
                        info: Some(found.info),
                        ranging: stream::Ranging::Header,
                        data: found.data,
                    });
                }
                Ok((client, Ok(None))) => log::info!("hi-res {}: no copy of \"{}\"", client.host(), track.title),
                Ok((client, Err(e))) => log::info!("hi-res {}: {e:#}", client.host()),
                Err(e) => log::info!("hi-res: a lookup stopped: {e}"),
            }
        }
        None
    }
}
