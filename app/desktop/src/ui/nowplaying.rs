use egui::{Color32, Rect, Sense, Ui, Vec2, pos2, vec2};

use super::widgets::{self, icon_button, named, text_at};
mod lyrics;
mod audio_info;
mod menus;
use menus::lyric_options;
pub(super) use audio_info::{audio_badge, audio_badge_icon, audio_badge_icon_width, audio_badge_width};
use lyrics::{plain_lyrics, synced, sync_color, sync_label};
use super::{Action, App, NpTab, Repeat, karaoke};
use crate::lyrics::{Lyrics, Sync, SyncKind};
use crate::shader::Params;
use crate::theme::{self, icon};

pub fn overlay(app: &mut App, ctx: &egui::Context, now: f64) {
    use crate::preset::BgMode;
    let t = theme::anim_bool(ctx, "np-open", app.np_open && app.current().is_some(), 0.42);
    if t <= 0.001 {
        return;
    }
    let screen = ctx.content_rect();
    let full = screen;
    let rect = full.translate(vec2(0.0, full.height() * (1.0 - t)));
    let look = app.look();
    let b = &look.background;
    egui::Area::new(egui::Id::unique("now-playing"))
        .order(egui::Order::Middle)
        .fixed_pos(rect.min)
        .show(ctx, |ui| {
            ui.allocate_rect(rect, Sense::click_and_drag());
            if !app.fullscreen {
                super::titlebar::drag_area(ui, Rect::from_min_size(rect.min, vec2(rect.width(), super::titlebar::HEIGHT)), "now-playing");
            }
            let painter = ui.painter_at(rect);
            let art = matches!(b.mode, BgMode::Kawarp | BgMode::CoverStatic);
            if b.mode == BgMode::CoverFullbleed {
                let solid = look.solid();
                painter.rect_filled(rect, 0.0, solid);
                if let Some(track) = app.shown().cloned().map(|t| app.with_cover(&t)) {
                    let side_by_side = look.cover.layout == crate::preset::Layout::LyricsOnly && rect.width() >= 900.0;
                    let area = if side_by_side { Rect::from_min_size(rect.min, Vec2::splat(rect.height())) } else { rect };
                    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(area));
                    child.set_clip_rect(area);
                    widgets::paint_cover_fill(&child, area, &track.thumbs, Color32::from_gray((b.np_dim.clamp(0.0, 1.0) * 255.0) as u8));
                    if side_by_side {
                        let fade = Rect::from_min_max(pos2(area.center().x - area.width() * 0.1, area.top()), area.max);
                        super::shell::hgradient(&painter, fade, Color32::TRANSPARENT, solid);
                    }
                }
            } else if let Some(bg) = app.bg.as_ref().filter(|_| art) {
                let blend = theme::ease_in_out(((now - app.art_changed) / 1.4) as f32);
                let level = if app.reactive_bg && app.playing() { app.audio.status.level() * b.reactive } else { 0.0 };
                bg.paint(
                    &painter,
                    rect,
                    Params {
                        time: app.bg_time as f32,
                        blend,
                        intensity: if b.mode == BgMode::CoverStatic { 0.0 } else { b.intensity },
                        saturation: b.saturation,
                        dim: b.np_dim,
                        scale: b.scale + level.min(0.5) * 0.12,
                    },
                );
            } else if b.mode == BgMode::Solid {
                painter.rect_filled(rect, 0.0, look.solid());
            } else {
                painter.rect_filled(rect, 0.0, theme::lerp_color(theme::bg(), app.accent_color(), 0.25));
            }
            let k = b.scrim / 0.3;
            super::shell::vgradient(ui.painter(), rect, theme::shade((0.18 * k).min(0.95)), theme::shade((0.42 * k).min(0.95)));
            let mut child = ui.new_child(egui::UiBuilder::new().max_rect(rect.shrink2(vec2(32.0, 20.0))));
            body(app, &mut child, now);
        });
}

fn body(app: &mut App, ui: &mut Ui, now: f64) {
    use crate::preset::{Layout, Side};
    let hold = app.mix_hold().map(|h| (h.total, h.mix, app.mix_hold_pos(h)));
    let Some(track) = app.current().cloned() else { return };
    let track = match &hold {
        Some(_) => app.shown().cloned().unwrap_or(track),
        None => track,
    };
    let heard = if hold.is_some() { track.clone() } else { app.playing_track().cloned().unwrap_or_else(|| track.clone()) };
    let look = app.look();
    let cs = &look.cover;
    let area = ui.max_rect();
    let no_lyrics = app.no_lyrics();
    let narrow = area.width() - 64.0 < 860.0;
    let lift = 20.0 - f32::from(super::titlebar::top_margin(20, 44.0));
    let header = Rect::from_min_size(area.min - vec2(0.0, if app.fullscreen { 0.0 } else { lift }), vec2(area.width(), 44.0));
    let mut hui = ui.new_child(egui::UiBuilder::new().max_rect(header).layout(egui::Layout::left_to_right(egui::Align::Center)));
    if icon_button(&mut hui, icon::CHEVRON_DOWN, 22.0, "Close now playing", false).clicked() {
        app.np_open = false;
        if app.fullscreen {
            app.set_fullscreen(ui.ctx(), false);
        }
    }
    let cover_hidden = (narrow || cs.layout == Layout::LyricsOnly) && app.np_tab != NpTab::Art && !no_lyrics;
    hui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
        ui.add_space(super::titlebar::right_gap(ui.ctx(), ui.max_rect(), 0.0));
        if !cover_hidden {
            return;
        }
        let on = app.np_tab == NpTab::Lyrics;
        let (r, resp) = ui.allocate_exact_size(vec2(34.0, 34.0), Sense::click());
        let resp = widgets::named(resp, if on { "Hide lyrics" } else { "Show lyrics" }).on_hover_cursor(egui::CursorIcon::PointingHand);
        let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
        if h > 0.0 {
            ui.painter().circle_filled(r.center(), 17.0, theme::glass(0.10 * h));
        }
        let tint = if on { app.accent_color() } else { theme::with_alpha(Color32::WHITE, 0.75 + 0.25 * h) };
        super::glyphs::draw(ui, r.center(), 18.0, if on { super::glyphs::LYRICS } else { super::glyphs::LYRICS_OFF }, tint);
        if resp.clicked() {
            app.np_tab = if on { NpTab::Art } else { NpTab::Lyrics };
        }
        let (r, _) = ui.allocate_exact_size(vec2(34.0, 34.0), Sense::hover());
        options_menu(app, ui, r, &heard, app.np_tab == NpTab::Lyrics && !no_lyrics, true);
    });

    let content = Rect::from_min_max(pos2(area.left(), header.bottom() + 12.0), area.max);
    let last_id = egui::Id::unique("np-last-panel");
    let panel_tab = if app.np_tab == NpTab::Art {
        ui.ctx().data(|d| d.get_temp(last_id)).unwrap_or(NpTab::Lyrics)
    } else {
        ui.ctx().data_mut(|d| d.insert_temp(last_id, app.np_tab));
        app.np_tab
    };
    let shown = ui.ctx().animate_bool_with_time(egui::Id::unique("np-panel"), app.np_tab != NpTab::Art, 0.6 * theme::speed());
    if shown > 0.0 && shown < 1.0 {
        ui.ctx().request_repaint();
    }
    let ppp = ui.ctx().pixels_per_point();
    let p = if panel_tab == NpTab::Lyrics && no_lyrics { 0.0 } else { shown };
    let (panel_vis, art_vis) = if app.np_tab != NpTab::Art {
        (theme::ease_out((p - 0.3) / 0.7), 1.0 - theme::ease_out(p / 0.45))
    } else {
        let q = 1.0 - p;
        (1.0 - theme::ease_out(q / 0.45), theme::ease_out((q - 0.3) / 0.7))
    };
    let glide = p * p * (3.0 - 2.0 * p);
    let lyrics_only = narrow || cs.layout == Layout::LyricsOnly;
    let beside_cover = !narrow && cs.layout == Layout::LyricsOnly && look.background.mode == crate::preset::BgMode::CoverFullbleed;
    let centered = lyrics_only && !beside_cover;
    let col_art = Rect::from_center_size(content.center(), vec2(content.width().min(content.height() * 0.9), content.height()));
    let slide = 72.0;
    let right = if lyrics_only {
        let px = (art_side(cs, col_art) * ppp) as u32;
        if art_vis > 0.001 {
            let mut cui = ui.new_child(egui::UiBuilder::new().max_rect(content));
            cui.multiply_opacity(art_vis);
            cover_column(app, &mut cui, col_art.translate(vec2(-slide * (1.0 - art_vis), 0.0)), &track, no_lyrics, now, px);
        } else {
            widgets::preload_cover(ui.ctx(), &track.thumbs, px);
        }
        if beside_cover { Rect::from_min_max(pos2(content.left() + content.width() * 0.46, content.top()), content.max) } else { content }
    } else {
        let frac = if cs.layout == Layout::CoverHero { 0.5 } else { 0.42 };
        let col_w = content.width() * frac;
        let col_panel = if cs.side == Side::Right {
            Rect::from_min_size(pos2(content.right() - col_w, content.top()), vec2(col_w, content.height()))
        } else {
            Rect::from_min_size(content.min, vec2(col_w, content.height()))
        };
        let col = Rect::from_min_max(col_art.min.lerp(col_panel.min, glide), col_art.max.lerp(col_panel.max, glide));
        let px = (art_side(cs, col_art).max(art_side(cs, col_panel)) * ppp) as u32;
        cover_column(app, ui, col, &track, no_lyrics, now, px);
        if cs.side == Side::Right {
            Rect::from_min_max(content.min, pos2(col_panel.left() - 28.0, content.bottom()))
        } else {
            Rect::from_min_max(pos2(col_panel.right() + 28.0, content.top()), content.max)
        }
    };
    if panel_vis > 0.001 {
        let toward = if !lyrics_only && cs.side == Side::Right { -slide } else { slide };
        let rect = right.translate(vec2(toward * (1.0 - panel_vis), 0.0));
        for (tab, key) in [(NpTab::Lyrics, "np-tab-lyrics"), (NpTab::UpNext, "np-tab-upnext")] {
            let w = ui.ctx().animate_bool_with_time(egui::Id::unique(key), panel_tab == tab, 0.45 * theme::speed());
            if w > 0.0 && w < 1.0 {
                ui.ctx().request_repaint();
            }
            let vis = if panel_tab == tab {
                theme::ease_out((w - 0.35) / 0.65)
            } else {
                let x = ((w - 0.45) / 0.55).clamp(0.0, 1.0);
                x * x * (3.0 - 2.0 * x)
            };
            if vis <= 0.001 || (tab == NpTab::Lyrics && no_lyrics) {
                continue;
            }
            let rise = if panel_tab == tab { 18.0 * (1.0 - vis) } else { 0.0 };
            let r = rect.translate(vec2(0.0, rise));
            let mut rui = ui.new_child(egui::UiBuilder::new().max_rect(r));
            rui.set_clip_rect(content.intersect(ui.clip_rect()));
            rui.multiply_opacity(panel_vis * vis);
            match tab {
                NpTab::Lyrics => lyrics_panel(app, &mut rui, r, now, centered),
                _ => super::shell::queue_list(app, &mut rui, "np"),
            }
        }
    }
}

fn art_side(cs: &crate::preset::CoverStyle, col: Rect) -> f32 {
    (col.width() * 0.82 * cs.size.clamp(0.5, 1.35)).min(col.height() - 190.0).max(160.0)
}

#[allow(clippy::too_many_arguments)]
fn cover_column(app: &mut App, ui: &mut Ui, col: Rect, track: &crate::model::Track, no_lyrics: bool, now: f64, px: u32) {
    let track = &app.with_cover(track);
    use crate::preset::Shape;
    let hold = app.mix_hold().map(|h| (h.total, h.mix, app.mix_hold_pos(h)));
    let heard = if hold.is_some() { track.clone() } else { app.playing_track().cloned().unwrap_or_else(|| track.clone()) };
    let look = app.look();
    let cs = &look.cover;
    let side = art_side(cs, col);
    let art = Rect::from_center_size(pos2(col.center().x, col.top() + side / 2.0 + (col.height() - side - 190.0).max(0.0) * 0.42), Vec2::splat(side));
    let round = cs.shape != Shape::Rounded;
    let video = if round { None } else { app.video_sprite(ui.ctx()) };
    let art = match &video {
        Some(_) => {
            let w = col.width().min(side * 16.0 / 9.0);
            Rect::from_center_size(art.center(), vec2(w, w * 9.0 / 16.0))
        }
        None => art,
    };
    let radius = if round { side / 2.0 } else { cs.radius.clamp(0.0, side / 2.0) };
    let cr = egui::CornerRadius::same(radius.min(255.0) as u8);
    if cs.glow > 0.0 {
        let s = egui::epaint::Shadow { offset: [0, 16], blur: 90, spread: 6, color: theme::with_alpha(app.accent_color(), cs.glow.min(1.0) * 0.7) };
        ui.painter().add(s.as_shape(art, cr));
    }
    if cs.shadow > 0.0 {
        widgets::soft_shadow(ui, art, radius.min(255.0), 60.0, cs.shadow.min(1.0));
    }
    let tint = Color32::from_gray((cs.brightness.clamp(0.1, 1.0) * 255.0) as u8);
    if round {
        if cs.shape == Shape::Record && app.playing() {
            let dt = ui.input(|i| i.stable_dt).min(0.1) as f64;
            app.record_angle = (app.record_angle + dt * std::f64::consts::TAU / cs.spin_secs.max(4.0) as f64) % std::f64::consts::TAU;
        }
        let angle = if cs.shape == Shape::Record { app.record_angle as f32 } else { 0.0 };
        widgets::paint_disc(ui, egui::Id::unique("np-art"), art.center(), side / 2.0, &track.thumbs, angle, tint, px);
        if cs.shape == Shape::Record {
            let p = ui.painter();
            for i in 1..6 {
                p.circle_stroke(art.center(), side / 2.0 * (0.42 + i as f32 * 0.1), egui::Stroke::new(1.0, theme::shade(0.18)));
            }
            p.circle_filled(art.center(), side * 0.09, theme::shade(0.35));
            p.circle_filled(art.center(), side * 0.022, Color32::from_rgb(10, 10, 12));
        }
    } else {
        widgets::paint_cover_xfade_px(ui, egui::Id::unique("np-art"), art, &track.thumbs, radius, 1.0, px);
        if let Some(s) = &video {
            widgets::paint_motion(ui, art, radius, s);
        } else if let Some(s) = app.song_canvas(ui.ctx(), &track, px) {
            widgets::paint_motion(ui, art, radius, &s);
        }
        if cs.brightness < 0.99 {
            ui.painter().rect_filled(art, cr, theme::shade(1.0 - cs.brightness));
        }
    }
    let w = side;
    let x0 = art.left();
    let info_y = art.bottom() + 22.0;
    let text_w = w - 76.0;
    text_at(ui, pos2(x0, info_y), &track.title, theme::semibold(17.0), Color32::WHITE, text_w);
    let mut line = track.artist_line();
    if let Some(album) = &track.album
        && !album.name.is_empty()
    {
        line = format!("{line} — {}", album.name);
    }
    let line_w = text_w;
    let line_rect = widgets::linked_text(ui, pos2(x0, info_y + 21.0), &line, &track.artists, theme::regular(15.0), theme::text_dim(), line_w, &mut app.actions);
    if let Some(album) = track.album.as_ref().filter(|a| !a.name.is_empty())
        && let Some(id) = album.id.clone()
    {
        let g = widgets::galley(ui, &line, theme::regular(15.0), theme::text_dim(), line_w);
        if let Some(at) = g.job.text.rfind(album.name.as_str()) {
            let c0 = g.job.text[..at].chars().count();
            let c1 = c0 + album.name.chars().count();
            let a0 = g.pos_from_cursor(egui::text::CCursor::new(c0)).min.x;
            let a1 = g.pos_from_cursor(egui::text::CCursor::new(c1)).min.x;
            if a1 - a0 >= 2.0 {
                let r = Rect::from_min_max(pos2(line_rect.left() + a0, line_rect.top()), pos2(line_rect.left() + a1, line_rect.bottom()));
                let resp = ui.interact(r, ui.scope_id().with(("album-link", &album.name)), Sense::click());
                let resp = named(resp, &album.name).on_hover_cursor(egui::CursorIcon::PointingHand);
                if resp.hovered() {
                    ui.painter().hline(r.x_range(), r.bottom() - 1.0, egui::Stroke::new(1.0, theme::text_dim()));
                }
                if resp.clicked() {
                    app.actions.push(Action::Open(super::Target::Album(id)));
                }
            }
        }
    }
    let liked = app.liked.contains(&heard.id);
    let star = Rect::from_center_size(pos2(art.right() - 55.0, info_y + 14.0), Vec2::splat(30.0));
    let sr = ui.interact(star, ui.scope_id().with(("np-like", &heard.id)), Sense::click());
    let sr = named(sr, if liked { "Remove from favourites" } else { "Add to favourites" }).on_hover_cursor(egui::CursorIcon::PointingHand);
    let sh = theme::anim_bool(ui.ctx(), sr.id.with("h"), sr.hovered(), 0.1);
    let pop = theme::anim_bool(ui.ctx(), ("liked", &heard.id), liked, 0.3);
    let disc = (0.09 + 0.07 * sh + 0.05 * pop).min(0.30);
    if disc > 0.0 {
        ui.painter().circle_filled(star.center(), 15.0, theme::glass(disc));
    }
    let dim = 0.85 + 0.15 * sh;
    let tint = theme::lerp_color(theme::with_alpha(Color32::WHITE, dim), app.accent_color(), pop);
    super::glyphs::draw(ui, star.center(), 19.0, super::glyphs::STAR, tint);
    if pop > 0.0 {
        super::glyphs::draw(ui, star.center(), 19.0, super::glyphs::STAR_FILLED, theme::with_alpha(app.accent_color(), pop));
    }
    if sr.clicked() {
        app.actions.push(Action::Like(heard.clone(), !liked));
    }
    let options = Rect::from_center_size(pos2(art.right() - 15.0, info_y + 14.0), Vec2::splat(34.0));
    options_menu(app, ui, options, &heard, app.np_tab == NpTab::Lyrics && !no_lyrics, false);

    let status = app.audio.status.clone();
    let held = hold.is_some();
    let (total, mix) = match &hold {
        Some((total, mix, _)) => (*total, *mix),
        None => {
            let total = status
                .duration()
                .map(|d| d.as_secs_f32())
                .or(track.duration.map(|d| d as f32))
                .unwrap_or(0.0);
            let next = app.queue.upcoming().is_some() || app.autoplay;
            let mix = match (total > 0.0, next, app.queue.repeat) {
                (true, true, repeat) if repeat != Repeat::One => {
                    status.mix_secs(status.duration().or_else(|| track.duration.map(|s| std::time::Duration::from_secs(s as u64)))).min(total * 0.5)
                }
                _ => 0.0,
            };
            (total, mix)
        }
    };
    let smooth = match &hold {
        Some((_, _, pos)) => *pos,
        None => status.position_smooth().as_secs_f32(),
    };
    let pos = app.seek_drag.unwrap_or(smooth).min(total.max(0.0));
    let hit = Rect::from_min_size(pos2(x0, info_y + 55.0), vec2(w, 16.0));
    let sresp = ui.interact(hit, ui.scope_id().with("np-seek"), if held { Sense::hover() } else { Sense::click_and_drag() });
    let sresp = if total > 0.0 && !held { sresp.on_hover_cursor(egui::CursorIcon::PointingHand) } else { sresp };
    sresp.widget_info(|| egui::WidgetInfo::slider(total > 0.0 && !held, pos as f64, "Seek"));
    let focus = theme::anim_bool(ui.ctx(), "np-seek-h", total > 0.0 && !held && (sresp.hovered() || sresp.dragged()), 0.18);
    let thick = 4.0 + 4.0 * focus;
    let bar = Rect::from_min_max(pos2(x0, hit.center().y - thick / 2.0), pos2(x0 + w, hit.center().y + thick / 2.0));
    ui.painter().rect_filled(bar, thick / 2.0, Color32::from_white_alpha(38));
    if mix > 0.5 {
        let edge = x0 + w * ((total - mix) / total);
        let band = Rect::from_min_max(pos2(edge, bar.top()), bar.max);
        ui.painter().rect_filled(band, thick / 2.0, theme::with_alpha(app.accent_color(), 0.32));
        let tick = Rect::from_min_size(pos2(edge - 1.0, bar.top()), vec2(2.0, thick));
        ui.painter().rect_filled(tick, 1.0, theme::with_alpha(app.accent_color(), 0.9));
    }
    let frac = if total > 0.0 { (pos / total).clamp(0.0, 1.0) } else { 0.0 };
    if frac > 0.0 {
        let fill = theme::lerp_color(Color32::from_white_alpha(163), Color32::from_white_alpha(240), focus);
        ui.painter().rect_filled(Rect::from_min_size(bar.min, vec2(bar.width() * frac, thick)), thick / 2.0, fill);
    }
    if app.version_loading() {
        widgets::loading_glow(ui, bar, now, Color32::from_white_alpha(200));
    }
    let at = |p: egui::Pos2| (((p.x - x0) / w.max(1.0)).clamp(0.0, 1.0) * total).max(0.0);
    let tc = theme::with_alpha(Color32::WHITE, 0.62);
    let g = widgets::galley(ui, &super::fmt_time(pos as u32), theme::semibold(11.0), tc, 80.0);
    ui.painter().galley(pos2(x0, hit.bottom() + 2.0), g, tc);
    let left = super::fmt_time((total - pos).max(0.0) as u32);
    let g = widgets::galley(ui, &format!("-{left}"), theme::semibold(11.0), tc, 80.0);
    ui.painter().galley(pos2(x0 + w - g.size().x, hit.bottom() + 2.0), g, tc);
    let badge_w = audio_badge_width(ui, app, false);
    if badge_w > 0.0 {
        audio_badge(app, ui, pos2(x0 + (w - badge_w) / 2.0, hit.bottom() + 1.0), false);
    }
    let mixed = theme::anim_bool(ui.ctx(), "np-automix-on", app.mixing(), 0.3);
    if mixed > 0.01 && badge_w == 0.0 {
        let accent = theme::with_alpha(app.accent_color(), mixed);
        let g = widgets::galley(ui, "Mixing", theme::semibold(10.5), accent, 80.0);
        ui.painter().galley(pos2(x0 + (w - g.size().x) / 2.0, hit.bottom() + 3.0), g, accent);
    }
    if total > 0.0
        && let Some(p) = sresp.interact_pointer_pos()
    {
        if sresp.dragged() {
            app.seek_drag = Some(at(p));
        }
        if sresp.drag_stopped() || sresp.clicked() {
            app.seek_drag = None;
            app.actions.push(Action::Seek(at(p)));
        }
    }

    let ty = info_y + 99.0 + 26.0;
    let row_w = w.min(400.0);
    let sizes = [36.0f32, 36.0, 48.0, 56.0, 48.0, 36.0, 36.0];
    let icons = [26.0f32, 30.0, 40.0, 48.0, 40.0, 30.0, 26.0];
    let gap = ((row_w - sizes.iter().sum::<f32>()) / 6.0).max(4.0);
    let mut xs = [0.0f32; 7];
    let mut cx = art.center().x - row_w / 2.0;
    for (i, s) in sizes.iter().enumerate() {
        xs[i] = cx + s / 2.0;
        cx += s + gap;
    }
    let ink = |h: f32, base: f32, on: f32| theme::with_alpha(theme::lerp_color(theme::with_alpha(Color32::WHITE, base), Color32::WHITE, h), on);
    let enabled = app.current().is_some();
    let dim = if enabled { 1.0 } else { 0.4 };
    let btn = |ui: &mut Ui, i: usize, key: &str, name: &str, live: bool| -> (egui::Response, f32, f32) {
        let r = Rect::from_center_size(pos2(xs[i], ty), Vec2::splat(sizes[i]));
        let resp = ui.interact(r, ui.scope_id().with(("np-t", key)), if live { Sense::click() } else { Sense::hover() });
        let resp = named(resp, name);
        let resp = if live { resp.on_hover_cursor(egui::CursorIcon::PointingHand) } else { resp };
        let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), live && resp.hovered(), 0.1);
        let p = theme::anim_bool(ui.ctx(), resp.id.with("p"), live && resp.is_pointer_button_down_on(), 0.1);
        if h > 0.0 {
            ui.painter().circle_filled(pos2(xs[i], ty), sizes[i] / 2.0 * (1.0 - 0.1 * p), Color32::from_white_alpha((0.1 * h * 255.0) as u8));
        }
        (resp, h, p)
    };
    let (r, h, p) = btn(ui, 1, "shuffle", "Shuffle up next", true);
    super::glyphs::draw(ui, r.rect.center(), icons[1] * (1.0 - 0.1 * p), super::glyphs::SHUFFLE, ink(h, 0.55, 1.0));
    if r.clicked() {
        app.shuffle_upcoming();
    }
    let (r, h, p) = btn(ui, 2, "prev", "Previous", enabled);
    super::glyphs::draw(ui, r.rect.center(), icons[2] * (1.0 - 0.1 * p), super::glyphs::PREVIOUS, ink(h, 1.0, dim));
    if r.clicked() && enabled {
        app.prev();
    }
    let (r, h, p) = btn(ui, 3, "play", if app.playing() || app.buffering { "Pause" } else { "Play" }, enabled);
    let c = r.rect.center();
    let size = icons[3] * (1.0 - 0.1 * p);
    let mark = ink(h, 1.0, dim);
    let morph = theme::anim_bool(ui.ctx(), "np-play-morph", app.playing() || app.buffering, 0.18);
    if app.buffering {
        let a = now as f32 * 6.0;
        let pts: Vec<egui::Pos2> = (0..16).map(|i| c + vec2((a + i as f32 * 0.24).cos(), (a + i as f32 * 0.24).sin()) * 11.0).collect();
        ui.painter().add(egui::Shape::Path(egui::epaint::PathShape::line(pts, egui::epaint::PathStroke::new(2.4, mark))));
        ui.ctx().request_repaint_after(std::time::Duration::from_millis(33));
    } else {
        if morph < 1.0 {
            super::glyphs::draw(ui, c, size, super::glyphs::PLAY, theme::with_alpha(mark, 1.0 - morph));
        }
        if morph > 0.0 {
            super::glyphs::draw(ui, c, size, super::glyphs::PAUSE, theme::with_alpha(mark, morph));
        }
    }
    if r.clicked() && enabled {
        app.toggle();
    }
    let (r, h, p) = btn(ui, 4, "next", "Next", enabled);
    super::glyphs::draw(ui, r.rect.center(), icons[4] * (1.0 - 0.1 * p), super::glyphs::NEXT, ink(h, 1.0, dim));
    if r.clicked() && enabled {
        app.next();
    }
    let (glyph, on) = match app.queue.repeat {
        Repeat::Off => (super::glyphs::REPEAT, false),
        Repeat::All => (super::glyphs::REPEAT, true),
        Repeat::One => (super::glyphs::REPEAT_ONE, true),
    };
    let (r, h, p) = btn(ui, 5, "repeat", "Repeat", true);
    super::glyphs::draw(ui, r.rect.center(), icons[5] * (1.0 - 0.1 * p), glyph, if on { app.accent_color() } else { ink(h, 0.55, 1.0) });
    if r.clicked() {
        app.queue.repeat = match app.queue.repeat {
            Repeat::Off => Repeat::All,
            Repeat::All => Repeat::One,
            Repeat::One => Repeat::Off,
        };
    }
    for (i, tab, key, glyph, off_glyph, show, hide) in [
        (6, NpTab::UpNext, "queue", super::glyphs::QUEUE, super::glyphs::QUEUE, "View queue", "Hide queue"),
        (0, NpTab::Lyrics, "lyrics", super::glyphs::LYRICS, super::glyphs::LYRICS_OFF, "Show lyrics", "Hide lyrics"),
    ] {
        let on = app.np_tab == tab;
        let (r, h, p) = btn(ui, i, key, if on { hide } else { show }, true);
        let tint = if on { app.accent_color() } else { ink(h, 0.55, 1.0) };
        super::glyphs::draw(ui, r.rect.center(), icons[i] * (1.0 - 0.1 * p), if on { glyph } else { off_glyph }, tint);
        if r.clicked() {
            app.np_tab = if on { NpTab::Art } else { tab };
        }
    }

    if no_lyrics && app.np_tab == NpTab::Lyrics {
        let since = ui.ctx().memory_mut(|m| *m.data.get_temp_mut_or_insert_with(egui::Id::unique(("no-lyrics", &track.id)), || now));
        let a = (1.0 - ((now - since - 3.0) / 1.0) as f32).clamp(0.0, 1.0);
        if a > 0.0 {
            let c = theme::with_alpha(theme::text_dim(), a * 0.8);
            text_at(ui, pos2(x0, ty + 40.0), "No lyrics for this song", theme::regular(14.0), c, w);
            let wait = (since + 3.0 - now).max(0.0);
            ui.ctx().request_repaint_after(std::time::Duration::from_secs_f64(if wait > 0.0 { wait } else { 0.033 }));
        }
    }
}

pub(super) fn lyrics_panel(app: &mut App, ui: &mut Ui, rect: Rect, now: f64, centered: bool) {
    let Some((id, set)) = app.lyrics.take() else {
        ui.add_space(rect.height() * 0.35);
        ui.vertical_centered(|ui| {
            ui.spinner();
            ui.label(egui::RichText::new("Loading lyrics…").color(theme::text_dim()));
        });
        return;
    };
    if let Some(lyrics) = app.lyric_prefs.choose(&id, &set).and_then(|p| set.get(p)) {
        let panel = Rect::from_min_max(rect.min, pos2(rect.right(), rect.bottom() - 4.0));
        if lyrics.sync == Sync::None {
            plain_lyrics(app, ui, panel, lyrics);
        } else {
            synced(app, ui, panel, &id, lyrics, now, centered);
        }
    } else {
        ui.add_space(rect.height() * 0.4);
        ui.vertical_centered(|ui| {
            ui.label(egui::RichText::new("No lyrics found for this song").font(theme::semibold(20.0)).color(theme::text_dim()));
        });
    }
    app.lyrics = Some((id, set));
}

fn options_menu(app: &mut App, ui: &mut Ui, rect: Rect, track: &crate::model::Track, lyrics: bool, header: bool) {
    let resp = ui.interact(rect, ui.scope_id().with("np-options"), Sense::click());
    let resp = named(resp, "Options").on_hover_cursor(egui::CursorIcon::PointingHand);
    let open = egui::Popup::is_id_open(ui.ctx(), egui::Popup::default_response_id(&resp));
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered() || open, 0.12);
    ui.painter().circle_filled(rect.center(), 17.0, theme::glass(0.09 + 0.07 * h));
    for dx in [-6.0, 0.0, 6.0] {
        ui.painter().circle_filled(rect.center() + vec2(dx, 0.0), 1.9, theme::with_alpha(Color32::WHITE, 0.75 + 0.25 * h));
    }
    egui::Popup::menu(&resp).close_behavior(egui::PopupCloseBehavior::CloseOnClickOutside).show(|ui| {
        ui.set_width(270.0);
        ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
        ui.add_space(4.0);
        let liked = app.liked.contains(&track.id);
        if lyrics {
            lyric_options(app, ui);
            menu_divider(ui);
        }
        widgets::song_actions(ui, track, liked, None, &mut app.actions);
        menu_divider(ui);
        if widgets::menu_action(ui, Some(icon::LIST_PLUS), "Play next") {
            app.actions.push(Action::PlayNext(track.clone()));
        }
        if widgets::menu_action(ui, Some(icon::LIST_MUSIC), "Add to queue") {
            app.actions.push(Action::Enqueue(track.clone()));
        }
        let queue = app.np_tab == NpTab::UpNext;
        if header && widgets::menu_glyph_action(ui, super::glyphs::QUEUE, if queue { "View lyrics" } else { "View queue" }) {
            app.np_tab = if queue { NpTab::Lyrics } else { NpTab::UpNext };
        }
        if app.queue.upcoming().is_some() && widgets::menu_glyph_action(ui, super::glyphs::SHUFFLE, "Shuffle") {
            app.shuffle_upcoming();
        }
        if let Some(listed) = app.current().map(|t| t.music_video)
            && !app.jam.active()
        {
            let swapped = app.playing_as().is_some();
            if listed != swapped {
                if widgets::menu_glyph_action(ui, super::glyphs::MUSIC_NOTE, "Switch to song") {
                    if swapped { app.play_listed_upload() } else { app.play_song_version() }
                }
            } else if widgets::menu_glyph_action(ui, super::glyphs::VIDEO, "Switch to video") {
                if swapped { app.play_listed_upload() } else { app.play_video_version() }
            }
        }
        let hires_playing = app.audio_info.as_ref().is_some_and(|(_, i)| i.lossless() || i.atmos);
        if hires_playing || !app.hires_sources.is_empty() {
            menu_divider(ui);
            if hires_playing {
                if widgets::menu_action(ui, Some(icon::MUSIC), "Revert audio quality") {
                    app.actions.push(Action::RevertQuality);
                }
            } else if widgets::menu_action(ui, Some(icon::MUSIC), "Upgrade audio quality") {
                app.actions.push(Action::UpgradeQuality);
            }
        }
        if crate::motion::SUPPORTED && app.playing_track().is_some_and(|t| t.music_video) {
            menu_divider(ui);
            for h in [1080, 720, 480, 360] {
                let picked = app.video_height == h;
                if widgets::menu_choice(ui, picked, &format!("{h}p video")) && !picked {
                    app.set_video_height(h);
                }
            }
        }
        ui.add_space(4.0);
    });
}

fn menu_caption(ui: &mut Ui, text: &str) {
    let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), 26.0), Sense::hover());
    ui.painter().text(pos2(r.left() + 12.0, r.center().y + 2.0), egui::Align2::LEFT_CENTER, text, theme::semibold(11.0), theme::text_faint());
}

fn menu_divider(ui: &mut Ui) {
    ui.add_space(5.0);
    let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), 1.0), Sense::hover());
    ui.painter().rect_filled(r.shrink2(vec2(8.0, 0.0)), 0.0, theme::glass(0.08));
    ui.add_space(5.0);
}
