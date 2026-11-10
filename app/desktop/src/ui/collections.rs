use std::collections::HashSet;

use crate::backend::{Collection, Req};
use crate::model::{ShelfKind, Target, Track};

use super::lists::{Arrival, Done, LIKED_MUSIC, Opened, Start};
use super::queue::QueueFill;
use super::{App, shuffle};

impl App {
    pub(super) fn load_list(&mut self, id: &str, album: bool, changed: bool) {
        if let Some(s) = self.lists.load(id, album, changed) {
            self.start_load(s);
        }
    }

    pub(super) fn visit_list(&mut self, id: &str, album: bool) {
        if let Some(s) = self.lists.visit(id, album, std::time::Instant::now()) {
            self.start_load(s);
        }
    }

    fn start_load(&self, s: Start) {
        self.backend.request(match s.album {
            true => Req::Album { id: s.id, load: s.load },
            false => Req::Playlist { id: s.id, load: s.load, stream: s.stream },
        });
    }

    pub(super) fn opened(&self, id: &str) -> Option<&Opened> {
        self.lists.get(id)
    }

    pub(super) fn receiving(&self, id: &str) -> bool {
        self.lists.receiving(id)
    }

    pub(super) fn request_liked(&mut self, changed: bool) {
        if self.logged_in {
            self.load_list(LIKED_MUSIC, false, changed);
        }
    }

    pub(super) fn set_liked(&mut self, tracks: &[Track], like: bool) {
        log::debug!("like={like}: {} songs, e.g. {:?}", tracks.len(), tracks.iter().take(3).map(|t| &t.id).collect::<Vec<_>>());
        for t in tracks {
            if like {
                self.liked.insert(t.id.clone());
            } else {
                self.liked.remove(&t.id);
            }
        }
        self.lists.set_liked(tracks, like);
    }

    pub(super) fn play_opened(&mut self, id: String, shuffled: bool) {
        let Some(o) = self.opened(&id).filter(|o| !o.c.tracks.is_empty()) else { return };
        let mut tracks = o.c.tracks.clone();
        if shuffled {
            shuffle(&mut tracks);
        }
        let fill = self.receiving(&id).then(|| QueueFill { id: id.clone(), load: o.load, shuffled, ran_out: false });
        self.queue_source = Some(if o.c.is_album { Target::Album(id) } else { Target::Playlist(id) });
        self.play_list_filling(tracks, 0, fill);
    }

    pub(super) fn current_collection(&mut self) -> Option<&mut Collection> {
        let id = self.page_list()?.to_owned();
        self.lists.collection_mut(&id)
    }

    pub(super) fn collection_in(&mut self, load: u64, c: Collection, more: bool) {
        let id = c.id.clone();
        let keep = self.keep_lists();
        let keep: Vec<&str> = keep.iter().map(String::as_str).collect();
        let Some(arrival) = self.lists.arrived(load, c, more, &keep) else { return };
        if self.page_list() == Some(id.as_str()) {
            self.loading = false;
        }
        if id == LIKED_MUSIC
            && let Some(o) = self.lists.get(LIKED_MUSIC)
        {
            self.liked = o.c.tracks.iter().map(|t| t.id.clone()).collect();
            log::debug!("hearts: {} liked (from Liked Music's first page or whole copy)", self.liked.len());
        }
        if let Arrival::Done(done) = arrival {
            self.load_done(&id, done);
        }
        if let Some((Target::Playlist(p), shuffled)) = &self.play_pending
            && *p == id
        {
            let shuffled = *shuffled;
            self.play_pending = None;
            if !self.jam.guest() {
                self.play_opened(id, shuffled);
            }
        }
    }

    pub(super) fn collection_more(&mut self, id: String, load: u64, tracks: Vec<Track>, set_ids: Vec<Option<String>>, more: bool) {
        let keep = self.keep_lists();
        let keep: Vec<&str> = keep.iter().map(String::as_str).collect();
        let Some(page) = self.lists.page(&id, load, tracks, set_ids, more, &keep) else { return };
        if id == LIKED_MUSIC {
            if page.kept {
                self.liked.extend(page.tracks.iter().map(|t| t.id.clone()));
            }
            log::debug!("hearts: {} liked", self.liked.len());
        }
        self.fill_queue(&id, load, page.tracks, more);
        if let Some(done) = page.done {
            self.load_done(&id, done);
        }
    }

    fn load_done(&mut self, id: &str, done: Done) {
        match done {
            Done::Kept => {}
            Done::Reload(s) => self.start_load(s),
            Done::NotKept => {
                if matches!(&self.play_pending, Some((Target::Playlist(p), _)) if p == id) {
                    self.play_pending = None;
                }
                if self.page_list() == Some(id) {
                    self.loading = false;
                    self.load_failed = true;
                }
            }
        }
    }

    fn keep_lists(&self) -> Vec<String> {
        let playing = match &self.queue_source {
            Some(Target::Album(id) | Target::Playlist(id)) => Some(id.clone()),
            _ => None,
        };
        self.page_list().map(str::to_owned).into_iter().chain(playing).collect()
    }

    fn fill_queue(&mut self, id: &str, load: u64, tracks: Vec<Track>, more: bool) {
        let playing_it = self.queue_source == Some(Target::Playlist(id.to_owned()));
        let Some(filled) = self.queue.fill_from(id, load, tracks, more, playing_it) else { return };
        if filled.ran_out {
            self.next();
        } else if filled.was_at_end && !self.buffering {
            self.prefetch_next();
        }
        self.top_up();
    }

    pub(super) fn request_library(&mut self) {
        self.library_requested = std::time::Instant::now();
        self.backend.request(Req::Library);
    }

    pub(super) fn preferred_artist_ids(&self) -> Vec<String> {
        let mut ids = HashSet::new();
        if let Some(home) = &self.home {
            for shelf in &home.shelves {
                if shelf.title != "Listen again" && shelf.title != "Quick picks" { continue; }
                match &shelf.kind {
                    ShelfKind::Cards(cards) => {
                        for card in cards {
                            ids.extend(card.links.iter().filter_map(|link| link.id.clone()));
                            if let Target::Song(track) = &card.target {
                                ids.extend(track.artists.iter().filter_map(|artist| artist.id.clone()));
                            }
                        }
                    }
                    ShelfKind::Songs { tracks, .. } => {
                        for track in tracks {
                            ids.extend(track.artists.iter().filter_map(|artist| artist.id.clone()));
                        }
                    }
                }
            }
        }
        ids.into_iter().collect()
    }
}
