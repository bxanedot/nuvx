use std::collections::HashSet;

use egui::{Color32, Rect, RichText, Sense, Ui, pos2, vec2};

use super::pages::{card_row, feed_tile, page_title, placeholder, Rows, CARD};
use super::widgets::{self, card, h_scroll, shelf_header, track_row};
use super::{Action, App, Page};
use crate::model::{ShelfKind, Target, Track};
use crate::theme;

fn song_grid(ui: &mut Ui, rows_ctx: &Rows, liked: &HashSet<String>, acts: &mut Vec<Action>, id: &str, title: &str, strap: Option<&str>, tracks: &[Track], rows: usize) -> bool {
    let (l, r) = shelf_header(ui, title, strap, None, true);
    let nudge = r as i32 - l as i32;
    let col_w = feed_tile(ui, false);
    let mut picked = None;
    let (_, reached_end) = h_scroll(ui, id, nudge, tracks.len().div_ceil(rows.max(1)), col_w, 20.0, |ui| {
        ui.spacing_mut().item_spacing.x = 20.0;
        for (c, col) in tracks.chunks(rows.max(1)).enumerate() {
            ui.allocate_ui(vec2(col_w, rows as f32 * 60.0), |ui| {
                ui.set_width(col_w);
                ui.vertical(|ui| {
                    for (i, t) in col.iter().enumerate() {
                        let o = rows_ctx.opts(liked, t, None, false);
                        if track_row(ui, t, &o, acts).clicked() {
                            picked = Some(c * rows + i);
                        }
                    }
                });
            });
        }
    });
    if let Some(i) = picked {
        acts.push(Action::Radio(tracks[i].clone()));
    }
    reached_end
}

pub(super) fn home(app: &mut App, ui: &mut Ui, now: f64) {
    page_title(ui, "Home");
    if app.home.as_ref().is_none_or(|h| app.home_loading && h.shelves.is_empty()) {
        placeholder(app, ui, now, 3, CARD);
        return;
    }
    let rows = Rows::new(app, now);
    let Some(h) = &app.home else { return };
    if h.shelves.is_empty() {
        ui.add_space(40.0);
        ui.label(RichText::new("Nothing here yet").color(theme::text_dim()));
    }
    let mut more_picks = None;
    let no_videos = app.hide_home_videos;
    let is_video = |c: &crate::model::Card| matches!(&c.target, Target::Song(t) if t.music_video);
    for (i, s) in h.shelves.iter().enumerate() {
        if app.hide_quick_picks && s.title == "Quick picks" {
            continue;
        }
        let id = format!("home-{i}-{}", s.title);
        match &s.kind {
            ShelfKind::Cards(cards) => {
                let (strap, thumb) = if s.title == "Listen again" { (None, None) } else { (s.strapline.as_deref(), s.strap_thumb.as_deref()) };
                let (l, r) = shelf_header(ui, &s.title, strap, thumb, true);
                let nudge = r as i32 - l as i32;
                let tile = feed_tile(ui, true);
                let acts = &mut app.actions;
                let liked = &app.liked;
                let shown = cards.iter().filter(|c| !(no_videos && is_video(c))).count();
                h_scroll(ui, &id, nudge, shown, tile, 20.0, |ui| {
                    ui.spacing_mut().item_spacing.x = 20.0;
                    for c in cards.iter().filter(|c| !(no_videos && is_video(c))) {
                        let is_liked = matches!(&c.target, Target::Song(t) if liked.contains(&t.id));
                        card(ui, c, tile, is_liked, acts);
                    }
                });
            }
            ShelfKind::Songs { tracks, rows: n } => {
                let kept: Vec<Track>;
                let tracks = if no_videos { kept = tracks.iter().filter(|t| !t.music_video).cloned().collect(); &kept } else { tracks };
                let reached_end = song_grid(ui, &rows, &app.liked, &mut app.actions, &id, &s.title, s.strapline.as_deref(), tracks, *n);
                if s.title == "Quick picks" && reached_end && !h.picks_loading && !h.picks_expanded {
                    more_picks = tracks.last().map(|t| t.id.clone());
                }
            }
        }
    }
    let loading_more = h.loading_more.is_some();
    if let Some(seed) = more_picks
        && let Some(h) = &mut app.home
    {
        h.picks_loading = true;
        app.backend.request(crate::backend::Req::MoreQuickPicks(seed));
    }
    if loading_more {
        widgets::skeleton(ui, now, 1, CARD);
    }
}

pub(super) fn new(app: &mut App, ui: &mut Ui, now: f64) {
    page_title(ui, "Explore");
    if app.explore.is_none() {
        placeholder(app, ui, now, 2, CARD);
        return;
    }
    let rows = Rows::new(app, now);
    let Some(e) = &app.explore else { return };
    card_row(ui, "ex-new", if app.logged_in { "New releases for you" } else { "Popular new releases" }, &e.new_albums, &mut app.actions);
    if !e.top.is_empty() {
        song_grid(ui, &rows, &app.liked, &mut app.actions, "ex-top", "Top songs", None, &e.top, 4);
    }
    card_row(ui, "ex-charts", "Charts", &e.charts, &mut app.actions);
    if let Some(sections) = &app.moods {
        for s in sections {
            ui.add_space(26.0);
            ui.label(RichText::new(&s.title).font(theme::bold(24.0)).color(theme::text()));
            ui.add_space(12.0);
            mood_tiles(ui, &s.items, &mut app.actions);
        }
    }
}

pub(super) fn mood_tiles(ui: &mut Ui, items: &[crate::model::Mood], acts: &mut Vec<Action>) {
    const GAP: f32 = 14.0;
    let w = ui.available_width();
    let cols = ((w + GAP) / 214.0).floor().max(2.0) as usize;
    let tw = (w - GAP * (cols - 1) as f32) / cols as f32;
    for row in items.chunks(cols) {
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = GAP;
            for m in row {
                let (rect, resp) = ui.allocate_exact_size(vec2(tw, 84.0), Sense::click());
                let resp = widgets::named(resp, &m.title).on_hover_cursor(egui::CursorIcon::PointingHand);
                let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
                let base = m.color.map_or(theme::glass(0.14), |c| Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8));
                ui.painter().rect_filled(rect, 12.0, theme::lerp_color(base, Color32::WHITE, 0.10 * h));
                if let Some(url) = &m.thumb {
                    let img = Rect::from_min_max(pos2(rect.right() - rect.width() * 0.72, rect.top()), rect.max);
                    let uv = Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, (img.height() / img.width()).min(1.0)));
                    let round = egui::CornerRadius { nw: 0, sw: 0, ne: 12, se: 12 };
                    egui::Image::new(format!("gray:{url}")).uv(uv).corner_radius(round).tint(base).paint_at(ui, img);
                    ui.painter().rect_filled(img, round, theme::with_alpha(base, 0.25));
                    let (mid, foot, dark) = (rect.top() + rect.height() * 0.45, rect.bottom() - 12.0, theme::shade(0.28));
                    let mut mesh = egui::Mesh::default();
                    for (y, c) in [(mid, Color32::TRANSPARENT), (foot, dark)] {
                        mesh.colored_vertex(pos2(rect.left(), y), c);
                        mesh.colored_vertex(pos2(rect.right(), y), c);
                    }
                    mesh.add_triangle(0, 1, 2);
                    mesh.add_triangle(1, 3, 2);
                    ui.painter().add(mesh);
                    let strip = Rect::from_min_max(pos2(rect.left(), foot), rect.max);
                    ui.painter().rect_filled(strip, egui::CornerRadius { nw: 0, ne: 0, sw: 12, se: 12 }, dark);
                }
                let light = m.thumb.is_none() && 0.2126 * f32::from(base.r()) + 0.7152 * f32::from(base.g()) + 0.0722 * f32::from(base.b()) > 160.0;
                let ink = if light { Color32::from_rgb(18, 18, 18) } else { Color32::WHITE };
                let g = widgets::galley_wrapped(ui, &m.title, theme::bold(16.0), ink, tw - 28.0, 2);
                ui.painter().galley(pos2(rect.left() + 14.0, rect.bottom() - 12.0 - g.size().y), g, ink);
                if resp.clicked() {
                    acts.push(Action::Go(Page::Mood { browse_id: m.browse_id.clone(), params: m.params.clone(), title: m.title.clone() }));
                }
            }
        });
        ui.add_space(GAP);
    }
}

pub(super) fn mood(app: &mut App, ui: &mut Ui, browse_id: &str, params: Option<&String>, title: &str, now: f64) {
    let page = app.mood.as_ref().filter(|m| m.browse_id == browse_id && m.params.as_ref() == params);
    page_title(ui, page.and_then(|m| m.title.as_deref()).unwrap_or(title));
    let Some(m) = page else {
        placeholder(app, ui, now, 2, CARD);
        return;
    };
    let rows = Rows::new(app, now);
    if m.shelves.is_empty() {
        ui.add_space(18.0);
        ui.label(RichText::new("Nothing here yet").color(theme::text_dim()));
    }
    for (i, s) in m.shelves.iter().enumerate() {
        let id = format!("mood-{i}-{}", s.title);
        match &s.kind {
            ShelfKind::Cards(cards) => card_row(ui, &id, &s.title, cards, &mut app.actions),
            ShelfKind::Songs { tracks, rows: n } => {
                song_grid(ui, &rows, &app.liked, &mut app.actions, &id, &s.title, s.strapline.as_deref(), tracks, *n);
            }
        }
    }
}
