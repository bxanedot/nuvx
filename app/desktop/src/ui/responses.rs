mod handlers;

use std::collections::HashSet;
use std::time::Duration;

use souvlaki::MediaControlEvent;

use crate::audio::{AudioCmd, AudioEvent};
use crate::backend::{Req, Resp};
use crate::model::{ShelfKind, Track};

use super::{Action, App, Dialog, HomeState, LibTab, MixFrom, Page, PlayingAs, Repeat, dev_var, shuffle};

impl App {
    pub(super) fn pump(&mut self, now: f64) {
        let cover = self.media_cover.lock().unwrap().take();
        if let Some((id, file)) = cover
            && self.current().is_some_and(|t| t.id == id)
            && self.mix_hold().is_none()
            && let Some(track) = self.playing_track().cloned()
            && let Some(m) = &mut self.media
        {
            m.set_track(&track.title, &track.artist_line(), track.album.as_ref().map(|a| a.name.as_str()), Some(&file), track.duration);
        }
        while let Ok(r) = self.ch.resp_rx.try_recv() {
            self.on_resp(r, now);
        }
        while let Ok(ev) = self.ch.audio_rx.try_recv() {
            match ev {
                AudioEvent::Finished { generation } if generation == self.generation && self.jam.guest() => {}
                AudioEvent::Finished { generation } if generation == self.generation => {
                    if self.queue.repeat == Repeat::One {
                        self.play_current();
                    } else {
                        self.next();
                    }
                }
                AudioEvent::Finished { .. } => {}
                AudioEvent::Ending { generation } if generation == self.generation && !self.jam.guest() && self.queue.repeat != Repeat::One => {
                    if let Some(from) = self.playing_track().cloned() {
                        let total = self
                            .audio
                            .status
                            .duration()
                            .map(|d| d.as_secs_f32())
                            .or_else(|| from.duration.map(|d| d as f32))
                            .unwrap_or(0.0);
                        let mix = self.audio.status.mix_secs(Some(std::time::Duration::from_secs_f32(total)));
                        if total > 0.0 && mix > 0.5 {
                            self.pending_mix = Some(MixFrom { track: from, total, mix, started: false, released: false });
                        }
                    }
                    let before = self.playing_id().map(str::to_owned);
                    self.handoff = true;
                    self.next();
                    self.handoff = false;
                    if self.playing_id().map(str::to_owned) != before {
                        self.mix_from = self.pending_mix.take();
                    } else {
                        self.pending_mix = None;
                    }
                }
                AudioEvent::Ending { .. } => {}
                AudioEvent::Interrupted { generation, at, reason } if generation == self.generation => self.recover(at, &reason),
                AudioEvent::Interrupted { .. } => {}
                AudioEvent::Error(e) => self.toast(e),
            }
        }
        let released = self.mix_from.as_ref().is_some_and(|h| h.released);
        let switched = self.watch_mix();
        let over = self.mix_over();
        if (switched || over) && !released {
            self.release_mix();
        }
        if over {
            self.mix_from = None;
        }
        while let Ok(ev) = self.ch.media_rx.try_recv() {
            match ev {
                MediaControlEvent::Play | MediaControlEvent::Pause if self.jam.guest() => self.toggle(),
                MediaControlEvent::Play if self.resume_at.is_some() => self.toggle(),
                MediaControlEvent::Play => self.audio.send(AudioCmd::Resume),
                MediaControlEvent::Pause | MediaControlEvent::Stop => self.audio.send(AudioCmd::Pause),
                MediaControlEvent::Toggle => self.toggle(),
                MediaControlEvent::Next => self.next(),
                MediaControlEvent::Previous => self.prev(),
                _ => {}
            }
        }
        if self.buffering && self.playing() {
            self.buffering = false;
            self.skip_streak = 0;
            self.prefetch_next();
            if self.generation == 1 {
                if let Some(s) = std::env::var("NUVX_SEEK").ok().and_then(|v| v.parse::<f32>().ok()) {
                    self.audio.send(AudioCmd::Seek(Duration::from_secs_f32(s)));
                }
                if let Some((s, d)) = dev_var("NUVX_SEEK_LATER").and_then(|v| v.split_once('@')).and_then(|(s, d)| Some((s.parse::<f32>().ok()?, d.parse::<f32>().ok()?))) {
                    let audio = self.audio.clone();
                    std::thread::spawn(move || {
                        std::thread::sleep(Duration::from_secs_f32(d));
                        audio.send(AudioCmd::Seek(Duration::from_secs_f32(s)));
                    });
                }
            }
        }
        if self.suggest_due.is_some_and(|d| now >= d) {
            self.suggest_due = None;
            let q = self.search_text.trim().to_owned();
            if self.forgets_pending == 0 && (!q.is_empty() || self.logged_in) {
                self.backend.request(Req::Suggest { query: q, round: self.suggest_round });
            }
        }
    }

    pub(super) fn expect_search_click(&mut self, query: &str) {
        if matches!(&self.page, Page::Search(q) if q == query) {
            self.search_pending = true;
        }
    }

    fn search_click_entry(&self, a: &Action) -> Option<super::RecentSearch> {
        let r = self.search.as_ref()?;
        let best = |thumbs: &[crate::model::Thumb]| thumbs.iter().max_by_key(|t| t.width).map(|t| t.url.clone());
        let song = |t: &Track| {
            Some(super::RecentSearch {
                query: r.query.clone(),
                target: Some(crate::model::Target::Song(t.clone())),
                title: t.title.clone(),
                subtitle: format!("Song • {}", t.artist_line()),
                thumb: best(&t.thumbs),
                round: false,
            })
        };
        let card = |target: &crate::model::Target| {
            let c = r.artists.iter().chain(&r.albums).chain(&r.playlists).find(|c| c.target == *target)?;
            Some(super::RecentSearch {
                query: r.query.clone(),
                target: Some(target.clone()),
                title: c.title.clone(),
                subtitle: c.subtitle.clone(),
                thumb: best(&c.thumbs),
                round: c.round,
            })
        };
        match a {
            Action::Open(crate::model::Target::Song(t)) | Action::Radio(t) => song(t),
            Action::Open(target) | Action::PlayCollection(target, _) => card(target),
            Action::Play(tracks, i) => tracks.get(*i).and_then(song),
            _ => None,
        }
    }

    pub(super) fn remember_search_click(&mut self, a: &Action) {
        if !self.search_pending || !matches!(self.page, Page::Search(_)) {
            return;
        }
        let Some(entry) = self.search_click_entry(a) else { return };
        self.search_pending = false;
        self.recent_searches.retain(|s| {
            s.target.as_ref() != entry.target.as_ref() && !(s.target.is_none() && s.query.eq_ignore_ascii_case(&entry.query))
        });
        self.recent_searches.insert(0, entry);
        self.recent_searches.truncate(8);
    }

    fn on_resp(&mut self, r: Resp, now: f64) {
        match r {
            Resp::Home(page) => self.on_home(page),
            Resp::HomeMore(page) => {
                if let Some(h) = &mut self.home {
                    h.shelves.extend(page.shelves);
                    h.continuation = page.continuation;
                    h.loading_more = None;
                }
            }
            Resp::Moods(m) => self.moods = Some(m),
            Resp::RadioCountries(c) => self.radio.set_countries(c),
            Resp::RadioStations { key, stations } => self.radio.set_stations(key, stations),
            Resp::Mood(m) => {
                self.mood = Some(m);
                self.loading = false;
            }
            Resp::Explore(e) => {
                self.explore = Some(e);
                self.loading = false;
            }
            Resp::YoutubeSearch { query, tracks } => self.youtube_results = Some((query, tracks)),
            Resp::Search(r) => {
                if self.smoke.as_ref() == Some(&r.query) {
                    self.smoke = None;
                    if !r.tracks.is_empty() {
                        log::info!("smoke: playing {}", r.tracks[0].title);
                        self.play_list(r.tracks.clone(), 0);
                    }
                }
                self.expect_search_click(&r.query);
                self.search = Some(r);
                self.loading = false;
            }
            Resp::SearchForgotten => {
                self.forgets_pending = self.forgets_pending.saturating_sub(1);
                if self.forgets_pending == 0 {
                    self.suggest_due = Some(now);
                }
            }
            Resp::Suggest { query, round, terms } => {
                if round == self.suggest_round && self.forgets_pending == 0 {
                    self.suggest_cache.retain(|(q, _)| q != &query);
                    self.suggest_cache.push_back((query.clone(), terms.clone()));
                    if self.suggest_cache.len() > 32 {
                        self.suggest_cache.pop_front();
                    }
                    if self.search_text.trim() == query {
                        self.suggestions = (query, terms);
                        self.suggest_sel = None;
                    }
                }
            }
            Resp::Collection { load, c, more } => self.collection_in(load, c, more),
            Resp::CollectionMore { id, load, tracks, set_ids, more } => self.collection_more(id, load, tracks, set_ids, more),
            Resp::Artist(a) => {
                if matches!(&self.page, Page::Artist(id) if *id != a.id) {
                    return;
                }
                if self.page == Page::Artist(a.id.clone()) {
                    self.loading = false;
                }
                let apple_key = format!("artist:{}", a.id);
                let answered = self.apple_artists.get(&a.id).is_some_and(Option::is_some);
                let cooling = self.apple_missed.get(&apple_key).is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(120));
                if !answered && !cooling {
                    let id = a.id.clone();
                    let name = a.name.clone();
                    if self.apple_waiting.insert(apple_key) {
                        self.backend.request(Req::AppleArtist { id, name });
                    }
                }
                self.artist = Some(a);
            }
            Resp::Library(mut l) => {
                if l.playlists_failed {
                    l.playlists = self.library.as_ref().map(|o| o.playlists.clone()).or_else(|| self.sidebar_cache.clone()).unwrap_or_default();
                }
                if l.albums_failed {
                    l.albums = self.library.as_ref().map(|o| o.albums.clone()).unwrap_or_default();
                }
                self.library = Some(l);
                self.loading = false;
            }
            Resp::History(h) => {
                self.history = Some(h);
                self.loading = false;
            }
            Resp::Uploads(t) => {
                self.uploads = Some(t);
                if self.page == Page::Library(LibTab::Uploads) {
                    self.loading = false;
                }
            }
            Resp::Downloaded { title, path } => {
                log::info!("downloaded to {}", path.display());
                self.toast(format!("Downloaded \u{201c}{title}\u{201d}"));
            }
            Resp::DownloadedAll { name, done, failed } => {
                let songs = |n: usize| if n == 1 { "1 song".to_owned() } else { format!("{n} songs") };
                self.toast(match (done, failed) {
                    (0, 0) => format!("\u{201c}{name}\u{201d} is already downloaded"),
                    (d, 0) => format!("Downloaded {} from \u{201c}{name}\u{201d}", songs(d)),
                    (d, f) => format!("Downloaded {}; {} couldn't be downloaded", songs(d), songs(f)),
                });
            }
            Resp::Uploaded { done, failed } => {
                let songs = |n: usize| if n == 1 { "1 song".to_owned() } else { format!("{n} songs") };
                self.toast(match (done, failed.len()) {
                    (_, 0) => format!("Uploaded {}. It can take a minute for YouTube Music to process them.", songs(done)),
                    (0, _) => format!("Upload failed: {}", failed.join(", ")),
                    _ => format!("Uploaded {}; failed: {}", songs(done), failed.join(", ")),
                });
            }
            Resp::Lyrics { video_id, lyrics } => {
                if self.shown().is_some_and(|t| t.id == video_id) {
                    let available = self.lyric_prefs.available(&lyrics);
                    let shown = self.lyric_prefs.choose(&video_id, &lyrics);
                    let missing: Vec<&str> = self.lyric_prefs.order.iter().filter(|(p, on)| *on && !available.contains(p)).map(|(p, _)| p.key()).collect();
                    log::info!(
                        "lyrics: showing {} ({}/{}); available: {}; no lyrics from: {}",
                        shown.map_or("nothing", |p| p.key()),
                        shown.and_then(|p| available.iter().position(|x| *x == p)).map_or(0, |i| i + 1),
                        available.len(),
                        available.iter().map(|p| p.key()).collect::<Vec<_>>().join(", "),
                        missing.join(", ")
                    );
                    self.lyrics = Some((video_id, lyrics));
                }
            }
            Resp::Segments { video_id, segments } => {
                if self.playing_id() == Some(video_id.as_str()) {
                    log::info!("sponsorblock: {} segment(s) for {video_id}", segments.len());
                    self.segments = Some((video_id, segments));
                }
            }
            Resp::LyricsExtras { video_id, source, target, extras } => {
                if self.shown().is_some_and(|t| t.id == video_id) {
                    self.lyrics_extras = Some((video_id, source, target, extras));
                }
            }
            Resp::Art { key, art } => {
                if self.mix_hold().is_none() && self.current_id() == Some(key.as_str()) {
                    self.accent = art.accent;
                    if let Some(bg) = &self.bg {
                        bg.set_art(art);
                    }
                    self.art_key = Some(key);
                    self.art_changed = now;
                }
            }
            Resp::AppleArtist { id, info } => {
                let key = format!("artist:{id}");
                self.apple_waiting.remove(&key);
                match info {
                    Some(artwork) => {
                        self.apple_missed.remove(&key);
                        self.apple_artists.insert(id, Some(artwork));
                    }
                    None => {
                        self.apple_artists.remove(&id);
                        self.apple_missed.insert(key, std::time::Instant::now());
                    }
                }
            }
            Resp::ArtistSignature { key, signature } => {
                self.signature_waiting.remove(&key);
                match signature {
                    Some(s) => {
                        self.signature_missed.remove(&key);
                        self.signature_images.insert(key, (s.width, s.height, s.rgba));
                    }
                    None => {
                        self.signature_missed.insert(key, std::time::Instant::now());
                    }
                }
            }
            Resp::SongCover { id, song } => {
                if song.is_some() {
                    let current = self.current_id() == Some(id.as_str());
                    self.song_covers.insert(id, song);
                    if current && let Some(t) = self.shown().cloned() {
                        self.art_key = None;
                        self.request_art();
                        self.show_in_media(&t);
                    }
                }
            }
            Resp::Video { id, path } => {
                if let Some((v, state)) = &mut self.video
                    && *v == id
                {
                    *state = Some(path.map_or(super::VideoState::Failed, super::VideoState::Saved));
                }
            }
            Resp::VideoOf { generation, listed, video } => {
                if generation == self.generation && self.current_id() == Some(listed.as_str()) {
                    self.video_of = Some((listed, video.clone()));
                    if std::mem::take(&mut self.video_wanted) {
                        self.play_video_version();
                    } else if let Some(v) = video {
                        self.backend.request(Req::Prefetch { video_id: v.id.clone(), song_of: None, track: Some(v.clone()) });
                        self.backend.request(Req::Video { id: v.id, height: self.video_height });
                    }
                }
            }
            Resp::Canvas { key, url } => {
                self.apple_waiting.remove(&key);
                match url {
                    Some(url) => {
                        self.apple_missed.remove(&key);
                        self.apple_covers.insert(key, Some(url));
                    }
                    None => {
                        self.apple_missed.insert(key, std::time::Instant::now());
                    }
                }
            }
            Resp::Radio { seed, tracks } => {
                self.radio_pending = false;
                let was_at_end = self.queue.add_radio(&seed, tracks);
                if was_at_end && self.queue.index.is_some() && !self.buffering && !self.playing() {
                    self.next();
                } else if !self.buffering {
                    self.prefetch_next();
                }
            }
            Resp::PlayQueue { target, mut tracks } => {
                if let Some((p, shuffled)) = &self.play_pending
                    && *p == target
                {
                    if *shuffled {
                        shuffle(&mut tracks);
                    }
                    self.queue_source = Some(target);
                    self.play_list(tracks, 0);
                }
            }
            Resp::Liked { video_id, like } => {
                self.lists.edits.confirm_likes(std::slice::from_ref(&video_id), &[]);
                if like {
                    self.liked.insert(video_id);
                }
            }
            Resp::QuickPicksMore { seed, tracks } => {
                if let Some(h) = &mut self.home {
                    h.picks_loading = false;
                    if let Some(shelf) = h.shelves.iter_mut().find(|s| s.title == "Quick picks")
                        && let ShelfKind::Songs { tracks: picks, .. } = &mut shelf.kind
                        && picks.iter().any(|t| t.id == seed)
                    {
                        h.picks_expanded = true;
                        let mut seen: HashSet<String> = picks.iter().map(|t| t.id.clone()).collect();
                        picks.extend(tracks.into_iter().filter(|t| seen.insert(t.id.clone())).take(24));
                    }
                }
            }
            Resp::LikeProgress { taken, done, total, like } => {
                if !like {
                    let tracks: Vec<Track> = taken.iter().filter_map(|id| self.unliking.remove(id)).collect();
                    self.set_liked(&tracks, false);
                }
                if done == 0 {
                    self.toast("Waiting for the transfer to finish adding to Liked Music…");
                } else if total > 1 && done < total {
                    let note = if self.backend.api().limited() { " (YouTube Music is limiting requests; waiting)" } else { "" };
                    self.toast(format!("{} {done} of {total} songs…{note}", if like { "Liking" } else { "Removing likes:" }));
                }
            }
            Resp::LikedMany { video_ids, failed, like } => self.on_liked_many(video_ids, failed, like),
            Resp::PlaylistCreated { id, songs } => {
                self.request_library();
                self.toast(match songs {
                    0 => "Playlist created".to_owned(),
                    1 => "Song added to new playlist".to_owned(),
                    n => format!("{n} songs added to new playlist"),
                });
                self.go(Page::Playlist(id));
            }
            Resp::PlaylistChanged { id, message } => self.on_playlist_changed(id, message),
            Resp::Collaboration(c) => {
                if let Some(Dialog::Collaborate { id, info, busy, .. }) = &mut self.dialog
                    && *id == c.playlist_id
                {
                    *info = Some(c);
                    *busy = false;
                }
            }
            Resp::PlayingAs { generation, listed, song } => {
                if generation == self.generation
                    && self.current_id() == Some(listed.as_str())
                    && let Some(track) = self.current().cloned()
                {
                    let shown = match song {
                        Some(song) => {
                            let song = Track { album: song.album.or(track.album.clone()), thumbs: if song.thumbs.is_empty() { track.thumbs.clone() } else { song.thumbs }, ..song };
                            self.version_alternate = Some((track.id.clone(), song.clone()));
                            self.playing_as = Some(PlayingAs { generation, listed: track.id.clone(), song: song.clone() });
                            song
                        }
                        None => {
                            self.version_alternate = None;
                            self.playing_as = None;
                            track
                        }
                    };
                    if self.mix_hold().is_none() {
                        self.show_in_media(&shown);
                        self.refresh_lyrics(shown);
                    }
                    if self.jam.active() {
                        self.play_listed_upload();
                    }
                }
            }
            Resp::PlayError { generation, msg } => self.on_play_error(generation, msg),
            Resp::AudioInfo { generation, info } => {
                if generation == self.generation {
                    self.audio_info = Some((generation, info));
                }
            }
            Resp::ServerQueue(q) => self.adopt_server_queue(q),
            Resp::Account { name, photo } => {
                self.account_name = name.or(self.account_name.take());
                self.account_avatar = photo.or(self.account_avatar.take());
            }
            Resp::Channels(c) => self.channels = Some(c),
            Resp::SwitchedChannel { page_id, epoch, error } => {
                let name = self.switching_to.take().unwrap_or_else(|| "another channel".to_owned());
                match error {
                    Some(e) => self.toast(e),
                    None => {
                        let channels = self.channels.take().map(|c| crate::backend::Channels { current: page_id, epoch, ..c });
                        let asked = self.channels_asked;
                        self.on_resp(Resp::LoggedIn(true), now);
                        (self.channels, self.channels_asked) = (channels, asked);
                        self.toast(format!("Switched to {name}"));
                    }
                }
            }
            Resp::LoggedIn(v) => self.on_logged_in(v, now),
            Resp::Error(e) => self.on_error(e),
        }
    }


}
