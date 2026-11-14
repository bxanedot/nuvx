use super::*;

impl Backend {
    pub(super) async fn handle(&self, req: Req) -> Option<Resp> {
        let api = &*self.api;
        let me = self.is_logged_in();
        let (what, quiet) = (req.name(), req.quiet());
        let res: anyhow::Result<Option<Resp>> = async {
            Ok(Some(match req {
                Req::Home(params) => Resp::Home(ytm::home(api, params.as_deref(), me).await?),
                Req::HomeMore(token) => Resp::HomeMore(ytm::home_more(api, &token, me).await?),
                Req::MoreQuickPicks(seed) => {
                    let tracks = ytm::radio(api, &seed, me, 0).await?;
                    Resp::QuickPicksMore { seed, tracks }
                }
                Req::Explore(preferred_artists) => self.explore(&preferred_artists, me).await?,
                Req::Moods => {
                    let mut sections = ytm::moods(api, me).await?;
                    let _ = self.tx.send(Resp::Moods(sections.clone()));
                    self.wake();
                    ytm::mood_art(api, &mut sections, me).await;
                    Resp::Moods(sections)
                }
                Req::Mood { browse_id, .. } if browse_id == ytm::PODCASTS => Resp::Mood(ytm::podcasts(api, me).await?),
                Req::Mood { browse_id, params } => Resp::Mood(ytm::mood_page(api, &browse_id, params.as_deref(), me).await?),
                Req::RadioCountries => Resp::RadioCountries(crate::radio::countries(&self.http()).await.map_err(|e| format!("{e:#}"))),
                Req::RadioStations { key, country, query } => {
                    let stations = crate::radio::stations(&self.http(), country.as_deref(), query.as_deref()).await.map_err(|e| format!("{e:#}"));
                    Resp::RadioStations { key, stations }
                }
                Req::RadioClick(id) => {
                    crate::radio::count_click(&self.http(), &id).await;
                    return Ok(None);
                }
                Req::Search(query) => self.search(query, me).await?,
                Req::YoutubeSearch(query) => Resp::YoutubeSearch { tracks: ytm::youtube_search(api, &query).await.map_err(|e| format!("{e:#}")), query },
                Req::Suggest { query, round } => {
                    let terms = ytm::suggestions(api, &query, me).await?.into_iter().take(8).collect();
                    Resp::Suggest { query, round, terms }
                }
                Req::ForgetSearch(token) => {
                    ytm::forget_search(api, &token).await?;
                    Resp::SearchForgotten
                }
                Req::Album { id, load } => Resp::Collection { load, c: ytm::album(api, &id).await?, more: false },
                Req::Playlist { id, load, stream: false } => Resp::Collection { load, c: ytm::playlist(api, &id, me, None).await?, more: false },
                Req::Playlist { id, load, stream: true } => self.stream_playlist(id, load, me).await?,
                Req::Artist(id) => Resp::Artist(ytm::artist(api, &id).await?),
                Req::Library => self.library().await?,
                Req::History => Resp::History(ytm::history(api).await?),
                Req::Uploads => Resp::Uploads(ytm::uploads(api).await?),
                Req::Account => {
                    let (name, photo) = ytm::account(api).await?;
                    Resp::Account { name, photo }
                }
                Req::Channels => {
                    let epoch = api.epoch();
                    let list = ytm::channels(api).await?;
                    Resp::Channels(Channels { list, current: api.account().and_then(|a| a.channel().map(str::to_owned)), epoch })
                }
                Req::SwitchChannel { page_id, epoch } => match self.switch_channel(page_id.clone(), epoch).await {
                    Ok(epoch) => Resp::SwitchedChannel { page_id, epoch, error: None },
                    Err(e) => {
                        log::warn!("switching channel failed: {e:#}");
                        Resp::SwitchedChannel { page_id, epoch, error: Some(e.to_string()) }
                    }
                },
                Req::ServerQueue => match ytm::server_queue(api).await? {
                    Some(q) => Resp::ServerQueue(q),
                    None => return Ok(None),
                },
                Req::Upload(files) => self.upload(&files).await?,
                Req::Lyrics(track) => {
                    let video_id = track.id.clone();
                    Resp::Lyrics { lyrics: self.lyrics_for(&track).await, video_id }
                }
                Req::Segments { video_id } => {
                    let segments = crate::sponsorblock::segments(&self.http(), &video_id).await?;
                    Resp::Segments { video_id, segments }
                }
                Req::LyricsExtras { video_id, source, lines, lang, target } => {
                    let key = format!("{video_id}_{source}");
                    let Some(extras) = self.translate.extras(&key, &lines, lang.as_deref(), &target).await else { return Ok(None) };
                    Resp::LyricsExtras { video_id, source, target, extras }
                }
                Req::Art { key, url } => {
                    let url = model::sized(&url, 2 * crate::art::SIZE);
                    let bytes = self.http().get(&url).send().await?.error_for_status()?.bytes().await?;
                    let art = tokio::task::spawn_blocking(move || crate::art::process(&bytes)).await??;
                    Resp::Art { key, art }
                }
                Req::AppleArtist { id, name } => {
                    let info = crate::apple::artist(&self.http(), &name).await;
                    log::info!("apple artist '{name}': {}", match &info {
                        Some(a) => match &a.logo {
                            Some(l) => format!("'{}' wordmark {}x{}", a.name, l.w, l.h),
                            None => format!("'{}' in type (no wordmark)", a.name),
                        },
                        None => "no answer".to_owned(),
                    });
                    Resp::AppleArtist { id, info }
                }
                Req::SongCover { track } => {
                    let premium = match self.api.account() {
                        Some(session) => self.is_premium(&session).await,
                        None => false,
                    };
                    let song = match premium {
                        true => ytm::paired_song(&self.api, &track.id).await.ok().flatten(),
                        false => ytm::cover_song(&self.api, &track).await.ok().flatten(),
                    };
                    log::info!("cover for '{}': {}", track.title, song.as_ref().map_or("none".to_owned(), |s| format!("'{}'", s.title)));
                    Resp::SongCover { id: track.id, song }
                }
                Req::Canvas { key, album, artist, title } => {
                    let http = self.http();
                    let album = Some(album.as_str()).filter(|a| !a.is_empty());
                    let apple = match (title.as_deref(), album) {
                        (Some(t), _) => crate::apple::song_motion(&http, t, &artist, album).await,
                        (None, Some(a)) => crate::apple::album_motion(&http, a, &artist).await,
                        (None, None) => None,
                    };
                    let url = match apple.and_then(|m| m.square) {
                        Some(url) => Some(url),
                        None => match (title.as_deref(), album) {
                            (Some(t), _) => crate::tidal::cover(&http, t, &artist, album, false).await,
                            (None, Some(a)) => crate::tidal::cover(&http, a, &artist, None, true).await,
                            (None, None) => None,
                        },
                    };
                    log::info!("canvas for '{}' by '{artist}': {}", title.as_deref().or(album).unwrap_or_default(), url.as_deref().unwrap_or("none"));
                    Resp::Canvas { key, url }
                }
                Req::Video { id, audio_id, height } => {
                    let (path, offset_ms) = tokio::join!(
                        self.video_file(&id, height),
                        self.video_offset(&audio_id, &id),
                    );
                    let video = path
                        .inspect_err(|e| log::warn!("video {id}: {e:#}"))
                        .ok()
                        .zip(offset_ms);
                    Resp::Video { id, audio_id, video }
                }
                Req::VideoOf { generation, track } => {
                    let video = ytm::video_of(&self.api, &track).await?;
                    log::info!("video of '{}': {}", track.title, video.as_ref().map_or("none", |v| v.id.as_str()));
                    Resp::VideoOf { generation, listed: track.id, video }
                }
                Req::ArtistSignature { key, endpoint, name } => {
                    let signature = crate::artist_signature::lookup(&self.http(), &endpoint, &name).await;
                    log::info!("artist signature: {}", match &signature {
                        Some(s) => format!("found one for '{name}' ({}x{})", s.width, s.height),
                        None => format!("none for '{name}'"),
                    });
                    Resp::ArtistSignature { key, signature }
                }
                Req::Radio { seed, song_of } => {
                    let song = self.song_version(&seed, song_of.as_ref(), PREFETCH_SONG_WAIT).await;
                    let from = song.as_ref().map_or(&seed, |s| &s.id);
                    let mut tracks = ytm::radio(api, from, false, 50).await?;
                    tracks.retain(|t| t.id != *from);
                    Resp::Radio { seed, tracks }
                }
                Req::PlayCollection { target } => {
                    let tracks = match &target {
                        Target::Album(id) => ytm::album(api, id).await?.tracks,
                        Target::Playlist(id) => ytm::playlist(api, id, me, None).await?.tracks,
                        Target::Artist(id) => ytm::artist(api, id).await?.top,
                        Target::Song(t) => vec![t.clone()],
                    };
                    Resp::PlayQueue { target, tracks }
                }
                Req::Download { track: t, for_video } => {
                    let path = self.download(&t, for_video).await?;
                    Resp::Downloaded { title: t.title, path }
                }
                Req::DownloadAll { name, targets } => {
                    let mut tracks = Vec::new();
                    for target in targets {
                        let c = match &target {
                            model::Target::Album(id) => ytm::album(api, id).await,
                            model::Target::Playlist(id) => ytm::playlist(api, id, me, None).await,
                            _ => continue,
                        };
                        match c {
                            Ok(c) => tracks.extend(c.tracks),
                            Err(e) => log::warn!("download all {target:?}: {e:#}"),
                        }
                    }
                    let mut seen = std::collections::HashSet::new();
                    tracks.retain(|t| crate::downloads::path(&t.id).is_none() && seen.insert(t.id.clone()));
                    let results = futures_util::future::join_all(tracks.iter().map(|t| self.download(t, None))).await;
                    let failed = results.iter().filter(|r| r.is_err()).count();
                    Resp::DownloadedAll { name, done: results.len() - failed, failed }
                }
                Req::Play { video_id, generation, at, report, start_paused, song_of, song, track } => match self.start_playback(&video_id, song_of.as_ref(), song, track.as_ref(), generation, at, report, start_paused).await {
                    Ok(()) => return Ok(None),
                    Err(e) => Resp::PlayError { generation, msg: format!("{e:#}") },
                },
                Req::Prefetch { video_id, song_of, track } => {
                    self.prefetch(video_id, song_of, track).await?;
                    return Ok(None);
                }
                Req::Like { video_id, like } => {
                    ytm::set_like(api, &video_id, like).await?;
                    Resp::Liked { video_id, like }
                }
                Req::LikeMany { video_ids, like } => self.like_many(video_ids, like).await?,
                Req::Dislike(video_id) => {
                    ytm::dislike(api, &video_id).await?;
                    Resp::Liked { video_id, like: false }
                }
                Req::CreatePlaylist { title, video_ids } => {
                    let id = match video_ids.as_slice() {
                        [] => ytm::create_playlist(api, &title, None).await?,
                        [one] => ytm::create_playlist(api, &title, Some(one)).await?,
                        many => ytm::create_playlist_with(api, &title, many).await?,
                    };
                    Resp::PlaylistCreated { id, songs: video_ids.len() }
                }
                Req::EditPlaylist { id, title, description, privacy } => {
                    ytm::edit_playlist(api, &id, &title, &description, privacy.as_deref()).await?;
                    Resp::PlaylistChanged { id, message: "Playlist updated" }
                }
                Req::AddToPlaylist { playlist_id, video_ids } => {
                    ytm::add_videos(api, &playlist_id, &video_ids).await?;
                    Resp::PlaylistChanged { id: playlist_id, message: "Added to playlist" }
                }
                Req::RemoveFromPlaylist { playlist_id, items } => {
                    ytm::remove_videos(api, &playlist_id, &items).await?;
                    Resp::PlaylistChanged { id: playlist_id, message: "Removed from playlist" }
                }
                Req::DeletePlaylist(id) => {
                    ytm::delete_playlist(api, &id).await?;
                    Resp::PlaylistChanged { id, message: "Playlist deleted" }
                }
                Req::Collaboration(id) => Resp::Collaboration(ytm::collaboration(api, &id).await?),
                Req::SetCollaboration { info, change } => {
                    ytm::set_collaboration(api, &info, change).await?;
                    Resp::Collaboration(ytm::collaboration(api, &info.playlist_id).await?)
                }
                Req::SaveCollection { id, save } => {
                    ytm::save_collection(api, &id, save).await?;
                    Resp::PlaylistChanged { id, message: if save { "Saved to library" } else { "Removed from library" } }
                }
                Req::SetCookie(input) => {
                    self.set_cookie(&input).await?;
                    *self.premium.lock().await = Err(None);
                    self.warm_signed_in();
                    log::info!("signed in");
                    Resp::LoggedIn(true)
                }
                Req::Logout => {
                    self.forget_session().await;
                    Resp::LoggedIn(false)
                }
            }))
        }
        .await;
        res.unwrap_or_else(|e| {
            log::warn!("{what} failed: {e:#}");
            (!quiet).then(|| Resp::Error(format!("{what} failed: {e}")))
        })
    }
}
