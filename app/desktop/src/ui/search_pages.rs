use std::collections::HashSet;

use egui::{Color32, Rect, RichText, Sense, Ui, Vec2, pos2, vec2};

use super::pages::{card_row, mood_tiles, page_title, placeholder, Rows, CARD};
use super::widgets::{self, named, paint_cover, track_row, RowOpts};
use super::{Action, App, Page};
use crate::model::{Card, Target};
use crate::theme;
fn youtube_results(app: &mut App, ui: &mut Ui, q: &str, pick: bool, now: f64) {
    let q = q.trim();
    let have = app.youtube_results.as_ref().filter(|(k, _)| k == q);
    if have.is_none() && app.youtube_asked != q {
        app.youtube_asked = q.to_owned();
        app.backend.request(crate::backend::Req::YoutubeSearch(q.to_owned()));
    }
    ui.add_space(14.0);
    let Some((_, found)) = have else {
        placeholder(app, ui, now, 2, CARD);
        return;
    };
    let tracks = match found {
        Ok(t) if t.is_empty() => {
            ui.label(RichText::new("No results").font(theme::bold(20.0)).color(theme::text()));
            return;
        }
        Ok(t) => t.clone(),
        Err(_) => {
            ui.label(RichText::new("Couldn't search YouTube").color(theme::text_dim()));
            ui.add_space(10.0);
            if widgets::pill(ui, "Retry", None, false).clicked() {
                app.youtube_results = None;
                app.youtube_asked.clear();
            }
            return;
        }
    };
    let rows = Rows::new(app, now);
    let acts = &mut app.actions;
    for (i, t) in tracks.iter().enumerate() {
        let o = RowOpts { pick, ..rows.opts(&app.liked, t, None, true) };
        if track_row(ui, t, &o, acts).clicked() {
            acts.push(if pick { Action::TransferPick(t.clone()) } else { Action::Play(tracks.clone(), i) });
        }
    }
}

pub(super) fn search(app: &mut App, ui: &mut Ui, q: &str, now: f64) {
    let pick = super::transfer::pick_banner(app, ui);
    page_title(ui, "Search");
    ui.add_space(10.0);
    if app.search_synced != q {
        app.search_synced = q.to_owned();
        app.search_text = q.trim().to_owned();
    }
    super::shell::search_field(app, ui, ui.available_width().min(560.0));
    if q.trim().is_empty() {
        ui.add_space(26.0);
        recent_searches(ui, app);
        if !app.classic_search
            && let Some(sections) = &app.moods
        {
            let mut seen = HashSet::new();
            let tiles: Vec<crate::model::Mood> =
                sections.iter().flat_map(|s| &s.items).filter(|m| seen.insert((m.browse_id.clone(), m.params.clone()))).cloned().collect();
            ui.add_space(30.0);
            ui.label(RichText::new("Browse all").font(theme::bold(22.0)).color(theme::text()));
            ui.add_space(12.0);
            mood_tiles(ui, &tiles, &mut app.actions);
        }
        return;
    }
    if app.youtube_search {
        ui.add_space(12.0);
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = 8.0;
            if widgets::chip(ui, "YouTube Music", !app.search_youtube).clicked() {
                app.search_youtube = false;
            }
            if widgets::chip(ui, "YouTube", app.search_youtube).clicked() {
                app.search_youtube = true;
            }
        });
        if app.search_youtube {
            return youtube_results(app, ui, q, pick, now);
        }
    }
    if app.search.as_ref().is_none_or(|r| r.query != q) {
        ui.add_space(14.0);
        placeholder(app, ui, now, 2, CARD);
        return;
    }
    let rows = Rows::new(app, now);
    let Some(r) = &app.search else { return };
    let acts = &mut app.actions;
    let liked = &app.liked;
    if r.tracks.is_empty() && r.artists.is_empty() && r.albums.is_empty() && r.playlists.is_empty() {
        ui.add_space(18.0);
        ui.label(RichText::new("No results").font(theme::bold(20.0)).color(theme::text()));
        return;
    }
    ui.add_space(14.0);
    ui.horizontal_top(|ui| {
        if let Some(top) = r.artists.first().filter(|a| a.title.eq_ignore_ascii_case(q.trim())) {
            top_result(ui, top, false, acts);
        } else if let Some(t) = r.tracks.first() {
            let c = Card {
                title: t.title.clone(),
                subtitle: format!("Song • {}", t.artist_line()),
                thumbs: t.thumbs.clone(),
                target: Target::Song(t.clone()),
                round: false,
                links: t.artists.clone(),
            };
            top_result(ui, &c, pick, acts);
        }
        ui.add_space(24.0);
        ui.vertical(|ui| {
            ui.label(RichText::new("Songs").font(theme::bold(24.0)));
            ui.add_space(8.0);
            for (i, t) in r.tracks.iter().take(4).enumerate() {
                let o = RowOpts { pick, ..rows.opts(liked, t, None, false) };
                if track_row(ui, t, &o, acts).clicked() {
                    acts.push(if pick { Action::TransferPick(t.clone()) } else { Action::Play(r.tracks.clone(), i) });
                }
            }
        });
    });
    card_row(ui, "s-artists", "Artists", &r.artists, acts);
    card_row(ui, "s-albums", "Albums", &r.albums, acts);
    if r.tracks.len() > 4 {
        ui.add_space(26.0);
        ui.label(RichText::new("More songs").font(theme::bold(24.0)));
        ui.add_space(10.0);
        for (i, t) in r.tracks.iter().enumerate().skip(4) {
            let o = RowOpts { pick, ..rows.opts(liked, t, None, true) };
            if track_row(ui, t, &o, acts).clicked() {
                acts.push(if pick { Action::TransferPick(t.clone()) } else { Action::Play(r.tracks.clone(), i) });
            }
        }
    }
    card_row(ui, "s-pl", "Playlists", &r.playlists, acts);
}

fn recent_searches(ui: &mut Ui, app: &mut App) {
    if app.recent_searches.is_empty() {
        ui.label(RichText::new("Search for artists, songs, albums and playlists.").font(theme::regular(15.0)).color(theme::text_dim()));
        return;
    }
    ui.label(RichText::new("Recently Searched").font(theme::bold(22.0)).color(theme::text()));
    ui.add_space(12.0);
    let w = ui.available_width().min(620.0);
    let recents = app.recent_searches.clone();
    let mut remove = None;
    for (i, r) in recents.iter().enumerate() {
        let (rect, resp) = ui.allocate_exact_size(vec2(w, 64.0), Sense::click());
        let resp = named(resp, &r.title).on_hover_cursor(egui::CursorIcon::PointingHand);
        let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.contains_pointer(), 0.12);
        ui.painter().rect_filled(rect, 10.0, theme::glass(0.05 + 0.06 * h));
        let art = Rect::from_min_size(rect.left_top() + vec2(8.0, 8.0), Vec2::splat(48.0));
        let radius = if r.round { 24.0 } else { 6.0 };
        match r.thumb.as_deref().filter(|u| !u.is_empty()) {
            Some(url) => {
                let thumbs = [crate::model::Thumb { url: url.to_owned(), width: 300, height: 300 }];
                paint_cover(ui, art, &thumbs, radius, 1.0);
            }
            None => {
                ui.painter().rect_filled(art, radius, theme::glass(0.1));
            }
        }
        let x = art.right() + 14.0;
        widgets::text_at(ui, pos2(x, rect.center().y - 17.0), &r.title, theme::semibold(15.0), theme::text(), rect.right() - x - 12.0);
        widgets::text_at(ui, pos2(x, rect.center().y + 4.0), &r.subtitle, theme::regular(13.0), theme::text_dim(), rect.right() - x - 12.0);
        egui::Popup::context_menu(&resp).show(|ui| {
            ui.set_min_width(200.0);
            if ui.button("Remove from Recently Searched").clicked() {
                remove = Some(i);
                ui.close();
            }
        });
        if resp.clicked() {
            app.actions.push(match &r.target {
                Some(t) => Action::Open(t.clone()),
                None => Action::Go(Page::Search(r.query.clone())),
            });
        }
        ui.add_space(6.0);
    }
    if let Some(i) = remove {
        app.recent_searches.remove(i);
    }
}

fn top_result(ui: &mut Ui, c: &Card, pick: bool, acts: &mut Vec<Action>) {    ui.vertical(|ui| {
        ui.label(RichText::new("Top result").font(theme::bold(24.0)));
        ui.add_space(8.0);
        let (rect, resp) = ui.allocate_exact_size(vec2(420.0, 236.0), Sense::click());
        let resp = named(resp, &c.title).on_hover_cursor(egui::CursorIcon::PointingHand);
        let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.contains_pointer(), 0.2);
        ui.painter().rect_filled(rect, 14.0, theme::glass(0.07 + 0.05 * h));
        let img = Rect::from_min_size(rect.min + vec2(20.0, 20.0), Vec2::splat(110.0));
        paint_cover(ui, img, &c.thumbs, if c.round { 55.0 } else { theme::card_radius() }, 1.0);
        let x = rect.left() + 20.0;
        widgets::text_at(ui, pos2(x, img.bottom() + 16.0), &c.title, theme::bold(26.0), theme::text(), rect.width() - 40.0);
        widgets::linked_text(ui, pos2(x, img.bottom() + 52.0), &c.subtitle, &c.links, theme::regular(14.0), theme::text_dim(), rect.width() - 40.0, acts);
        let pc = rect.right_bottom() + vec2(-44.0, -44.0 + 6.0 * (1.0 - h));
        let pr = Rect::from_center_size(pc, Vec2::splat(52.0));
        let active = widgets::active_state(ui, &c.target);
        let playing = active == Some(true);
        let label = format!("{} {}", if playing { "Pause" } else { "Play" }, c.title);
        let p = named(ui.interact(pr, resp.id.with("play"), Sense::click()), &label).on_hover_cursor(egui::CursorIcon::PointingHand);
        ui.painter().circle_filled(pc, 26.0, theme::with_alpha(Color32::WHITE, 0.4 + 0.6 * h));
        let nudge = if playing { 0.0 } else { 1.5 };
        widgets::play_pause_glyph(ui, pc + vec2(nudge, 0.0), 10.0, Color32::from_gray(15), playing);
        if p.clicked() {
            acts.push(match &c.target {
                _ if active.is_some() => Action::TogglePlay,
                Target::Song(t) => Action::Radio(t.clone()),
                t => Action::PlayCollection(t.clone(), false),
            });
        } else if resp.clicked() {
            acts.push(match &c.target {
                Target::Song(t) if pick => Action::TransferPick(t.clone()),
                t => Action::Open(t.clone()),
            });
        }
    });
}
