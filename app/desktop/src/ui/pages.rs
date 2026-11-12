use std::collections::HashSet;
use std::sync::atomic::Ordering;

use egui::{Align2, Color32, Rect, RichText, Sense, Ui, Vec2, pos2, vec2};

use super::widgets::{self, RowOpts, card, chip, h_scroll, named, paint_cover, pill, shelf_header, track_row};
use super::{Action, App, LibTab, Page, Selection};
use crate::backend::compact;
use crate::model::{Card, Target, Track};
use super::glyphs;
use crate::theme::{self, icon};

pub(super) const CARD: f32 = 180.0;

fn feed_columns(width: f32, square: bool) -> usize {
    let viewport = width + 260.0;
    match square {
        true if viewport < 1260.0 => 4,
        true if viewport < 1580.0 => 5,
        true => 6,
        false if viewport < 1260.0 => 2,
        false if viewport < 1580.0 => 3,
        false => 4,
    }
}

pub(super) fn feed_tile(ui: &Ui, square: bool) -> f32 {
    let width = ui.available_width();
    let min = if square { 144.0 } else { 270.0 };
    if width < 1024.0 {
        return min;
    }
    let cols = feed_columns(width, square);
    ((width - 20.0 * (cols - 1) as f32) / cols as f32).max(min)
}

pub fn page(app: &mut App, ui: &mut Ui, now: f64) {
    let t = theme::ease_out(((now - app.page_changed) / 0.32) as f32);
    if t < 1.0 {
        ui.ctx().request_repaint();
    }
    ui.set_opacity(0.15 + 0.85 * t);
    let page = app.page.clone();
    let mut area = egui::ScrollArea::vertical().id_salt(("page", &page)).auto_shrink(false);
    ui.spacing_mut().scroll.bar_outer_margin += super::titlebar::edge_inset();
    if let Some(y) = super::dev_var("NUVX_SCROLL").and_then(|v| v.parse::<f32>().ok()) {
        area = area.vertical_scroll_offset(y);
    }
    let out = area.show(ui, |ui| {
        ui.set_max_width((ui.available_width() - 20.0).max(0.0));
        ui.add_space(18.0 * (1.0 - t));
        match &page {
            Page::Home => super::feed_pages::home(app, ui, now),
            Page::New => super::feed_pages::new(app, ui, now),
            Page::Search(q) => super::search_pages::search(app, ui, q, now),
            Page::Mood { browse_id, params, title } => super::feed_pages::mood(app, ui, browse_id, params.as_ref(), title, now),
            Page::Radio => super::radio::page(app, ui, now),
            Page::Album(id) | Page::Playlist(id) => collection(app, ui, id, now),
            Page::Artist(id) => artist(app, ui, id, now),
            Page::Library(tab) => library(app, ui, *tab, now),
            Page::Downloads => downloads(app, ui, now),
            Page::Settings => super::settings_pages::settings(app, ui, None),
            Page::Setting(pane) => super::settings_pages::settings(app, ui, Some(*pane)),
            Page::Transfer => super::transfer::page(app, ui, now),
            Page::Jam => super::jam::page(app, ui),
        }
        ui.add_space(96.0);
    });
    selection_bar(app, ui.ctx(), &page, out.inner_rect);
    if page == Page::Home {
        let remaining = out.content_size.y - (out.state.clamped_offset().y + out.inner_rect.height());
        if let Some(h) = &mut app.home {
            if h.loading_more.is_some_and(|t| now - t > 20.0) {
                h.loading_more = None;
            }
            if remaining < 700.0 && h.loading_more.is_none()
                && let Some(token) = h.continuation.clone()
            {
                h.loading_more = Some(now);
                app.backend.request(crate::backend::Req::HomeMore(token));
            }
        }
    }
}

pub(super) struct Rows {
    current: Option<String>,
    playing: bool,
    accent: Color32,
    now: f64,
}

impl Rows {
    pub(super) fn new(app: &App, now: f64) -> Self {
        Self {
            current: app.current_id().map(str::to_owned),
            playing: app.audio.status.playing.load(Ordering::Relaxed),
            accent: app.accent_color(),
            now,
        }
    }

    pub(super) fn opts(&self, liked: &HashSet<String>, t: &Track, number: Option<usize>, show_album: bool) -> RowOpts {
        RowOpts {
            number,
            show_album,
            current: self.current.as_deref() == Some(t.id.as_str()),
            playing: self.playing,
            accent: self.accent,
            time: self.now,
            liked: liked.contains(&t.id),
            added_by: None,
            pick: false,
            selected: None,
            remove: None,
        }
    }
}

pub(super) fn placeholder(app: &mut App, ui: &mut Ui, now: f64, rows: usize, card: f32) {
    if app.load_failed {
        ui.add_space(40.0);
        ui.label(RichText::new("Couldn't load this page").color(theme::text_dim()));
        ui.add_space(12.0);
        if pill(ui, "Try again", None, false).clicked() {
            app.refresh();
        }
    } else {
        widgets::skeleton(ui, now, rows, card);
    }
}

enum CircleGlyph {
    Char(char),
    More,
}

fn circle_action(ui: &mut Ui, glyph: CircleGlyph, name: &str, active: bool) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(38.0), Sense::click());
    let resp = named(resp, name).on_hover_text(name).on_hover_cursor(egui::CursorIcon::PointingHand);
    let open = egui::Popup::is_id_open(ui.ctx(), egui::Popup::default_response_id(&resp));
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered() || open, 0.15);
    let press = if resp.is_pointer_button_down_on() { 18.3 } else { 19.0 };
    ui.painter().circle_filled(rect.center(), press, theme::glass(0.10 + 0.09 * h));
    let color = theme::lerp_color(theme::text_dim(), theme::text(), h.max(active as u8 as f32));
    match glyph {
        CircleGlyph::Char(g) => widgets::icon_at(ui, rect.center(), g, 16.0, color),
        CircleGlyph::More => {
            for i in -1..=1 {
                ui.painter().circle_filled(rect.center() + vec2(i as f32 * 5.2, 0.0), 1.8, color);
            }
        }
    }
    resp
}

fn play_pill(ui: &mut Ui, acts: &mut Vec<Action>, target: &Target, tracks: &[Track]) {
    let active = widgets::active_state(ui, target);
    let (label, glyph) = if active == Some(true) { ("Pause", icon::PAUSE) } else { ("Play", icon::PLAY) };
    if pill(ui, label, Some(glyph), true).clicked() {
        if active.is_some() {
            acts.push(Action::TogglePlay);
        } else if !tracks.is_empty() {
            acts.push(Action::Play(tracks.to_vec(), 0));
        }
    }
}

pub(super) fn card_row(ui: &mut Ui, id: &str, title: &str, cards: &[Card], acts: &mut Vec<Action>) {
    if cards.is_empty() {
        return;
    }
    let (l, r) = shelf_header(ui, title, None, None, true);
    let nudge = r as i32 - l as i32;
    let tile = feed_tile(ui, true);
    h_scroll(ui, id, nudge, cards.len(), tile, 20.0, |ui| {
        ui.spacing_mut().item_spacing.x = 20.0;
        for c in cards {
            card(ui, c, tile, false, acts);
        }
    });
}

#[allow(clippy::too_many_arguments)]
struct ListCtx<'a> {
    sel: &'a mut Selection,
    page: &'a Page,
    own: bool,
}

#[allow(clippy::too_many_arguments)]
fn list(ui: &mut Ui, rows: &Rows, liked: &HashSet<String>, tracks: &[Track], show_album: bool, number: impl Fn(usize, &Track) -> Option<usize>, cx: ListCtx, acts: &mut Vec<Action>) {
    let ListCtx { sel, page, own } = cx;
    let selecting = sel.on(page);
    let mods = ui.input(|i| i.modifiers);
    widgets::visible_rows(ui, tracks.len(), |ui, i| {
        let t = &tracks[i];
        let o = RowOpts {
            selected: selecting.then(|| sel.rows.contains(&i)),
            remove: own.then_some(i),
            ..rows.opts(liked, t, number(i, t), show_album)
        };
        if track_row(ui, t, &o, acts).clicked() {
            if selecting || mods.command || mods.shift {
                sel.pick(page, i, mods.shift);
            } else {
                acts.push(Action::Play(tracks.to_vec(), i));
            }
        }
    });
}

fn select_pill(ui: &mut Ui, sel: &mut Selection, page: &Page) {
    let on = sel.on(page);
    if pill(ui, if on { "Done" } else { "Select" }, Some(icon::LIST_CHECKS), false).clicked() {
        if on { sel.clear() } else { sel.start(page) }
    }
}

fn selectable<'a>(app: &'a App, page: &Page) -> Option<(&'a [Track], bool)> {
    match page {
        Page::Album(id) | Page::Playlist(id) => {
            app.opened(id).map(|o| &o.c).map(|c| (c.tracks.as_slice(), app.logged_in && c.owned && !c.is_album))
        }
        Page::Library(LibTab::Songs) => app.opened(super::LIKED_MUSIC).map(|o| (o.c.tracks.as_slice(), false)),
        Page::Library(LibTab::History) => app.history.as_deref().map(|h| (h, false)),
        Page::Library(LibTab::Uploads) => app.uploads.as_deref().map(|u| (u, false)),
        _ => None,
    }
}

fn selection_bar(app: &mut App, ctx: &egui::Context, page: &Page, area: Rect) {
    if !app.selection.on(page) {
        return;
    }
    let Some((tracks, own)) = selectable(app, page) else { return };
    let n = tracks.len();
    let picked: Vec<Track> = app.selection.rows.iter().filter_map(|&i| tracks.get(i).cloned()).collect();
    let all = !picked.is_empty() && picked.len() == n;
    let all_liked = !picked.is_empty() && picked.iter().all(|t| app.liked.contains(&t.id));
    let wide = area.width() > 820.0;
    let button = |ui: &mut Ui, label: &str, glyph: char, primary: bool| {
        if wide { pill(ui, label, Some(glyph), primary) } else { widgets::icon_button(ui, glyph, 18.0, label, false) }
    };
    let mut acts = Vec::new();
    let mut toggle_all = false;
    let mut done = ctx.input(|i| i.key_pressed(egui::Key::Escape));
    egui::Area::new(egui::Id::unique("selection-bar"))
        .order(egui::Order::Foreground)
        .pivot(Align2::CENTER_BOTTOM)
        .fixed_pos(pos2(area.center().x, area.bottom() - 18.0))
        .show(ctx, |ui| {
            egui::Frame::new()
                .fill(theme::menu_fill())
                .corner_radius(14)
                .inner_margin(egui::Margin { left: 20, right: 10, top: 8, bottom: 8 })
                .shadow(egui::epaint::Shadow { offset: [0, 8], blur: 24, spread: 0, color: theme::shade(0.45) })
                .show(ui, |ui| {
                    ui.with_layout(egui::Layout::left_to_right(egui::Align::Center), |ui| {
                        ui.spacing_mut().item_spacing.x = 8.0;
                        let count = if picked.is_empty() { "Select songs".to_owned() } else { format!("{} selected", picked.len()) };
                        ui.label(RichText::new(count).font(theme::semibold(14.0)).color(theme::text()));
                        ui.add_space(10.0);
                        if !picked.is_empty() {
                            if button(ui, "Add to playlist", icon::LIST_PLUS, true).clicked() {
                                acts.push(Action::AddToPlaylist(picked.clone()));
                            }
                            if own && button(ui, "Remove from playlist", icon::LIST_MINUS, false).clicked() {
                                acts.push(Action::RemoveFromPlaylist(app.selection.rows.iter().copied().collect()));
                            }
                            if button(ui, if all_liked { "Remove like" } else { "Like" }, icon::HEART, false).clicked() {
                                acts.push(Action::LikeMany(picked.clone(), !all_liked));
                            }
                            if button(ui, "Add to queue", icon::LIST_MUSIC, false).clicked() {
                                acts.extend(picked.iter().cloned().map(Action::Enqueue));
                            }
                        }
                        toggle_all = button(ui, if all { "Select none" } else { "Select all" }, icon::LIST_CHECKS, false).clicked();
                        done |= widgets::icon_button(ui, icon::X, 18.0, "Done selecting", false).clicked();
                    });
                });
        });
    if toggle_all {
        let page = page.clone();
        app.selection.start(&page);
        if !all {
            app.selection.rows = (0..n).collect();
        }
    }
    if done || !acts.is_empty() {
        app.selection.clear();
    }
    app.actions.extend(acts);
}

pub(super) fn page_title(ui: &mut Ui, title: &str) {
    ui.add_space(18.0);
    let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 42.0), Sense::hover());
    ui.painter().text(pos2(rect.left(), rect.center().y), Align2::LEFT_CENTER, title, theme::bold(34.0), theme::text());
}

fn collection(app: &mut App, ui: &mut Ui, id: &str, now: f64) {
    if app.opened(id).is_none() {
        placeholder(app, ui, now, 1, 232.0);
        return;
    }
    let rows = Rows::new(app, now);
    let (release, artist) = match app.opened(id) {
        Some(o) => (o.c.title.clone(), o.c.artists.first().map(|a| a.name.clone()).unwrap_or_else(|| o.c.byline())),
        None => (String::new(), String::new()),
    };
    let px = (232.0 * ui.ctx().pixels_per_point()).round().max(256.0) as u32;
    let sprite = app.album_canvas(ui.ctx(), &release, &artist, px);
    let Some(o) = app.lists.get(id) else { return };
    let (c, more) = (&o.c, app.receiving(id));
    let acts = &mut app.actions;
    let tracks = &c.tracks;
    let is_album = c.is_album;
    let filter_on = app.coll_search.as_ref().is_some_and(|(fid, _)| fid == id);
    let selecting = app.selection.on(&app.page);
    let mut toggle_search = false;
    let mut start_select = false;
    let mut stop_select = false;
    ui.add_space(18.0);
    ui.horizontal_top(|ui| {
        let (img, _) = ui.allocate_exact_size(Vec2::splat(232.0), Sense::hover());
        widgets::soft_shadow(ui, img, 10.0, 40.0, 0.5);
        paint_cover(ui, img, &c.thumbs, theme::card_radius() + 2.0, 1.0);
        if let Some(s) = &sprite {
            widgets::paint_motion(ui, img, theme::card_radius() + 2.0, s);
        }
        ui.add_space(26.0);
        ui.vertical(|ui| {
            ui.set_max_width(ui.available_width().min(760.0));
            ui.add_space(24.0);
            ui.label(RichText::new(if is_album { "ALBUM" } else { "PLAYLIST" }).font(theme::semibold(12.0)).color(theme::text_dim()));
            ui.label(RichText::new(&c.title).font(theme::bold(40.0)).color(theme::text()));
            ui.add_space(2.0);
            let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), 20.0), Sense::hover());
            widgets::linked_text(ui, r.min, &c.byline(), &c.artists, theme::regular(15.0), theme::text_dim(), r.width(), acts);
            let total: u32 = tracks.iter().filter_map(|t| t.duration).sum();
            if total > 0 {
                ui.label(
                    RichText::new(format!("{} songs • {} min", tracks.len(), total / 60)).font(theme::regular(13.5)).color(theme::text_faint()),
                );
            }
            if let Some(d) = c.description.as_deref().filter(|d| !d.trim().is_empty()) {
                ui.add_space(6.0);
                let g = widgets::galley_wrapped(ui, d.trim(), theme::regular(13.5), theme::text_dim(), ui.available_width(), 2);
                ui.label(g);
            }
            ui.add_space(16.0);
            let target = if is_album { Target::Album(id.to_owned()) } else { Target::Playlist(id.to_owned()) };
            let auto = matches!(id, "LM" | "SE");
            let saved = app.library.as_ref().is_some_and(|l| {
                let cards = if is_album { &l.albums } else { &l.playlists };
                cards.iter().any(|card| card.target == target)
            });
            let save_id = if is_album { c.save_id.clone() } else { (!c.owned).then(|| id.to_owned()) };
            let owned = !is_album && c.owned;
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing = vec2(10.0, 0.0);
                play_pill(ui, acts, &target, tracks);
                if circle_action(ui, CircleGlyph::Char(icon::SHUFFLE), "Shuffle", false).clicked() && !tracks.is_empty() {
                    let mut t = tracks.clone();
                    super::shuffle(&mut t);
                    acts.push(Action::Play(t, 0));
                }
                if !tracks.is_empty() && circle_action(ui, CircleGlyph::Char(icon::DOWNLOAD), "Download", false).clicked() {
                    acts.push(Action::DownloadAll(c.title.clone(), vec![target.clone()]));
                }
                if circle_action(ui, CircleGlyph::Char(icon::SEARCH), "Search in this list", filter_on).clicked() {
                    toggle_search = true;
                }
                let more = circle_action(ui, CircleGlyph::More, "More", false);
                egui::Popup::menu(&more).show(|ui| {
                    ui.set_width(226.0);
                    ui.add_space(3.0);
                    if let Some(first) = tracks.first()
                        && widgets::menu_action(ui, Some(icon::RADIO), "Start radio")
                    {
                        acts.push(Action::Radio(first.clone()));
                    }
                    if let Some(save_id) = save_id.clone()
                        && widgets::menu_action(ui, Some(icon::LIBRARY), if saved { "Remove from library" } else { "Save to library" })
                    {
                        acts.push(Action::SaveCollection(save_id, !saved));
                    }
                    if app.logged_in && !auto && !tracks.is_empty() {
                        if widgets::menu_action(ui, Some(icon::LIST_CHECKS), if selecting { "Done selecting" } else { "Select" }) {
                            if selecting { stop_select = true } else { start_select = true }
                        }
                    }
                    if app.logged_in && !auto && (save_id.is_some() || owned) {
                        ui.separator();
                    }
                    if owned {
                        if widgets::menu_action(ui, Some(icon::MUSIC), "Edit playlist") {
                            acts.push(Action::EditPlaylist);
                        }
                        if widgets::menu_action(ui, Some(icon::USERS), "Collaborate") {
                            acts.push(Action::Collaborate);
                        }
                        if widgets::menu_action(ui, Some(icon::TRASH_2), "Delete playlist") {
                            acts.push(Action::DeletePlaylist);
                        }
                    }
                    if app.logged_in && !auto && !owned && save_id.is_none() {
                        ui.separator();
                    }
                    if widgets::menu_action(ui, Some(icon::LINK), "Share") {
                        acts.push(Action::Share(target.clone()));
                    }
                    ui.add_space(3.0);
                });
            });
            if toggle_search {
                let closing = app.coll_search.as_ref().is_some_and(|(fid, _)| fid == id);
                app.coll_search = (!closing).then(|| (id.to_owned(), String::new()));
            }
            if start_select {
                app.selection.start(&app.page);
            }
            if stop_select {
                app.selection.clear();
            }

        });
    });
    let mut dismiss_search = false;
    if let Some((fid, query)) = &mut app.coll_search
        && fid == id
    {
        ui.add_space(14.0);
        let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width().min(420.0), 40.0), Sense::hover());
        ui.painter().rect_filled(rect, 20.0, theme::glass(0.09));
        widgets::icon_at(ui, pos2(rect.left() + 20.0, rect.center().y), icon::SEARCH, 16.0, theme::text_dim());
        let clearable = !query.is_empty();
        let inner = Rect::from_min_max(pos2(rect.left() + 40.0, rect.top() + 4.0), pos2(rect.right() - if clearable { 40.0 } else { 16.0 }, rect.bottom() - 4.0));
        let edit = ui.put(
            inner,
            egui::TextEdit::singleline(query)
                .hint_text(egui::RichText::new("Search in this list").color(theme::text_faint()))
                .font(theme::regular(14.0))
                .align(egui::Align2::LEFT_CENTER)
                .frame(egui::Frame::NONE)
                .desired_width(inner.width()),
        );
        if toggle_search {
            edit.request_focus();
        } else if !edit.has_focus() && query.is_empty() {
            dismiss_search = true;
        }
        if clearable {
            let x = Rect::from_center_size(pos2(rect.right() - 20.0, rect.center().y), Vec2::splat(26.0));
            let resp = named(ui.interact(x, ui.scope_id().with("coll-clear"), Sense::click()), "Clear search").on_hover_cursor(egui::CursorIcon::PointingHand);
            widgets::icon_at(ui, x.center(), icon::X, 14.0, theme::text_dim());
            if resp.clicked() {
                query.clear();
            }
        }
        ui.add_space(18.0);
    } else {
        ui.add_space(26.0);
    }
    if dismiss_search {
        app.coll_search = None;
    }
    let needle = app.coll_search.as_ref().filter(|(fid, _)| fid == id).map(|(_, q)| q.trim().to_lowercase()).unwrap_or_default();
    let filtered: Vec<Track> = if needle.is_empty() {
        Vec::new()
    } else {
        c.tracks
            .iter()
            .filter(|t| {
                t.title.to_lowercase().contains(&needle)
                    || t.artist_line().to_lowercase().contains(&needle)
                    || t.album.as_ref().is_some_and(|a| a.name.to_lowercase().contains(&needle))
            })
            .cloned()
            .collect()
    };
    let shown: &[Track] = if needle.is_empty() { tracks } else { &filtered };
    let number = |i: usize, t: &Track| is_album.then(|| t.track_nr.map_or(i + 1, |n| n as usize));
    let own = app.logged_in && c.owned && !is_album;
    list(ui, &rows, &app.liked, shown, !is_album, number, ListCtx { sel: &mut app.selection, page: &app.page, own }, acts);
    if more {
        widgets::skeleton_rows(ui, now, 3);
    }
}

fn wordmark(ui: &Ui, base: egui::Pos2, logo: &crate::apple::LogoSrc, max_w: f32) -> bool {
    let aspect = logo.w.max(1) as f32 / logo.h.max(1) as f32;
    let mut size = vec2(72.0 * aspect, 72.0);
    if size.x > max_w {
        size = vec2(max_w, max_w / aspect);
    }
    let url = logo.at_height((size.y * 2.0) as u32);
    let ready = matches!(egui::Image::new(url.as_str()).load_for_size(ui.ctx(), size), Ok(egui::load::TexturePoll::Ready { .. }));
    if ready {
        let rect = Rect::from_min_size(pos2(base.x, base.y - 40.0 - size.y), size);
        let uv = Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0));
        widgets::paint_remote(ui, &url, rect, 0.0, uv, Color32::WHITE);
    }
    ready
}

fn artist(app: &mut App, ui: &mut Ui, id: &str, now: f64) {
    if app.artist.as_ref().is_none_or(|a| a.id != id) {
        placeholder(app, ui, now, 2, CARD);
        return;
    }
    let rows = Rows::new(app, now);
    let accent = app.accent_color();
    let apple = app.apple_artists.get(id).and_then(|o| o.clone());
    let name = apple.as_ref().map(|x| x.name.clone()).unwrap_or_else(|| app.artist.as_ref().map(|a| a.name.clone()).unwrap_or_default());
    if apple.is_none() {
        let key = format!("artist:{id}");
        let cooling = app.apple_missed.get(&key).is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(120));
        if !cooling && app.apple_waiting.insert(key) {
            app.backend.request(crate::backend::Req::AppleArtist { id: id.to_owned(), name: name.clone() });
        }
    }
    let signature = app.signature_texture(ui.ctx(), &name);
    let Some(a) = &app.artist else { return };
    let acts = &mut app.actions;
    let top = &a.top;
    let w = ui.available_width();
    let hero_h = (w * 0.34).clamp(240.0, 380.0);
    let (hero, _) = ui.allocate_exact_size(vec2(w, hero_h), Sense::hover());
    if let Some(t) = a.banner.iter().max_by_key(|t| t.width) {
        let img_ratio = if t.height > 0 { t.width as f32 / t.height as f32 } else { 16.0 / 9.0 };
        let vis_h = (img_ratio / (w / hero_h)).min(1.0);
        let uv = Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, vis_h));
        ui.painter().rect_filled(hero, 14.0, theme::glass(0.07));
        widgets::paint_remote(ui, &t.url, hero, 14.0, uv, Color32::WHITE);
    } else {
        ui.painter().rect_filled(hero, 14.0, theme::with_alpha(accent, 0.25));
    }
    super::shell::vgradient(ui.painter(), Rect::from_min_max(pos2(hero.left(), hero.center().y - 40.0), hero.max), Color32::TRANSPARENT, theme::shade(0.85));
    let base = hero.left_bottom() + vec2(28.0, -30.0);
    let drawn = apple.as_ref().and_then(|x| x.logo.as_ref()).is_some_and(|logo| wordmark(ui, base, logo, w * 0.62));
    if !drawn {
        match &signature {
            Some(tex) => {
                let tex_size = tex.size_vec2();
                let aspect = tex_size.x / tex_size.y.max(1.0);
                let mut size = vec2(96.0 * aspect, 96.0);
                let max_w = (w * 0.42).min(340.0);
                if size.x > max_w {
                    size = vec2(max_w, max_w / aspect);
                }
                let rect = Rect::from_min_size(pos2(base.x, base.y - 40.0 - size.y), size);
                ui.painter().text(pos2(base.x, rect.top() - 8.0), Align2::LEFT_BOTTOM, format!("Signed by {name}"), theme::regular(12.0), Color32::from_white_alpha(199));
                let tex = egui::load::SizedTexture::from_handle(tex);
                egui::Image::from_texture(tex).uv(Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0))).paint_at(ui, rect);
            }
            None => {
                ui.painter().text(base - vec2(0.0, 44.0), Align2::LEFT_BOTTOM, &name, theme::bold(58.0), Color32::WHITE);
            }
        }
    }
    if let Some(s) = a.subscribers {
        ui.painter().text(base - vec2(0.0, 18.0), Align2::LEFT_BOTTOM, format!("{} subscribers", compact(s)), theme::regular(14.0), theme::text_dim());
    }
    ui.add_space(18.0);
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 10.0;
        play_pill(ui, acts, &Target::Artist(id.to_owned()), top);
        if pill(ui, "Shuffle", Some(icon::SHUFFLE), false).clicked() && !top.is_empty() {
            let mut t = top.clone();
            super::shuffle(&mut t);
            acts.push(Action::Play(t, 0));
        }
        if let Some(first) = top.first()
            && pill(ui, "Radio", Some(icon::RADIO), false).clicked() {
                acts.push(Action::Radio(first.clone()));
            }
        let mut releases: Vec<Target> = a.albums.iter().map(|c| c.target.clone()).filter(|t| matches!(t, Target::Album(_))).collect();
        if releases.is_empty() {
            releases.extend(a.top_playlist.clone().map(Target::Playlist));
        }
        if !releases.is_empty() && pill(ui, "Download", Some(icon::DOWNLOAD), false).clicked() {
            acts.push(Action::DownloadAll(a.name.clone(), releases));
        }
    });
    if !top.is_empty() {
        ui.add_space(26.0);
        ui.horizontal(|ui| {
            ui.label(RichText::new("Top songs").font(theme::bold(24.0)).color(theme::text()));
            if let Some(pl) = &a.top_playlist {
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if pill(ui, "Show all", None, false).clicked() {
                        acts.push(Action::Open(Target::Playlist(pl.clone())));
                    }
                });
            }
        });
        ui.add_space(12.0);
        for (i, t) in top.iter().enumerate().take(5) {
            let o = rows.opts(&app.liked, t, None, true);
            if track_row(ui, t, &o, acts).clicked() {
                acts.push(Action::Play(top.clone(), i));
            }
        }
    }
    card_row(ui, "a-albums", "Albums & singles", &a.albums, acts);
    card_row(ui, "a-pl", "Featured on", &a.playlists, acts);
    card_row(ui, "a-sim", "Fans might also like", &a.similar, acts);
    if let Some(d) = a.description.as_deref().filter(|d| !d.trim().is_empty()) {
        shelf_header(ui, "About", None, None, false);
        let full = ui.available_width().min(820.0);
        let beside = drawn.then_some(signature.as_ref()).flatten();
        if let Some(tex) = beside {
            ui.horizontal_top(|ui| {
                let sig_w = (full * 0.28).clamp(120.0, 210.0);
                let text_w = (full - sig_w - 32.0).max(220.0);
                let g = widgets::galley_wrapped(ui, d.trim(), theme::regular(14.5), theme::text_dim(), text_w, 8);
                ui.label(g);
                ui.add_space(32.0);
                let tex_size = tex.size_vec2();
                let aspect = tex_size.x / tex_size.y.max(1.0);
                let mut size = vec2(sig_w, sig_w / aspect);
                if size.y > 140.0 {
                    size = vec2(140.0 * aspect, 140.0);
                }
                let (rect, _) = ui.allocate_exact_size(vec2(size.x, size.y + 22.0), Sense::hover());
                ui.painter().text(rect.left_top(), Align2::LEFT_TOP, format!("Signed by {name}"), theme::regular(12.0), Color32::from_white_alpha(199));
                let img = Rect::from_min_size(pos2(rect.left(), rect.top() + 22.0), size);
                let tex = egui::load::SizedTexture::from_handle(tex);
                egui::Image::from_texture(tex).uv(Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0))).paint_at(ui, img);
            });
        } else {
            let g = widgets::galley_wrapped(ui, d.trim(), theme::regular(14.5), theme::text_dim(), full, 8);
            ui.label(g);
        }
    }
}

fn library(app: &mut App, ui: &mut Ui, tab: LibTab, now: f64) {
    page_title(ui, "Library");
    if !app.logged_in {
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
    let grid = |ui: &mut Ui, cards: &[Card], acts: &mut Vec<Action>| {
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
            if app.library.is_none() {
                placeholder(app, ui, now, 2, CARD);
                return;
            }
            let Some(lib) = &app.library else { return };
            match tab {
                LibTab::Playlists => {
                    if pill(ui, "New playlist", None, false).clicked() {
                        app.actions.push(Action::NewPlaylist(Vec::new()));
                    }
                    ui.add_space(14.0);
                    grid(ui, &lib.playlists, &mut app.actions)
                }
                LibTab::Albums => grid(ui, &lib.albums, &mut app.actions),
                LibTab::Songs | LibTab::History | LibTab::Uploads => unreachable!(),
            }
        }
    }
}

fn downloads(app: &mut App, ui: &mut Ui, now: f64) {
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

pub(super) fn thousands(n: u64) -> String {
    let s = n.to_string();
    let mut out = String::new();
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(c);
    }
    out
}

pub(super) fn section_label(ui: &mut Ui, title: &str, value: Option<&str>) {
    ui.add_space(26.0);
    let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 16.0), Sense::hover());
    ui.painter().text(pos2(rect.left(), rect.center().y), Align2::LEFT_CENTER, title.to_uppercase(), theme::bold(11.0), theme::text_dim());
    if let Some(v) = value {
        ui.painter().text(pos2(rect.right(), rect.center().y), Align2::RIGHT_CENTER, v, theme::semibold(11.5), theme::text_dim());
    }
    ui.add_space(2.0);
}

pub(super) fn settings_card(ui: &mut Ui, body: impl FnOnce(&mut Ui)) {
    egui::Frame::new()
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            body(ui);
        });
}

pub(super) fn setting_row(ui: &mut Ui, title: &str, desc: Option<&str>, control: impl FnOnce(&mut Ui)) {
    glyph_row(ui, None, title, desc, control);
}

pub(super) fn glyph_row(ui: &mut Ui, glyph: Option<glyphs::Glyph>, title: &str, desc: Option<&str>, control: impl FnOnce(&mut Ui)) {
    let w = ui.available_width();
    let indent = if glyph.is_some() { 36.0 } else { 0.0 };
    let text_w = (w - 230.0 - indent).max(200.0);
    let title_g = widgets::galley(ui, title, theme::semibold(13.5), theme::text(), text_w);
    let desc_g = desc.map(|d| widgets::galley_wrapped(ui, d, theme::regular(11.5), theme::text_dim(), text_w, 3));
    let text_h = title_g.size().y + desc_g.as_ref().map_or(0.0, |g| g.size().y + 2.0);
    let h = (text_h + 18.0).max(46.0);
    let (rect, _) = ui.allocate_exact_size(vec2(w, h), Sense::hover());
    let fade = if ui.is_enabled() { 1.0 } else { 0.45 };
    let top = rect.center().y - text_h / 2.0;
    if let Some(g) = glyph {
        glyphs::draw(ui, pos2(rect.left() + 11.0, rect.center().y), 20.0, g, theme::with_alpha(theme::text(), fade));
    }
    let x = rect.left() + indent;
    ui.painter().galley(pos2(x, top), title_g.clone(), theme::with_alpha(theme::text(), fade));
    if let Some(g) = desc_g {
        ui.painter().galley(pos2(x, top + title_g.size().y + 2.0), g, theme::with_alpha(theme::text_dim(), fade));
    }
    let ctl = Rect::from_min_max(pos2(x + text_w + 20.0, rect.top()), rect.max);
    let mut cui = ui.new_child(egui::UiBuilder::new().max_rect(ctl).layout(egui::Layout::right_to_left(egui::Align::Center)));
    control(&mut cui);
}

pub(super) fn divider(_ui: &mut Ui) {}

pub(super) fn toggle(ui: &mut Ui, on: &mut bool, name: &str) -> egui::Response {
    let (rect, mut resp) = ui.allocate_exact_size(vec2(40.0, 24.0), Sense::click());
    if resp.clicked() {
        *on = !*on;
        resp.mark_changed();
    }
    let value = *on;
    resp.widget_info(|| egui::WidgetInfo::selected(egui::Role::CheckBox, ui.is_enabled(), value, name));
    let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
    let t = theme::anim_bool(ui.ctx(), resp.id.with("t"), value, 0.16);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
    let fade = if ui.is_enabled() { 1.0 } else { 0.4 };
    let off = theme::glass(0.16 + 0.06 * h);
    ui.painter().rect_filled(rect, 12.0, theme::with_alpha(theme::lerp_color(off, theme::text(), t), fade));
    let knob = pos2(egui::lerp(rect.left() + 12.0..=rect.right() - 12.0, t), rect.center().y);
    let knob_c = theme::lerp_color(theme::text(), theme::lerp_color(theme::text(), Color32::BLACK, 0.92), t);
    ui.painter().circle_filled(knob + vec2(0.0, 1.0), 9.0, theme::shade(0.2 * fade));
    ui.painter().circle_filled(knob, 9.0, theme::with_alpha(knob_c, fade));
    resp
}

