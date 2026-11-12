use std::collections::HashSet;
use std::sync::atomic::Ordering;

use egui::{Align2, Color32, Rect, RichText, Sense, Ui, pos2, vec2};

use super::widgets::{self, RowOpts, card, h_scroll, pill, shelf_header, track_row};
use super::{Action, App, LibTab, Page, Selection};
use crate::model::{Card, Track};
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
            Page::Album(id) | Page::Playlist(id) => super::collection_pages::collection(app, ui, id, now),
            Page::Artist(id) => super::collection_pages::artist(app, ui, id, now),
            Page::Library(tab) => super::library_pages::library(app, ui, *tab, now),
            Page::Downloads => super::library_pages::downloads(app, ui, now),
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
pub(super) struct ListCtx<'a> {
    pub(super) sel: &'a mut Selection,
    pub(super) page: &'a Page,
    pub(super) own: bool,
}

#[allow(clippy::too_many_arguments)]
pub(super) fn list(ui: &mut Ui, rows: &Rows, liked: &HashSet<String>, tracks: &[Track], show_album: bool, number: impl Fn(usize, &Track) -> Option<usize>, cx: ListCtx, acts: &mut Vec<Action>) {
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

