use std::sync::atomic::Ordering;
use std::time::Duration;

use crate::audio::AudioCmd;
use crate::backend::{self, Req};
use crate::lyrics::Lyrics;
use crate::model::Track;

use super::queue::QueueFill;
use super::{App, MixFrom, PlayingAs, Repeat, Session, jam, lyric_prefs};

const MIX_SWITCH: f32 = 0.65;

impl App {
    pub fn current(&self) -> Option<&Track> {
        self.queue.current()
    }

    fn lyric_choice(&self) -> Option<&Lyrics> {
        let (id, set) = self.lyrics.as_ref()?;
        set.get(self.lyric_prefs.choose(id, set)?)
    }

    pub(super) fn no_lyrics(&self) -> bool {
        self.lyrics.is_some() && self.lyric_choice().is_none()
    }

    pub(super) fn request_lyric_extras(&mut self) {
        if !(self.show_roman || self.show_trans) {
            return;
        }
        let Some(l) = self.lyric_choice() else { return };
        let Some((id, _)) = &self.lyrics else { return };
        let target = self.lyric_prefs.target.clone();
        let want = (id.clone(), l.provider.key().to_owned(), target.clone());
        if self.extras_asked.as_ref() == Some(&want) {
            return;
        }
        let needs = l.lines.iter().any(|x| {
            !x.instrumental
                && !x.text.trim().is_empty()
                && ((self.show_roman && x.romanization.is_none() && crate::translate::non_latin(&x.text))
                    || (self.show_trans && lyric_prefs::shipped_translation(x, &target).is_none()))
        });
        let req = needs.then(|| Req::LyricsExtras {
            video_id: id.clone(),
            source: l.provider.key().to_owned(),
            lines: l.lines.iter().map(|x| if x.instrumental { String::new() } else { x.text.clone() }).collect(),
            lang: l.language.clone(),
            target,
        });
        self.extras_asked = Some(want);
        if let Some(r) = req {
            self.backend.request(r);
        }
    }

    pub(super) fn current_id(&self) -> Option<&str> {
        self.current().map(|t| t.id.as_str())
    }

    pub(super) fn playing_as(&self) -> Option<&Track> {
        self.playing_as.as_ref().filter(|p| p.generation == self.generation && self.current_id() == Some(p.listed.as_str())).map(|p| &p.song)
    }

    pub(super) fn playing_track(&self) -> Option<&Track> {
        self.playing_as().or(self.current())
    }

    pub(super) fn playing_id(&self) -> Option<&str> {
        self.playing_track().map(|t| t.id.as_str())
    }

    pub(super) fn mix_hold(&self) -> Option<&MixFrom> {
        self.mix_from.as_ref().filter(|h| !h.released)
    }

    pub(super) fn mixing(&self) -> bool {
        self.mix_from.as_ref().is_some_and(|h| h.started && self.audio.status.mixing())
    }

    pub(super) fn mix_hold_pos(&self, h: &MixFrom) -> f32 {
        self.audio.status.outgoing_position().as_secs_f32().min(h.total)
    }

    pub(super) fn watch_mix(&mut self) -> bool {
        let pos = self.audio.status.position().as_secs_f32();
        let Some(h) = &mut self.mix_from else { return false };
        if let Some(duration) = self.audio.status.mix_duration(self.generation) {
            h.started = true;
            h.mix = duration;
        }
        let switch = h.started && !h.released && pos >= h.mix * MIX_SWITCH;
        if switch {
            h.released = true;
        }
        switch
    }

    pub(super) fn mix_over(&self) -> bool {
        self.mix_from.as_ref().is_some_and(|h| h.started && !self.audio.status.mixing())
    }

    pub(super) fn shown(&self) -> Option<&Track> {
        self.mix_hold().map(|h| &h.track).or_else(|| self.playing_track())
    }

    pub(super) fn release_mix(&mut self) {
        if let Some(track) = self.playing_track().cloned() {
            self.show_in_media(&track);
            self.request_art();
            self.refresh_lyrics(track);
        }
    }

    pub(super) fn play_listed_upload(&mut self) {
        if self.playing_as().is_none() {
            return;
        }
        let at = self.audio.status.position_smooth();
        self.playing_as = None;
        let keep = std::mem::replace(&mut self.play_songs, false);
        self.play_current_at(at, false, false, true);
        self.play_songs = keep;
    }

    pub(super) fn play_song_version(&mut self) {
        if crate::motion::SUPPORTED {
            self.video_mode = false;
            if self.playing_track().is_some_and(|t| !t.music_video) {
                return;
            }
        }
        let at = self.audio.status.position_smooth();
        let alternate = self.version_alternate.as_ref().filter(|(listed, _)| self.current_id() == Some(listed.as_str())).map(|(_, song)| song.clone());
        if let Some(song) = alternate {
            self.next_as = Some(song);
            self.play_current_at(at, false, false, true);
            if crate::motion::SUPPORTED {
                self.video_mode = false;
            }
            return;
        }
        let keep = std::mem::replace(&mut self.play_songs, true);
        self.play_current_at(at, false, true, true);
        self.play_songs = keep;
        if crate::motion::SUPPORTED {
            self.video_mode = false;
        }
    }

    pub(super) fn play_video_version(&mut self) {
        if crate::motion::SUPPORTED {
            self.video_mode = true;
            let Some(track) = self.current().cloned() else { return };
            if track.music_video {
                if self.playing_track().is_some_and(|t| t.music_video) {
                    self.play_song_version();
                    self.video_mode = true;
                }
                return;
            }
            match self.video_of.as_ref().filter(|(listed, _)| *listed == track.id) {
                Some((_, Some(_))) => return,
                Some((_, None)) => {
                    self.video_mode = false;
                    self.toast("No music video found for this song.".to_owned());
                }
                None => {
                    self.video_wanted = true;
                    self.backend.request(Req::VideoOf { generation: self.generation, track });
                }
            }
            return;
        }
        let Some(track) = self.current().cloned() else { return };
        let at = self.audio.status.position_smooth();
        match self.video_of.as_ref().filter(|(listed, _)| *listed == track.id) {
            Some((_, Some(video))) => {
                self.version_alternate = Some((track.id.clone(), video.clone()));
                self.next_as = Some(video.clone());
                self.play_current_at(at, false, false, true);
            }
            Some((_, None)) => self.toast("No music video found for this song.".to_owned()),
            None => {
                self.video_wanted = true;
                self.backend.request(Req::VideoOf { generation: self.generation, track });
            }
        }
    }

    pub(super) fn load_other_version(&mut self, track: &Track) {
        if !self.load_both || self.jam.active() {
            return;
        }
        if track.music_video {
            self.backend.request(Req::Prefetch { video_id: track.id.clone(), song_of: Some(track.clone()), track: Some(track.clone()) });
        } else {
            self.backend.request(Req::VideoOf { generation: self.generation, track: track.clone() });
        }
    }

    pub(super) fn song_of(&self, t: &Track) -> Option<Track> {
        (self.play_songs && t.music_video && !self.jam.active()).then(|| t.clone())
    }

    pub(super) fn playing(&self) -> bool {
        self.audio.status.playing.load(Ordering::Relaxed)
    }

    pub(super) fn request_art(&mut self) {
        let Some(track) = self.current().cloned() else { return };
        if self.art_key.as_deref() == Some(track.id.as_str()) {
            return;
        }
        if let Some(url) = backend::art_url(&self.with_cover(&track)) {
            let key = track.id.clone();
            self.backend.request(Req::Art { key, url });
        }
    }

    pub(super) fn play_list(&mut self, tracks: Vec<Track>, start: usize) {
        self.play_list_filling(tracks, start, None);
    }

    pub(super) fn play_list_filling(&mut self, tracks: Vec<Track>, start: usize, fill: Option<QueueFill>) {
        if !self.queue.replace(tracks, start, fill) {
            return;
        }
        self.play_pending = None;
        self.play_current();
    }

    pub(super) fn restore_session(&mut self, json: &str) {
        let Ok(s) = serde_json::from_str::<Session>(json) else { return };
        let Some(track) = s.queue.get(s.index).cloned() else { return };
        self.queue.adopt(s.queue, s.index);
        let shown = s.playing_as.clone().unwrap_or_else(|| track.clone());
        self.playing_as = s.playing_as.map(|song| PlayingAs { generation: self.generation, listed: track.id.clone(), song });
        let dur = shown.duration.map_or(s.duration_ms, |d| d as u64 * 1000);
        self.audio.status.duration_ms.store(dur, Ordering::Relaxed);
        self.audio.status.position_ms.store(s.position_ms, Ordering::Relaxed);
        self.resume_at = Some(Duration::from_millis(s.position_ms));
        self.listened_at = s.listened_at;
        self.request_art();
        self.backend.request(Req::Lyrics(shown));
    }

    pub(super) fn sync_server_queue(&mut self, ctx: &egui::Context, now: f64) {
        if self.playing() {
            self.listened_at = unix_now();
        }
        let focused = ctx.input(|i| i.focused);
        let refocused = focused && !std::mem::replace(&mut self.was_focused, focused);
        let idle = !self.playing() && !self.buffering && !self.jam.active() && self.smoke.is_none();
        let due = self.queue_checked.is_none_or(|t| refocused && now - t > 60.0);
        if self.logged_in && idle && due && std::env::var_os("NUVX_SMOKE").is_none() {
            self.queue_checked = Some(now);
            self.backend.request(Req::ServerQueue);
        }
    }

    pub(super) fn adopt_server_queue(&mut self, q: crate::ytm::ServerQueue) {
        let Some(track) = q.tracks.get(q.index).cloned() else { return };
        let idle = !self.playing() && !self.buffering && !self.jam.active();
        if !idle || q.watched_at <= self.listened_at + 60 || self.current_id() == Some(track.id.as_str()) {
            return;
        }
        log::info!("picking up {} from another device ({} in its queue)", track.id, q.tracks.len());
        self.queue.adopt(q.tracks, q.index);
        self.playing_as = None;
        self.queue_source = None;
        self.listened_at = q.watched_at;
        self.audio.send(AudioCmd::Stop);
        self.audio.status.duration_ms.store(track.duration.map_or(0, |d| d as u64 * 1000), Ordering::Relaxed);
        self.audio.status.position_ms.store(0, Ordering::Relaxed);
        self.resume_at = Some(Duration::ZERO);
        self.resume_counts = true;
        self.request_art();
        self.backend.request(Req::Lyrics(track.clone()));
        self.toast(format!("Picked up \"{}\" from your other device. Press play to carry on.", track.title));
    }

    pub(super) fn session_json(&self) -> Option<String> {
        let (queue, index) = self.queue.saved()?;
        serde_json::to_string(&Session {
            queue: queue.to_vec(),
            index,
            position_ms: self.audio.status.position().as_millis() as u64,
            duration_ms: self.audio.status.duration_ms.load(Ordering::Relaxed),
            listened_at: if self.playing() { unix_now() } else { self.listened_at },
            playing_as: self.playing_as().cloned(),
        })
        .ok()
    }

    pub(super) fn recover(&mut self, at: Duration, reason: &str) {
        const MAX_RECOVERIES: u32 = 3;
        if self.recoveries < MAX_RECOVERIES {
            self.recoveries += 1;
            log::warn!("playback interrupted at {at:?} ({reason}); reopening (try {})", self.recoveries);
            self.play_current_at(at, false, false, false);
            return;
        }
        log::warn!("playback interrupted at {at:?} ({reason}); giving up");
        self.audio.status.position_ms.store(at.as_millis() as u64, Ordering::Relaxed);
        self.resume_at = Some(at);
        self.buffering = false;
        let title = self.current().map(|t| t.title.clone()).unwrap_or_default();
        self.toast(format!("Lost the connection while playing \"{title}\". Press play to pick up where it stopped."));
    }

    pub(super) fn play_current(&mut self) {
        self.recoveries = 0;
        self.play_current_at(Duration::ZERO, true, false, false);
    }

    pub(super) fn play_current_at(&mut self, at: Duration, report: bool, lookup_song: bool, preserve_play_state: bool) {
        let Some(track) = self.current().cloned() else { return };
        self.video_mode = crate::motion::SUPPORTED && track.music_video;
        if self.version_alternate.as_ref().is_some_and(|(listed, _)| listed != &track.id) {
            self.version_alternate = None;
        }
        let start_paused = preserve_play_state && !self.playing() && !self.buffering;
        let mixing = self.pending_mix.is_some();
        if !mixing {
            self.mix_from = None;
        }
        let carried = self.next_as.take().or_else(|| if at.is_zero() || self.jam.active() { None } else { self.playing_as().cloned() });
        self.queue.started();
        self.resume_at = None;
        self.resume_counts = false;
        self.generation += 1;
        self.audio.status.wanted_gen.store(self.generation, Ordering::Relaxed);
        self.audio_info = None;
        let shown = carried.clone().unwrap_or_else(|| track.clone());
        self.audio.status.duration_ms.store(shown.duration.map_or(0, |d| d as u64 * 1000), Ordering::Relaxed);
        self.audio.status.hold_position(at);
        self.audio.send(if self.handoff && at.is_zero() { AudioCmd::Handoff } else { AudioCmd::Stop });
        self.buffering = true;
        self.seek_drag = None;
        let song_of = if carried.is_none() && (at.is_zero() || lookup_song) { self.song_of(&track) } else { None };
        self.playing_as = carried.clone().map(|song| PlayingAs { generation: self.generation, listed: track.id.clone(), song });
        self.video_wanted = false;
        self.backend.request(Req::Play { video_id: track.id.clone(), generation: self.generation, at, report, start_paused, song_of, song: carried.clone(), track: Some(track.clone()) });
        if carried.is_none() {
            self.load_other_version(&track);
        }
        if mixing {
        } else {
            self.show_in_media(&shown);
            self.request_art();
            self.refresh_lyrics(shown);
        }
        if let Some(m) = &mut self.media {
            m.set_playing(true);
        }
        self.top_up();
    }

    pub(super) fn show_in_media(&mut self, shown: &Track) {
        let Some(track) = self.current().cloned().map(|t| self.with_cover(&t)) else { return };
        let Some(m) = &mut self.media else { return };
        let by_url = matches!(crate::proxy::active(), crate::proxy::Setting::System);
        let art = backend::art_url(&track);
        m.set_track(&shown.title, &shown.artist_line(), shown.album.as_ref().map(|a| a.name.as_str()), art.as_deref().filter(|_| by_url), shown.duration);
        if !by_url && let Some(url) = art {
            self.fetch_media_cover(track.id.clone(), url);
        }
    }

    pub(super) fn refresh_lyrics(&mut self, playing: Track) {
        self.lyrics = None;
        self.lyrics_extras = None;
        self.extras_asked = None;
        self.lyric_view.reset();
        self.segments = None;
        if crate::radio::is_station(&playing.id) {
            return;
        }
        self.backend.request(Req::Lyrics(playing));
        self.request_segments();
    }

    pub(super) fn top_up(&mut self) {
        if self.autoplay && !self.radio_pending && !self.jam.guest() && self.queue.fill.is_none() && self.queue.at_end()
            && let Some(t) = self.current().filter(|t| !crate::radio::is_station(&t.id)).cloned()
        {
            self.radio_pending = true;
            self.backend.request(Req::Radio { song_of: self.song_of(&t), seed: t.id });
        }
    }

    pub(crate) fn set_crossfade(&self) {
        self.audio.set_automix(self.automix);
        self.audio.set_crossfade(if self.crossfade || self.automix { self.crossfade_secs } else { 0 });
    }

    pub(super) fn request_segments(&mut self) {
        if !self.sponsorblock || self.segments.is_some() {
            return;
        }
        let Some(video_id) = self.playing_id().map(str::to_owned) else { return };
        self.backend.request(Req::Segments { video_id });
    }

    pub(super) fn skip_segments(&mut self, ctx: &egui::Context, now: f64) {
        if !self.sponsorblock || self.jam.guest() || self.resume_at.is_some() || self.buffering || !self.playing() {
            return;
        }
        let Some((id, segs)) = &self.segments else { return };
        if self.playing_id() != Some(id.as_str()) || now - self.segment_skipped < 1.5 {
            return;
        }
        let Some(duration) = self.audio.status.duration().map(|d| d.as_secs_f64()) else { return };
        let pos = self.audio.status.position().as_secs_f64();
        let mut segs = segs.iter().filter(|s| s.fits(duration));
        let Some(seg) = segs.clone().find(|s| pos >= s.start && pos < s.end - 0.3).copied() else {
            if let Some(next) = segs.find(|s| s.start > pos) {
                ctx.request_repaint_after(Duration::from_secs_f64(next.start - pos + 0.05));
            }
            return;
        };
        self.segment_skipped = now;
        log::info!("sponsorblock: skipping {:.1}–{:.1} s", seg.start, seg.end);
        if seg.end >= duration - 1.0 {
            if self.queue.repeat == Repeat::One {
                self.play_current();
            } else {
                self.next();
            }
        } else {
            self.audio.send(AudioCmd::Seek(Duration::from_secs_f64(seg.end)));
        }
    }

    pub(super) fn prefetch_next(&self) {
        if let Some(t) = self.queue.upcoming() {
            self.backend.request(Req::Prefetch { video_id: t.id.clone(), song_of: self.song_of(t), track: Some(t.clone()) });
        }
    }

    pub(super) fn next(&mut self) {
        if self.jam.guest() {
            return jam::request(self, crate::jam::Cmd::Skip);
        }
        if !self.queue.advance() {
            return;
        }
        self.play_current();
    }

    pub(super) fn prev(&mut self) {
        if self.jam.guest() {
            return jam::request(self, crate::jam::Cmd::Prev);
        }
        if self.audio.status.position() > Duration::from_secs(3) {
            self.seek_to(Duration::ZERO);
            return;
        }
        if self.queue.back() {
            self.play_current();
        }
    }

    pub(super) fn seek_to(&mut self, at: Duration) {
        if self.resume_at.is_some() {
            self.resume_at = Some(at);
            self.audio.status.position_ms.store(at.as_millis() as u64, Ordering::Relaxed);
        } else {
            self.audio.send(AudioCmd::Seek(at));
        }
    }

    pub(super) fn toggle(&mut self) {
        if self.queue.index.is_none() {
            return;
        }
        if self.jam.guest() {
            return jam::request(self, crate::jam::Cmd::Toggle);
        }
        if let Some(at) = self.resume_at {
            let report = std::mem::take(&mut self.resume_counts);
            self.play_current_at(at, report, false, false);
            return;
        }
        let playing = self.playing();
        self.audio.send(if playing { AudioCmd::Pause } else { AudioCmd::Resume });
    }

    pub(super) fn shuffle_upcoming(&mut self) {
        if self.jam.guest() {
            return self.toast("Only the host can shuffle the jam");
        }
        if self.queue.shuffle_upcoming() {
            self.toast("Shuffled up next");
            self.prefetch_next();
        }
    }
}

fn unix_now() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs())
}
