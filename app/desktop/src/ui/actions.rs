use std::time::Duration;

use crate::backend::Req;
use crate::model::{Target, Track};

use super::lists::LIKED_MUSIC;
use super::queue::QueueFill;
use super::{Action, App, Dialog, LibTab, NpTab, Page, jam, transfer};

impl App {
    pub(super) fn apply(&mut self, a: Action) {
        let a = if self.jam.guest() {
            match jam::guest_action(self, a) {
                Some(a) => a,
                None => return,
            }
        } else {
            a
        };
        self.remember_search_click(&a);
        match a {
            Action::Play(tracks, i) if tracks.get(i).is_some_and(|t| self.current_id() == Some(t.id.as_str())) && self.resume_at.is_none() => {
                self.toggle()
            }
            Action::Play(tracks, i) => {
                self.queue_source = match &self.page {
                    Page::Album(id) => Some(Target::Album(id.clone())),
                    Page::Playlist(id) => Some(Target::Playlist(id.clone())),
                    Page::Library(LibTab::Songs) => Some(Target::Playlist(LIKED_MUSIC.into())),
                    Page::Artist(id) => Some(Target::Artist(id.clone())),
                    _ => None,
                };
                let fill = self.page_list().filter(|id| self.receiving(id)).and_then(|id| {
                    let o = self.opened(id).filter(|o| o.c.tracks.len() == tracks.len())?;
                    Some(QueueFill { id: id.to_owned(), load: o.load, shuffled: o.c.tracks.iter().zip(&tracks).any(|(a, b)| a.id != b.id), ran_out: false })
                });
                self.play_list_filling(tracks, i, fill);
            }
            Action::TogglePlay => self.toggle(),
            Action::TransferPick(t) => transfer::use_pick(self, t),
            Action::PlayCollection(Target::Album(id) | Target::Playlist(id), shuffle) if self.opened(&id).is_some() => self.play_opened(id, shuffle),
            Action::PlayCollection(target, shuffle) => {
                self.toast("Starting playback…");
                self.play_pending = Some((target.clone(), shuffle));
                match target {
                    Target::Playlist(id) => self.load_list(&id, false, false),
                    target => self.backend.request(Req::PlayCollection { target }),
                }
            }
            Action::Open(Target::Song(t)) | Action::Radio(t) => {
                self.queue_source = None;
                self.play_list(vec![t.clone()], 0);
                if !self.radio_pending {
                    self.radio_pending = true;
                    self.backend.request(Req::Radio { song_of: self.song_of(&t), seed: t.id });
                }
            }
            Action::Open(Target::Album(id)) => self.go(Page::Album(id)),
            Action::Open(Target::Playlist(id)) => self.go(Page::Playlist(id)),
            Action::Open(Target::Artist(id)) => self.go(Page::Artist(id)),
            Action::PlayNext(t) => {
                let start = self.queue.play_next(t);
                self.toast("Playing next");
                if start {
                    self.play_current();
                } else {
                    self.prefetch_next();
                }
            }
            Action::Enqueue(t) => {
                let start = self.queue.enqueue(t);
                self.toast("Added to queue");
                if start {
                    self.play_current();
                }
            }
            Action::Go(p) => self.go(p),
            Action::QueueJump(i) => {
                self.play_pending = None;
                self.queue.index = Some(i);
                self.play_current();
            }
            Action::QueueRemove(i) => self.queue.remove(i),
            Action::ViewQueue => {
                if self.np_open {
                    self.np_tab = NpTab::UpNext;
                } else {
                    self.queue_open = true;
                }
            }
            Action::Seek(secs) => {
                self.seek_to(Duration::from_secs_f32(secs.max(0.0)));
                self.lyric_view.resume();
            }
            Action::Like(t, like) => {
                if !self.logged_in {
                    self.toast("Sign in to like songs");
                    return;
                }
                self.set_liked(std::slice::from_ref(&t), like);
                self.backend.request(Req::Like { video_id: t.id, like });
            }
            Action::LikeMany(tracks, like) => {
                if !self.logged_in {
                    self.toast("Sign in to like songs");
                    return;
                }
                let tracks: Vec<Track> = tracks.into_iter().filter(|t| self.liked.contains(&t.id) != like).collect();
                if tracks.is_empty() { return; }
                if like {
                    self.set_liked(&tracks, true);
                } else {
                    self.unliking.extend(tracks.iter().map(|t| (t.id.clone(), t.clone())));
                }
                if tracks.len() > 10 {
                    self.toast(format!("{} {} songs…", if like { "Liking" } else { "Removing likes from" }, tracks.len()));
                }
                self.backend.request(Req::LikeMany { video_ids: tracks.into_iter().map(|t| t.id).collect(), like });
            }
            Action::Dislike(t) => {
                if !self.logged_in { self.toast("Sign in to rate songs"); return; }
                self.set_liked(std::slice::from_ref(&t), false);
                self.backend.request(Req::Dislike(t.id));
            }
            Action::Share(target) => {
                self.share_pending = Some(share_url(&target));
                self.toast("Link copied");
            }
            Action::Download(track) => {
                self.toast(format!("Downloading \u{201c}{}\u{201d}", track.title));
                let for_video = if self.playing_as().is_some_and(|s| s.id == track.id) { self.current_id().map(str::to_owned) } else { None };
                self.backend.request(Req::Download { track, for_video });
            }
            Action::DownloadAll(name, targets) => {
                self.toast(format!("Downloading \u{201c}{name}\u{201d}"));
                self.backend.request(Req::DownloadAll { name, targets });
            }
            Action::AddToPlaylist(tracks) => {
                if self.logged_in { self.dialog = Some(Dialog::PickPlaylist(tracks)); }
                else { self.toast("Sign in to edit playlists"); }
            }
            Action::RemoveFromPlaylist(mut rows) => {
                let Some(c) = self.current_collection().filter(|c| c.owned && !c.is_album) else { return };
                rows.sort_unstable();
                rows.dedup();
                rows.retain(|&i| c.set_ids.get(i).is_some_and(Option::is_some));
                if rows.is_empty() {
                    self.toast("YouTube Music didn't say where these songs are in the playlist. Reload it and try again.");
                    return;
                }
                let items: Vec<(String, String)> = rows.iter().map(|&i| (c.tracks[i].id.clone(), c.set_ids[i].clone().unwrap_or_default())).collect();
                let playlist = c.id.clone();
                c.remove_rows(&rows);
                log::debug!("list {playlist}: removing {} entries", items.len());
                self.lists.edits.remove(&playlist, items.iter().map(|(_, set)| set.clone()));
                self.selection.clear();
                self.backend.request(Req::RemoveFromPlaylist { playlist_id: playlist, items });
            }
            Action::NewPlaylist(tracks) => {
                if self.logged_in { self.dialog = Some(Dialog::NewPlaylist { title: String::new(), tracks }); }
                else { self.toast("Sign in to create playlists"); }
            }
            Action::EditPlaylist => {
                if let Some(c) = self.current_collection().filter(|c| !c.is_album) {
                    self.dialog = Some(Dialog::EditPlaylist {
                        id: c.id.clone(), title: c.title.clone(), description: c.description.clone().unwrap_or_default(), privacy: None,
                    });
                }
            }
            Action::Collaborate => {
                if let Some(id) = self.current_collection().filter(|c| !c.is_album && c.owned).map(|c| c.id.clone()) {
                    self.backend.request(Req::Collaboration(id.clone()));
                    self.dialog = Some(Dialog::Collaborate { id, info: None, busy: true, confirm_off: false });
                }
            }
            Action::DeletePlaylist => {
                if let Some(c) = self.current_collection().filter(|c| !c.is_album) {
                    self.dialog = Some(Dialog::ConfirmDelete { id: c.id.clone(), title: c.title.clone() });
                }
            }
            Action::SaveCollection(id, save) => {
                if self.logged_in { self.backend.request(Req::SaveCollection { id, save }); }
                else { self.toast("Sign in to save to library"); }
            }
            Action::OpenNowPlaying(open) => {
                if self.current().is_some() {
                    if open && !self.np_open {
                        self.np_tab = NpTab::Art;
                    }
                    self.np_open = open;
                }
            }
            Action::Mini(on) => self.mini.request(on),
            Action::RevertQuality => {
                let listed = self.current_id().map(str::to_owned);
                let playing = self.playing_track().map(|t| t.id.clone()).or(listed.clone());
                let Some(id) = playing else { return };
                let at = self.audio.status.position();
                self.backend.revert_quality(&id);
                if let Some(listed) = listed.filter(|l| l != &id) {
                    self.backend.revert_quality(&listed);
                }
                self.play_current_at(at, false, false, false);
            }
            Action::UpgradeQuality => {
                let listed = self.current_id().map(str::to_owned);
                let playing = self.playing_track().map(|t| t.id.clone()).or(listed.clone());
                let Some(id) = playing else { return };
                let at = self.audio.status.position();
                self.backend.upgrade_quality(&id);
                if let Some(listed) = listed.filter(|l| l != &id) {
                    self.backend.upgrade_quality(&listed);
                }
                self.play_current_at(at, false, false, false);
            }
        }
    }

    pub(super) fn shortcuts(&mut self, ctx: &egui::Context) {
        let typing = ctx.egui_wants_keyboard_input();
        let editing = ctx.text_edit_focused();
        let i = ctx.input(|i| {
            let k = |key| !editing && !i.modifiers.command && i.key_pressed(key);
            let ctrl = |key| !editing && i.modifiers.ctrl && i.key_pressed(key);
            (
                !typing && i.key_pressed(egui::Key::Space),
                i.pointer.button_pressed(egui::PointerButton::Extra1)
                    || (i.modifiers.alt && i.key_pressed(egui::Key::ArrowLeft)),
                i.pointer.button_pressed(egui::PointerButton::Extra2)
                    || (i.modifiers.alt && i.key_pressed(egui::Key::ArrowRight)),
                ctrl(egui::Key::ArrowRight),
                ctrl(egui::Key::ArrowLeft),
                k(egui::Key::L),
                k(egui::Key::Q),
                k(egui::Key::F),
                i.key_pressed(egui::Key::Escape),
                ctrl(egui::Key::ArrowUp),
                ctrl(egui::Key::ArrowDown),
                i.events.iter().any(|e| matches!(e, egui::Event::Key { key: egui::Key::M, pressed: true, repeat: false, modifiers, .. } if modifiers.ctrl)),
                i.modifiers.command && i.key_pressed(egui::Key::F),
            )
        });
        let (space, back, fwd, next, prev, lyr, queue, full, esc, vol_up, vol_down, mini, find) = i;
        if find && !self.mini.busy() {
            if !matches!(self.page, Page::Search(_)) {
                self.actions.push(Action::Go(Page::Search(String::new())));
            }
            self.np_open = false;
            ctx.data_mut(|d| d.insert_temp(egui::Id::unique("focus-search"), true));
        }
        if mini {
            self.actions.push(Action::Mini(!self.mini.target()));
        }
        let (back, fwd, lyr, queue, full, esc) = if self.mini.busy() { (false, false, false, false, false, false) } else { (back, fwd, lyr, queue, full, esc) };
        if space {
            self.toggle();
        }
        if back {
            self.nav_back();
        }
        if fwd {
            self.nav_forward();
        }
        if next {
            self.next();
        }
        if prev {
            self.prev();
        }
        if lyr && self.current().is_some() {
            self.np_open = true;
            self.np_tab = NpTab::Lyrics;
        }
        if queue && self.current().is_some() {
            self.np_open = true;
            self.np_tab = NpTab::UpNext;
        }
        if full && (self.np_open || self.current().is_some()) {
            self.np_open = true;
            self.set_fullscreen(ctx, !self.fullscreen);
        }
        if esc {
            if self.fullscreen {
                self.set_fullscreen(ctx, false);
            } else {
                self.np_open = false;
            }
        }
        if vol_up || vol_down {
            let v = (self.audio.volume() + if vol_up { 0.05 } else { -0.05 }).clamp(0.0, 1.0);
            self.audio.set_volume(v);
        }
    }

    pub(super) fn set_mini(&mut self, ctx: &egui::Context, on: bool, now: f64) {
        if on == self.mini.active() {
            return;
        }
        if on && self.fullscreen {
            self.set_fullscreen(ctx, false);
            self.mini.request(true);
            return;
        }
        self.mini.toggle(ctx, now);
    }

    pub(super) fn set_fullscreen(&mut self, ctx: &egui::Context, on: bool) {
        self.fullscreen = on;
        if !crate::fullscreen::set(on) {
            ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(on));
        }
        ctx.request_repaint();
    }
}

fn share_url(target: &Target) -> String {
    match target {
        Target::Song(t) => format!("https://music.youtube.com/watch?v={}", t.id),
        Target::Playlist(id) => format!("https://music.youtube.com/playlist?list={id}"),
        Target::Album(id) => format!("https://music.youtube.com/browse/{id}"),
        Target::Artist(id) => format!("https://music.youtube.com/channel/{id}"),
    }
}

#[cfg(test)]
mod share_tests {
    use super::*;

    #[test]
    fn shares_youtube_music_urls() {
        let song = Track { id: "abc123".into(), title: String::new(), artists: vec![], album: None, duration: None, thumbs: vec![], track_nr: None, plays: None, music_video: false };
        assert_eq!(share_url(&Target::Song(song)), "https://music.youtube.com/watch?v=abc123");
        assert_eq!(share_url(&Target::Playlist("PL123".into())), "https://music.youtube.com/playlist?list=PL123");
        assert_eq!(share_url(&Target::Album("MPRE123".into())), "https://music.youtube.com/browse/MPRE123");
        assert_eq!(share_url(&Target::Artist("UC123".into())), "https://music.youtube.com/channel/UC123");
    }
}
