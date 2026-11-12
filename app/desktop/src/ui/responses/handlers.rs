use super::*;

use crate::ytm::HomePage;

impl App {
    pub(super) fn on_home(&mut self, page: HomePage) {
        if self.account_avatar.is_none() {
            self.account_avatar = page.shelves.iter().find(|s| s.title == "Listen again").and_then(|s| s.strap_thumb.clone());
        }
        let mut chips = page.chips;
        if chips.is_empty() {
            chips = self.home.as_ref().map(|h| h.chips.clone()).unwrap_or_default();
        }
        self.home = Some(HomeState {
            chips,
            shelves: page.shelves,
            continuation: page.continuation,
            loading_more: None,
            picks_loading: false,
            picks_expanded: false,
        });
        self.home_loading = false;
        if self.page == Page::Home {
            self.loading = false;
        }
    }

    pub(super) fn on_liked_many(&mut self, video_ids: Vec<String>, failed: Vec<String>, like: bool) {
        if !like {
            let rest: Vec<Track> = video_ids.iter().filter_map(|id| self.unliking.remove(id)).collect();
            self.set_liked(&rest, false);
            for id in &failed {
                self.unliking.remove(id);
            }
        }
        log::debug!("like={like} answered: {} taken, {} refused {:?}", video_ids.len(), failed.len(), failed.iter().take(5).collect::<Vec<_>>());
        self.lists.edits.confirm_likes(&video_ids, &failed);
        if !failed.is_empty() {
            self.request_liked(true);
        }
        let count = video_ids.len();
        let mut msg = format!("{} {count} {}", if like { "Liked" } else { "Removed like from" }, if count == 1 { "song" } else { "songs" });
        if count == 0 {
            msg = format!("YouTube Music refused all {} songs; try again in a while", failed.len());
        } else if !failed.is_empty() {
            msg += &format!("; YouTube Music refused {}", failed.len());
        }
        self.toast(msg);
    }

    pub(super) fn on_playlist_changed(&mut self, id: String, message: &'static str) {
        self.request_library();
        if message == "Playlist deleted" {
            self.lists.forget(&id);
            if self.queue.fill.as_ref().is_some_and(|f| f.id == id) {
                self.queue.fill = None;
            }
            let gone = Page::Playlist(id);
            if self.page == gone {
                self.go(Page::Library(LibTab::Playlists));
            }
            self.back.retain(|p| *p != gone);
            self.back.dedup();
            self.forward.retain(|p| *p != gone);
            self.forward.dedup();
        } else {
            if message == "Removed from playlist" {
                self.lists.edits.confirm_removal(&id);
            }
            if self.lists.known(&id) {
                self.load_list(&id, false, true);
            }
        }
        self.toast(message);
    }

    pub(super) fn on_play_error(&mut self, generation: u64, msg: String) {
        if generation == self.generation {
            self.buffering = false;
            if self.mix_from.take().is_some() {
                self.pending_mix = None;
                self.audio.send(AudioCmd::Stop);
                self.release_mix();
            }
            let title = self.current().map(|t| t.title.clone()).unwrap_or_default();
            let reason = if msg.contains("age-restricted") || msg.contains("confirm your age") {
                "it's age-restricted".to_owned()
            } else {
                msg
            };
            let has_next = !self.queue.at_end() && self.queue.index.is_some();
            if let Some(why) = account_block(&reason, self.logged_in) {
                self.skip_streak = 0;
                self.toast(why.replace("{title}", &title));
            } else if has_next && self.skip_streak < MAX_SKIPS {
                self.skip_streak += 1;
                self.toast(format!("Skipped \"{title}\": {reason}"));
                self.next();
            } else {
                self.skip_streak = 0;
                self.toast(format!("Can't play \"{title}\": {reason}"));
            }
        }
    }

    pub(super) fn on_logged_in(&mut self, v: bool, now: f64) {
        self.logged_in = v;
        self.channels = None;
        self.channels_asked = None;
        self.suggestions = Default::default();
        self.suggest_sel = None;
        self.forgetting = None;
        self.suggest_round += 1;
        self.suggest_cache.clear();
        self.forgets_pending = 0;
        self.suggest_due = Some(now);
        self.account_name = None;
        self.account_avatar = None;
        self.sidebar_cache = None;
        self.library = None;
        self.history = None;
        self.uploads = None;
        self.lists.clear();
        self.lists.edits = Default::default();
        self.liked.clear();
        self.unliking.clear();
        self.queue.fill = None;
        self.play_pending = None;
        if self.page_list().is_some() {
            self.refresh();
        }
        self.home = None;
        self.home_loading = true;
        self.backend.request(Req::Home(None));
        if v {
            self.request_library();
            self.request_liked(false);
            self.backend.request(Req::Account);
        }
        self.toast(if v { "Signed in" } else { "Signed out" });
    }

    pub(super) fn on_error(&mut self, e: String) {
        if let Some(Dialog::Collaborate { info, busy, .. }) = &mut self.dialog {
            *busy = false;
            if info.is_none() {
                self.dialog = None;
            }
        }
        self.load_failed |= self.loading || (self.page == Page::Home && self.home_loading);
        self.loading = false;
        self.home_loading = false;
        self.radio_pending = false;
        if let Some(h) = &mut self.home {
            h.loading_more = None;
            h.picks_loading = false;
        }
        self.toast(e);
    }
}

const MAX_SKIPS: u32 = 3;

fn account_block(msg: &str, logged_in: bool) -> Option<&'static str> {
    let m = msg.to_ascii_lowercase();
    if m.contains("premium") {
        Some("\"{title}\" needs YouTube Music Premium")
    } else if !logged_in && (m.contains("sign in") || m.contains("age-restricted")) {
        Some("Sign in to play \"{title}\"")
    } else {
        None
    }
}

#[cfg(test)]
mod account_block_tests {
    use super::account_block;

    #[test]
    fn account_errors_stop_instead_of_skipping() {
        assert!(account_block("sign in to play this song", false).is_some());
        assert!(account_block("sign in to play this song", true).is_none());
        assert!(account_block("This video is only available to Music Premium members", true).is_some());
        assert!(account_block("decode: end of stream", false).is_none());
    }
}
