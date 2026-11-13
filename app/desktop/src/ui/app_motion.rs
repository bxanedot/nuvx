use super::*;

pub(super) struct MotionView {
    motion: std::sync::Arc<crate::motion::Motion>,
    tex: Option<egui::TextureHandle>,
    seen: u64,
    width: u32,
}

impl MotionView {
    fn new(motion: std::sync::Arc<crate::motion::Motion>, width: u32) -> Self {
        Self { motion, tex: None, seen: 0, width }
    }

    fn texture(&mut self, ctx: &egui::Context) -> Option<&egui::TextureHandle> {
        if let Some((version, frame)) = self.motion.latest()
            && version != self.seen
        {
            self.seen = version;
            let image = egui::ColorImage::from_rgba_unmultiplied([frame.w, frame.h], &frame.rgba);
            match &mut self.tex {
                Some(tex) => tex.set(image, egui::TextureOptions::LINEAR),
                None => self.tex = Some(ctx.load_texture("apple-motion", image, egui::TextureOptions::LINEAR)),
            }
        }
        self.tex.as_ref()
    }
}

pub(super) enum VideoState {
    Saved(std::path::PathBuf),
    Playing(MotionView),
    Failed,
}

impl Drop for MotionView {
    fn drop(&mut self) {
        self.motion.stop();
    }
}

impl App {
    pub(super) fn with_cover(&mut self, t: &Track) -> Track {
        if !t.thumbs.first().is_some_and(|th| th.url.contains("ytimg.com")) {
            return t.clone();
        }
        let song = match self.playing_as().filter(|_| self.current_id() == Some(t.id.as_str())) {
            Some(song) => Some(song.clone()),
            None => match self.song_covers.get(&t.id) {
                Some(found) => found.clone(),
                None => {
                    if self.song_covers.len() >= 512 {
                        self.song_covers.clear();
                    }
                    self.song_covers.insert(t.id.clone(), None);
                    self.backend.request(Req::SongCover { track: t.clone() });
                    None
                }
            },
        };
        match song.filter(|s| !s.thumbs.is_empty()) {
            Some(s) => Track { thumbs: s.thumbs, album: s.album.or_else(|| t.album.clone()), ..t.clone() },
            None => t.clone(),
        }
    }

    pub(super) fn song_canvas(&mut self, ctx: &egui::Context, t: &Track, max_width: u32) -> Option<widgets::MotionSprite> {
        let album = t.album.as_ref().map_or("", |a| a.name.as_str());
        let artist = t.artists.first().map(|a| a.name.clone()).unwrap_or_else(|| t.artist_line());
        self.canvas(ctx, format!("song\u{1}{}\u{1}{album}", t.id), album, &artist, Some(&t.title), max_width)
    }

    pub(super) fn album_canvas(&mut self, ctx: &egui::Context, album: &str, artist: &str, max_width: u32) -> Option<widgets::MotionSprite> {
        if album.is_empty() {
            return None;
        }
        self.canvas(ctx, format!("{album}\u{1}{artist}"), album, artist, None, max_width)
    }

    pub(super) fn canvas(&mut self, ctx: &egui::Context, key: String, album: &str, artist: &str, title: Option<&str>, max_width: u32) -> Option<widgets::MotionSprite> {
        if !crate::motion::SUPPORTED || !self.anim_canvas || artist.is_empty() {
            return None;
        }
        if let Some(url) = self.apple_covers.get(&key) {
            let url = url.clone()?;
            return self.motion_sprite(ctx, key, &url, max_width);
        }
        let cooling = self.apple_missed.get(&key).is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(300));
        if !cooling && self.apple_waiting.insert(key.clone()) {
            self.backend.request(Req::Canvas { key, album: album.to_owned(), artist: artist.to_owned(), title: title.map(str::to_owned) });
        }
        None
    }

    pub(super) fn signature_texture(&mut self, ctx: &egui::Context, name: &str) -> Option<egui::TextureHandle> {
        let endpoint = crate::artist_signature::endpoint(&self.signature_api);
        let canon = crate::artist_signature::key(name);
        if endpoint.is_empty() || canon.is_empty() {
            return None;
        }
        let key = format!("{endpoint}\u{1}{canon}");
        if let Some(tex) = self.signature_tex.get(&key) {
            return Some(tex.clone());
        }
        if let Some((w, h, rgba)) = self.signature_images.remove(&key) {
            let image = egui::ColorImage::from_rgba_unmultiplied([w, h], &rgba);
            let tex = ctx.load_texture("artist-signature", image, egui::TextureOptions::LINEAR);
            if self.signature_tex.len() >= 8 {
                self.signature_tex.clear();
            }
            self.signature_tex.insert(key, tex.clone());
            return Some(tex);
        }
        if self.signature_missed.get(&key).is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(60)) {
            return None;
        }
        if self.signature_waiting.insert(key.clone()) {
            self.backend.request(Req::ArtistSignature { key, endpoint, name: name.to_owned() });
        }
        None
    }

    pub(super) fn signature_reset(&mut self) {
        self.signature_tex.clear();
        self.signature_images.clear();
        self.signature_missed.clear();
        self.signature_waiting.clear();
    }

    pub(super) fn video_sprite(&mut self, ctx: &egui::Context) -> Option<widgets::MotionSprite> {
        let id = self
            .current()
            .filter(|t| t.music_video)
            .or_else(|| self.playing_track().filter(|t| t.music_video))
            .map(|t| t.id.clone())
            .or_else(|| {
                let current = self.current()?;
                self.video_of
                    .as_ref()
                    .filter(|(listed, _)| listed == &current.id)
                    .and_then(|(_, video)| video.as_ref())
                    .map(|video| video.id.clone())
            })
            .filter(|_| crate::motion::SUPPORTED);
        let Some(id) = id else {
            self.video = None;
            return None;
        };
        if self.video.as_ref().is_none_or(|(v, _)| *v != id) {
            self.backend.request(Req::Video { id: id.clone(), height: self.video_height });
            self.video = Some((id, None));
            return None;
        }
        let state = self.video.as_mut()?.1.as_mut()?;
        if let VideoState::Saved(path) = state {
            *state = VideoState::Playing(MotionView::new(crate::motion::video(ctx, path.clone(), self.audio.status.clone()), 0));
        }
        let VideoState::Playing(view) = state else { return None };
        let tex = view.texture(ctx)?;
        Some(widgets::MotionSprite { id: tex.id(), size: tex.size_vec2() })
    }

    pub(super) fn version_loading(&self) -> bool {
        let picture = crate::motion::SUPPORTED && (self.current().is_some_and(|t| t.music_video) || self.playing_track().is_some_and(|t| t.music_video)) && self.video.as_ref().is_none_or(|(_, s)| s.is_none());
        self.buffering || self.video_wanted || picture
    }

    pub fn set_video_height(&mut self, height: u32) {
        self.video_height = height;
        self.video = None;
    }

    pub(super) fn motion_sprite(&mut self, ctx: &egui::Context, key: String, url: &str, max_width: u32) -> Option<widgets::MotionSprite> {
        let stale = self.apple_motion.get(&key).map_or(true, |v| v.width < max_width);
        if stale {
            if self.apple_motion.len() >= 8 {
                self.apple_motion.clear();
            }
            self.apple_motion.insert(key.clone(), MotionView::new(crate::motion::play(ctx, url, max_width), max_width));
        }
        let tex = self.apple_motion.get_mut(&key)?.texture(ctx)?;
        Some(widgets::MotionSprite { id: tex.id(), size: tex.size_vec2() })
    }
}
