use std::time::Duration;

use egui::{Color32, Pos2, Rect, Sense, Ui, Vec2, ViewportCommand, pos2, vec2};

use super::super::widgets::{self, icon_at, named, paint_cover, text_at};
use super::super::{Action, App, fmt_time, glyphs, titlebar};
use super::{CLOSE, CloseButton};
use crate::theme::{self, icon};

pub fn strip(app: &mut App, ui: &mut Ui, now: f64) {
    let ctx = ui.ctx().clone();
    let r = ctx.content_rect();
    if titlebar::drag_strip(ui, r, "mini").double_clicked() {
        app.actions.push(Action::Mini(false));
    }
    ui.painter().rect_filled(r, theme::thumb_radius() + 1.0, theme::panel(0.62));

    let hold = app.mix_hold().is_some();
    let track = if hold { app.shown().cloned() } else { app.current().cloned() };
    let track = track.map(|t| app.with_cover(&t));
    let pad = 12.0;
    let art = Rect::from_min_size(r.min + Vec2::splat(pad), Vec2::splat(r.height() - 2.0 * pad - 4.0));
    match &track {
        Some(t) => paint_cover(ui, art, &t.thumbs, theme::thumb_radius() + 1.0, 1.0),
        None => {
            ui.painter().rect_filled(art, theme::thumb_radius() + 1.0, theme::glass(0.08));
            icon_at(ui, art.center(), icon::MUSIC, 28.0, theme::text_faint());
        }
    }
    if let Some(t) = &track {
        let px = (art.width() * ctx.pixels_per_point()).round().max(256.0) as u32;
        if let Some(s) = app.song_canvas(&ctx, t, px) {
            widgets::paint_motion(ui, art, theme::thumb_radius() + 1.0, &s);
        }
    }

    let x0 = art.right() + 12.0;
    let x1 = r.right() - 10.0;
    let text_w = (x1 - x0).max(20.0);
    let (title, artists) = match &track {
        Some(t) => (t.title.clone(), t.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", ")),
        None => ("Nothing playing".to_owned(), String::new()),
    };
    let tr = text_at(ui, pos2(x0, art.top() + 1.0), &title, theme::semibold(14.5), theme::text(), text_w);
    if let Some(t) = &track {
        named(ui.interact(tr, ui.scope_id().with("mini-title"), Sense::hover()), &t.title).on_hover_text(&t.title);
    }
    let ar = text_at(ui, pos2(x0, art.top() + 22.0), &artists, theme::regular(12.5), theme::text_dim(), text_w);
    let bw = super::super::nowplaying::audio_badge_icon_width(app, true);
    if bw > 0.0 && ar.right() + 8.0 + bw <= x1 {
        super::super::nowplaying::audio_badge_icon(app, ui, pos2(ar.right() + 8.0 + bw / 2.0, ar.center().y), true);
    }

    let scope = ui.scope_id();
    let btn_id = move |key: &str| scope.with(("mini-btn", key));
    let focused = ctx.memory(|m| m.focused()).is_some_and(|f| ["close", "full", "pin"].iter().any(|k| btn_id(k) == f));
    let over = ctx.input(|i| i.pointer.hover_pos()).is_some_and(|p| r.contains(p));
    let reveal = theme::anim_bool(&ctx, "mini-btns", over || focused, 0.15);
    let count = if titlebar::custom() { 3.0 } else { 2.0 };
    let backdrop = Rect::from_min_max(pos2(r.right() - 6.0 - 30.0 * count - 2.0, r.top() + 2.0), pos2(r.right() - 2.0, r.top() + 38.0));
    let pill = theme::lerp_color(super::super::shell::base_color(app), if theme::light() { Color32::WHITE } else { Color32::BLACK }, 0.55);
    ui.painter().rect_filled(backdrop, 9.0, pill.gamma_multiply(reveal));
    let mut bx = r.right() - 6.0;
    let mut top_btn = |ui: &mut Ui, glyph: char, key: &str, name: &str, close: bool, on: bool| -> bool {
        let b = Rect::from_min_size(pos2(bx - 28.0, r.top() + 6.0), Vec2::splat(28.0));
        bx -= 30.0;
        let resp = ui.interact(b, btn_id(key), Sense::click());
        let resp = named(resp, name).on_hover_text(name).on_hover_cursor(egui::CursorIcon::PointingHand);
        let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
        let down = resp.is_pointer_button_down_on();
        if h > 0.0 {
            let fill = if close { Color32::from_rgb(0xC4, 0x2B, 0x1C).gamma_multiply(h) } else { theme::glass(if down { 0.16 } else { 0.10 } * h) };
            ui.painter().rect_filled(b, 6.0, fill.gamma_multiply(reveal));
        }
        let idle = if on { theme::text() } else { theme::text_dim() };
        let fg = theme::lerp_color(idle, if close { Color32::WHITE } else { theme::text() }, h);
        icon_at(ui, b.center(), glyph, if down { 13.0 } else { 14.0 }, fg.gamma_multiply(reveal));
        if resp.has_focus() {
            ui.painter().rect_stroke(b.shrink(1.0), 6.0, egui::Stroke::new(1.5, theme::text()), egui::StrokeKind::Inside);
        }
        resp.clicked() && (reveal > 0.5 || resp.has_focus())
    };
    if titlebar::custom() && top_btn(ui, icon::X, "close", "Close", true, false) {
        match CLOSE {
            CloseButton::LikeFullWindow => ctx.send_viewport_cmd(ViewportCommand::Close),
            CloseButton::Expand => app.actions.push(Action::Mini(false)),
        }
    }
    if top_btn(ui, icon::MAXIMIZE_2, "full", "Full window (Ctrl+M)", false, false) {
        app.actions.push(Action::Mini(false));
    }
    let pinned = app.mini.pinned;
    if top_btn(ui, if pinned { icon::PIN } else { icon::PIN_OFF }, "pin", if pinned { "Stop keeping on top" } else { "Keep on top" }, false, pinned) {
        app.mini.set_pinned(&ctx, !pinned);
    }

    let cy = art.bottom() - 17.0;
    let cx = (x0 + x1) / 2.0;
    let enabled = track.is_some();
    let vol = app.audio.volume();
    let vpos = pos2(cx + 88.0, cy);
    let vhit = Rect::from_center_size(vpos, Vec2::splat(32.0));
    let slider = Rect::from_min_max(pos2(cx - 88.0, cy - 10.0), pos2(cx + 58.0, cy + 10.0));
    let open_id = ui.scope_id().with("mini-vol-open");
    let was_open = ui.data(|d| d.get_temp::<bool>(open_id).unwrap_or(false));
    let hover = |r: Rect| ui.input(|i| i.pointer.hover_pos()).is_some_and(|p| r.contains(p));
    let band = Rect::from_min_max(pos2(slider.left() - 4.0, cy - 14.0), pos2(vhit.right(), cy + 14.0));
    let open = hover(vhit) || ui.rect_contains_pointer(vhit) || (was_open && (hover(band) || ui.input(|i| i.pointer.any_down())));
    ui.data_mut(|d| d.insert_temp(open_id, open));
    if open != was_open {
        ctx.request_repaint();
    }
    let reveal = theme::anim_bool(&ctx, "mini-vol-reveal", open, 0.15);
    if reveal < 0.5 {
        let small = |ui: &mut Ui, c: Pos2, key: &str, glyph: glyphs::Glyph, name: &str| -> bool {
            let b = Rect::from_center_size(c, Vec2::splat(34.0));
            let resp = ui.interact(b, ui.scope_id().with(("mini-t", key)), Sense::click());
            let resp = named(resp, name).on_hover_text(name).on_hover_cursor(egui::CursorIcon::PointingHand);
            let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
            glyphs::draw(ui, c, 20.0, glyph, theme::lerp_color(theme::text_dim(), theme::text(), h));
            resp.clicked()
        };
        if small(ui, pos2(cx - 44.0, cy), "prev", glyphs::PREVIOUS, "Previous") && enabled {
            app.prev();
        }
        if small(ui, pos2(cx + 44.0, cy), "next", glyphs::NEXT, "Next") && enabled {
            app.next();
        }
        let playing = app.playing();
        let pr = Rect::from_center_size(pos2(cx, cy), Vec2::splat(40.0));
        let presp = named(ui.interact(pr, ui.scope_id().with("mini-play"), Sense::click()), if playing { "Pause" } else { "Play" }).on_hover_cursor(egui::CursorIcon::PointingHand);
        let ph = theme::anim_bool(&ctx, presp.id.with("h"), presp.hovered(), 0.15);
        let c = pr.center();
        let morph = theme::anim_bool(&ctx, "mini-play-morph", playing || app.buffering, 0.18);
        let fg = theme::lerp_color(theme::text_dim(), theme::text(), 0.55 + 0.45 * ph);
        if app.buffering {
            let a = now as f32 * 6.0;
            let pts = (0..20).map(|i| c + vec2((a + i as f32 * 0.22).cos(), (a + i as f32 * 0.22).sin()) * 7.0).collect();
            ui.painter().add(egui::Shape::Path(egui::epaint::PathShape::line(pts, egui::epaint::PathStroke::new(2.0, fg))));
            ctx.request_repaint_after(Duration::from_millis(33));
        } else {
            if morph < 1.0 {
                glyphs::draw(ui, c, 26.0, glyphs::PLAY, theme::with_alpha(fg, 1.0 - morph));
            }
            if morph > 0.0 {
                glyphs::draw(ui, c, 26.0, glyphs::PAUSE, theme::with_alpha(fg, morph));
            }
        }
        if presp.clicked() && enabled {
            app.toggle();
        }

        if let Some(t) = &track {
            let t = if hold { t.clone() } else { app.playing_track().cloned().unwrap_or_else(|| t.clone()) };
            let liked = app.liked.contains(&t.id);
            let lpos = pos2(cx - 88.0, cy);
            let lresp = ui.interact(Rect::from_center_size(lpos, Vec2::splat(32.0)), ui.scope_id().with("mini-like"), Sense::click());
            let name = if liked { "Remove like" } else { "Like" };
            let lresp = named(lresp, name).on_hover_text(name).on_hover_cursor(egui::CursorIcon::PointingHand);
            let lh = theme::anim_bool(&ctx, lresp.id.with("h"), lresp.hovered(), 0.15);
            let pop = theme::anim_bool(&ctx, ("liked", &t.id), liked, 0.3);
            let disc = (0.17 * pop + 0.10 * lh).min(0.30);
            if disc > 0.0 {
                ui.painter().circle_filled(lpos, 16.0, theme::glass(disc));
            }
            let size = 18.0 * (1.0 + 0.25 * (pop * std::f32::consts::PI).sin());
            let tint = theme::lerp_color(theme::lerp_color(theme::text_dim(), theme::text(), lh), app.accent_color(), pop);
            glyphs::draw(ui, lpos, size, glyphs::STAR, tint);
            if pop > 0.0 {
                glyphs::draw(ui, lpos, size, glyphs::STAR_FILLED, theme::with_alpha(app.accent_color(), pop));
            }
            if lresp.clicked() {
                app.actions.push(Action::Like(t.clone(), !liked));
            }
        }
    } else {
        let track_r = Rect::from_center_size(slider.center(), vec2(slider.width(), 4.0));
        ui.painter().rect_filled(track_r, 2.0, theme::glass(0.16));
        let fill = Rect::from_min_size(track_r.min, vec2(track_r.width() * vol, 4.0));
        ui.painter().rect_filled(fill, 2.0, theme::text());
        ui.painter().circle_filled(pos2(fill.right(), track_r.center().y), 6.0, theme::text());
        let sresp = ui.interact(slider.expand2(vec2(0.0, 8.0)), ui.scope_id().with("mini-vol-slider"), Sense::click_and_drag());
        let sresp = sresp.on_hover_cursor(egui::CursorIcon::PointingHand);
        sresp.widget_info(|| egui::WidgetInfo::slider(true, vol as f64, "Volume"));
        if let Some(p) = sresp.interact_pointer_pos()
            && (sresp.clicked() || sresp.dragged())
        {
            app.audio.set_volume(((p.x - track_r.left()) / track_r.width()).clamp(0.0, 1.0));
        }
    }

    let vol = app.audio.volume();
    let vresp = ui.interact(vhit, ui.scope_id().with("mini-vol"), Sense::click());
    let pct = format!("Volume {}%", (vol * 100.0).round());
    let vresp = named(vresp, "Mute").on_hover_text(pct).on_hover_cursor(egui::CursorIcon::PointingHand);
    let vh = theme::anim_bool(&ctx, vresp.id.with("h"), vresp.hovered(), 0.15);
    ui.painter().circle_filled(vpos, 16.0, theme::glass(0.1 * vh));
    let vglyph = glyphs::VOLUME[glyphs::volume_step(vol)];
    glyphs::draw(ui, vpos, 18.0, vglyph, theme::lerp_color(theme::text_dim(), theme::text(), vh));
    if vresp.hovered() {
        let scroll = ui.input(|i| i.smooth_scroll_delta.y);
        if scroll != 0.0 {
            app.audio.set_volume((vol + scroll / 600.0).clamp(0.0, 1.0));
        }
    }
    if vresp.clicked() {
        let stash = super::super::shell::unmute_id();
        if vol > 0.0 {
            ui.data_mut(|d| d.insert_temp(stash, vol));
            app.audio.set_volume(0.0);
        } else {
            app.audio.set_volume(ui.data(|d| d.get_temp::<f32>(stash)).unwrap_or(0.6));
        }
    }

    seek_line(app, ui, r, track.as_ref());
}

fn seek_line(app: &mut App, ui: &mut Ui, r: Rect, track: Option<&crate::model::Track>) {
    let status = app.audio.status.clone();
    let hold = app.mix_hold().map(|h| (h.total, app.mix_hold_pos(h)));
    let total = match &hold {
        Some((total, _)) => *total,
        None => status.duration().map(|d| d.as_secs_f32()).or(track.and_then(|t| t.duration).map(|d| d as f32)).unwrap_or(0.0),
    };
    let smooth = match &hold {
        Some((_, pos)) => *pos,
        None => status.position_smooth().as_secs_f32(),
    };
    let held = hold.is_some();
    let pos = app.seek_drag.unwrap_or(smooth).min(total.max(0.0));
    let (l, rr) = (r.left(), r.right());
    let hit = Rect::from_min_max(pos2(l, r.bottom() - 10.0), pos2(rr, r.bottom()));
    let resp = ui.interact(hit, ui.scope_id().with("mini-seek"), if held { Sense::hover() } else { Sense::click_and_drag() });
    let resp = if total > 0.0 && !held { resp.on_hover_cursor(egui::CursorIcon::PointingHand) } else { resp };
    resp.widget_info(|| egui::WidgetInfo::slider(total > 0.0 && !held, pos as f64, "Seek"));
    let h = theme::anim_bool(ui.ctx(), "mini-seek-h", total > 0.0 && !held && (resp.hovered() || resp.dragged()), 0.15);
    let thick = 4.0 + 2.0 * h;
    let line = Rect::from_min_max(pos2(l, r.bottom() - thick), pos2(rr, r.bottom()));
    ui.painter().rect_filled(line, 0.0, theme::glass(0.2));
    let frac = if total > 0.0 { pos / total } else { 0.0 };
    ui.painter().rect_filled(Rect::from_min_size(line.min, vec2(line.width() * frac, thick)), 0.0, app.accent_color());
    let at = |p: Pos2| ((p.x - l) / (rr - l).max(1.0)).clamp(0.0, 1.0) * total;
    if h > 0.0
        && let Some(p) = resp.hover_pos().or(resp.interact_pointer_pos())
    {
        let txt = format!("{} / {}", fmt_time(at(p) as u32), fmt_time(total as u32));
        let g = widgets::galley(ui, &txt, theme::semibold(11.5), Color32::WHITE, 120.0);
        let half = g.size().x / 2.0 + 6.0;
        let x = p.x.clamp(r.left() + half + 2.0, r.right() - half - 2.0);
        let bg = Rect::from_center_size(pos2(x, line.top() - 13.0), g.size() + vec2(12.0, 5.0));
        ui.painter().rect_filled(bg, 5.0, theme::shade(0.8).gamma_multiply(h));
        ui.painter().galley(bg.min + vec2(6.0, 2.5), g, Color32::WHITE.gamma_multiply(h));
    }
    if total > 0.0
        && let Some(p) = resp.interact_pointer_pos()
    {
        if resp.dragged() {
            app.seek_drag = Some(at(p));
        }
        if resp.drag_stopped() || resp.clicked() {
            app.seek_drag = None;
            app.actions.push(Action::Seek(at(p)));
        }
    }
}
