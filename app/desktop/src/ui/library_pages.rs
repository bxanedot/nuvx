use egui::{RichText, Ui, vec2};

use super::pages::{ListCtx, Rows, list, page_title, placeholder, CARD};
use super::widgets::{self, card, chip, pill};
use super::{Action, App, LibTab, Page, Selection};
use crate::model::{Card, Track};
use crate::theme::{self, icon};
fn select_pill(ui: &mut Ui, sel: &mut Selection, page: &Page) {
    let on = sel.on(page);
    if pill(ui, if on { "Done" } else { "Select" }, Some(icon::LIST_CHECKS), false).clicked() {
        if on { sel.clear() } else { sel.start(page) }
    }
}

fn view_pill(ui: &mut Ui, list: &mut bool) {
    if pill(ui, if *list { "Grid view" } else { "List view" }, None, false).clicked() {
        *list = !*list;
    }
}

fn card_row(ui: &mut Ui, c: &Card, acts: &mut Vec<Action>) {
    use egui::{Rect, Sense, pos2};
    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 56.0), Sense::click());
    let resp = widgets::named(resp, &c.title).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
    if h > 0.0 {
        ui.painter().rect_filled(rect, 10.0, theme::glass(0.08 * h));
    }
    let art = Rect::from_center_size(pos2(rect.left() + 32.0, rect.center().y), vec2(44.0, 44.0));
    widgets::paint_cover(ui, art, &c.thumbs, if c.round { 22.0 } else { 6.0 }, 1.0);
    let x = art.right() + 14.0;
    let w = rect.right() - x - 10.0;
    widgets::text_at(ui, pos2(x, rect.center().y - 19.0), &c.title, theme::semibold(14.0), theme::text(), w);
    widgets::text_at(ui, pos2(x, rect.center().y + 2.0), &c.subtitle, theme::regular(13.0), theme::text_dim(), w);
    if resp.clicked() {
        acts.push(Action::Open(c.target.clone()));
    }
}

pub(super) fn library(app: &mut App, ui: &mut Ui, tab: LibTab, now: f64) {
    page_title(ui, "Library");
    if !app.logged_in && (tab != LibTab::Playlists || (!app.create_playlists_locally && app.local_playlists.is_empty())) {
        ui.add_space(10.0);
        ui.label(RichText::new("Sign in to see your playlists, liked songs and history.").color(theme::text_dim()));
        ui.add_space(12.0);
        if pill(ui, "Sign in with Google", Some(icon::CIRCLE_USER), true).clicked() {
            app.start_sign_in(ui.ctx());
        }
        return;
    }
    ui.add_space(12.0);
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 10.0;
        for (t, label) in [(LibTab::Playlists, "Playlists"), (LibTab::Songs, "Liked songs"), (LibTab::Albums, "Albums"), (LibTab::History, "History"), (LibTab::Uploads, "Uploads")] {
            if chip(ui, label, tab == t).clicked() {
                if tab == t {
                    app.refresh();
                } else {
                    app.page = Page::Library(t);
                    app.page_loaded();
                }
            }
        }
    });
    ui.add_space(12.0);
    let as_list = app.lib_list;
    let grid = |ui: &mut Ui, cards: &[Card], acts: &mut Vec<Action>| {
        if as_list {
            for c in cards {
                card_row(ui, c, acts);
            }
            return;
        }
        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing = vec2(20.0, 22.0);
            for c in cards {
                card(ui, c, CARD, false, acts);
            }
        });
    };
    let rows = Rows::new(app, now);
    let plain = |_: usize, _: &Track| None;
    match tab {
        LibTab::History => {
            if app.history.is_none() {
                placeholder(app, ui, now, 1, 48.0);
                return;
            }
            let Some(h) = &app.history else { return };
            list(ui, &rows, &app.liked, h, true, plain, ListCtx { sel: &mut app.selection, page: &app.page, own: false }, &mut app.actions);
        }
        LibTab::Uploads => {
            ui.horizontal(|ui| {
                if pill(ui, "Upload music", Some(icon::UPLOAD), true).clicked() {
                    app.pick_uploads(ui.ctx());
                }
                if app.uploads.as_ref().is_some_and(|u| !u.is_empty()) && pill(ui, "Shuffle", Some(icon::SHUFFLE), false).clicked() {
                    let mut t = app.uploads.clone().unwrap_or_default();
                    super::shuffle(&mut t);
                    app.actions.push(Action::Play(t, 0));
                }
            });
            ui.add_space(6.0);
            ui.label(RichText::new("Or drop audio files anywhere on the window. MP3, M4A, FLAC, OGG and WMA.").font(theme::regular(13.0)).color(theme::text_dim()));
            ui.add_space(12.0);
            if app.uploads.is_none() {
                placeholder(app, ui, now, 1, 48.0);
                return;
            }
            let Some(up) = &app.uploads else { return };
            if up.is_empty() {
                ui.add_space(20.0);
                ui.label(RichText::new("No uploads yet").color(theme::text_dim()));
            }
            list(ui, &rows, &app.liked, up, true, plain, ListCtx { sel: &mut app.selection, page: &app.page, own: false }, &mut app.actions);
        }
        LibTab::Songs => {
            if app.opened(super::LIKED_MUSIC).is_none() {
                placeholder(app, ui, now, 1, 48.0);
                return;
            }
            let more = app.receiving(super::LIKED_MUSIC);
            let Some(o) = app.lists.get(super::LIKED_MUSIC) else { return };
            let liked = &o.c.tracks;
            if !liked.is_empty() {
                ui.horizontal(|ui| {
                    if pill(ui, "Play", Some(icon::PLAY), true).clicked() {
                        app.actions.push(Action::Play(liked.clone(), 0));
                    }
                    if pill(ui, "Shuffle", Some(icon::SHUFFLE), false).clicked() {
                        let mut t = liked.clone();
                        super::shuffle(&mut t);
                        app.actions.push(Action::Play(t, 0));
                    }
                    select_pill(ui, &mut app.selection, &app.page);
                });
                ui.add_space(10.0);
            }
            list(ui, &rows, &app.liked, liked, true, plain, ListCtx { sel: &mut app.selection, page: &app.page, own: false }, &mut app.actions);
            if more {
                widgets::skeleton_rows(ui, now, 3);
            }
        }
        _ => {
            if app.library.is_none() && tab != LibTab::Playlists {
                placeholder(app, ui, now, 2, CARD);
                return;
            }
            match tab {
                LibTab::Playlists => {
                    ui.horizontal(|ui| {
                        if (app.logged_in || app.create_playlists_locally) && pill(ui, "New playlist", None, false).clicked() {
                            app.actions.push(Action::NewPlaylist(Vec::new()));
                        }
                        view_pill(ui, &mut app.lib_list);
                    });
                    ui.add_space(14.0);
                    let mut cards = app.local_playlist_cards();
                    cards.extend(app.library.as_ref().map(|lib| lib.playlists.iter()
                        .filter(|card| !matches!(&card.target, crate::model::Target::Playlist(id) if id.starts_with("nuvx-local:")))
                        .cloned().collect::<Vec<_>>()).unwrap_or_default());
                    grid(ui, &cards, &mut app.actions)
                }
                LibTab::Albums => {
                    view_pill(ui, &mut app.lib_list);
                    ui.add_space(14.0);
                    if let Some(lib) = &app.library { grid(ui, &lib.albums, &mut app.actions) }
                }
                LibTab::Songs | LibTab::History | LibTab::Uploads => unreachable!(),
            }
        }
    }
}

pub(super) fn downloads(app: &mut App, ui: &mut Ui, now: f64) {
    page_title(ui, "Downloads");
    let songs = crate::downloads::list();
    ui.add_space(4.0);
    ui.label(
        RichText::new(format!("{} songs \u{2022} {}", songs.len(), crate::downloads::folder().display()))
            .font(theme::regular(13.0))
            .color(theme::text_dim()),
    );
    ui.add_space(12.0);
    if songs.is_empty() {
        ui.label(RichText::new("No downloads yet").font(theme::semibold(16.0)).color(theme::text()));
        ui.add_space(4.0);
        ui.label(
            RichText::new("Right-click a song and choose Download to keep it on this computer, playable offline.")
                .font(theme::regular(13.0))
                .color(theme::text_dim()),
        );
        ui.add_space(10.0);
        if pill(ui, "Open folder", Some(icon::FOLDER_OPEN), false).clicked() {
            crate::downloads::open_folder();
        }
        return;
    }
    ui.horizontal(|ui| {
        if pill(ui, "Play", Some(icon::PLAY), true).clicked() {
            app.actions.push(Action::Play(songs.clone(), 0));
        }
        if pill(ui, "Shuffle", Some(icon::SHUFFLE), false).clicked() {
            let mut t = songs.clone();
            super::shuffle(&mut t);
            app.actions.push(Action::Play(t, 0));
        }
        if pill(ui, "Open folder", Some(icon::FOLDER_OPEN), false).clicked() {
            crate::downloads::open_folder();
        }
    });
    ui.add_space(12.0);
    let rows = Rows::new(app, now);
    list(ui, &rows, &app.liked, &songs, true, |_, _| None, ListCtx { sel: &mut app.selection, page: &app.page, own: false }, &mut app.actions);
}
