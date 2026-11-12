use egui::{Align2, Color32, Pos2, Rect, Sense, Ui, Vec2, pos2, vec2};

use super::widgets::{self, icon_at, icon_button, named, paint_cover, text_at};
use super::{Action, App, LibTab, NpTab, Page, Repeat, fmt_time, glyphs};
use crate::model::Target;
use crate::theme::{self, icon};
mod background;
pub use background::{background, base_color, hgradient, pages_bg_moves, vgradient};
mod volume;
use volume::volume_popover;
mod sidebar;
pub use sidebar::{sidebar, sidebar_collapse, RAIL_W, SIDEBAR_W};

pub fn search_field(app: &mut App, ui: &mut Ui, width: f32) {
    let (rect, field) = ui.allocate_exact_size(vec2(width, 42.0), Sense::click());
    let field = field.on_hover_cursor(egui::CursorIcon::Text);
    let focus = theme::anim_bool(ui.ctx(), "search-focus", app.search_focused, 0.2);
    ui.painter().rect_filled(rect, 21.0, theme::glass(0.09 + 0.05 * focus));
    if focus > 0.0 {
        ui.painter().rect_stroke(rect, 21.0, egui::Stroke::new(1.0, theme::glass(0.25 * focus)), egui::StrokeKind::Inside);
    }
    icon_at(ui, pos2(rect.left() + 22.0, rect.center().y), icon::SEARCH, 17.0, theme::text_dim());
    let clearable = !app.search_text.is_empty();
    let inner = Rect::from_min_max(pos2(rect.left() + 42.0, rect.top() + 4.0), pos2(rect.right() - if clearable { 46.0 } else { 16.0 }, rect.bottom() - 4.0));
    let fresh = app.suggestions.0 == app.search_text.trim();
    let n = if fresh { app.suggestions.1.len() } else { 0 };
    if app.search_focused && n > 0 && ui.input(|i| i.modifiers.is_none()) {
        ui.input_mut(|i| {
            if i.consume_key(egui::Modifiers::NONE, egui::Key::ArrowDown) {
                app.suggest_sel = match app.suggest_sel {
                    None => Some(0),
                    Some(s) if s + 1 < n => Some(s + 1),
                    Some(_) => None,
                };
            }
            if i.consume_key(egui::Modifiers::NONE, egui::Key::ArrowUp) {
                app.suggest_sel = match app.suggest_sel {
                    None => Some(n - 1),
                    Some(0) => None,
                    Some(s) => Some(s - 1),
                };
            }
        });
    }
    let edit = ui.put(
        inner,
        egui::TextEdit::singleline(&mut app.search_text)
            .hint_text(egui::RichText::new("Search songs, albums, artists, podcasts").color(theme::text_faint()))
            .font(theme::regular(15.0))
            .frame(egui::Frame::NONE)
            .align(egui::Align2::LEFT_CENTER)
            .desired_width(inner.width()),
    );
    app.search_rect = Some(rect);
    if std::mem::take(&mut app.search_refocus) {
        edit.request_focus();
    }
    app.search_focused = edit.has_focus();
    let mut cleared = false;
    if clearable {
        let x = Rect::from_center_size(pos2(rect.right() - 25.0, rect.center().y), Vec2::splat(30.0));
        let resp = named(ui.interact(x, ui.scope_id().with("search-clear"), Sense::click()), "Clear search").on_hover_cursor(egui::CursorIcon::PointingHand);
        let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
        if h > 0.0 {
            ui.painter().circle_filled(x.center(), 15.0, theme::glass(0.10 * h));
        }
        icon_at(ui, x.center(), icon::X, 16.0, theme::lerp_color(theme::text_dim(), theme::text(), h));
        if resp.clicked() {
            app.search_text.clear();
            app.search_synced = String::new();
            app.suggestions = Default::default();
            app.suggest_sel = None;
            cleared = true;
            app.actions.push(Action::Go(Page::Search(String::new())));
            edit.request_focus();
        }
    }
    if field.clicked() || ui.data_mut(|d| d.remove_temp::<bool>(egui::Id::unique("focus-search"))).unwrap_or(false) {
        edit.request_focus();
    }
    if edit.changed() || edit.gained_focus() || cleared {
        app.suggest_sel = None;
        let now = ui.input(|i| i.time);
        let first = app.suggestions.1.is_empty();
        let query = app.search_text.trim();
        app.suggestions = app.suggest_cache.iter()
            .find(|(q, _)| q == query)
            .cloned()
            .unwrap_or_default();
        let delay = if first || cleared || edit.gained_focus() { 0.0 } else { 0.06 };
        app.suggest_due = Some(now + delay);
    }
    if let Some(due) = app.suggest_due {
        let now = ui.input(|i| i.time);
        ui.ctx().request_repaint_after(std::time::Duration::from_secs_f64((due - now).max(0.0)));
    }
    if edit.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
        let picked = app.suggest_sel.filter(|_| fresh).and_then(|s| app.suggestions.1.get(s)).map(|s| s.text.clone());
        let q = picked.unwrap_or_else(|| app.search_text.trim().to_owned());
        if !q.is_empty() {
            app.suggestions = Default::default();
            app.actions.push(Action::Go(Page::Search(q)));
        }
    }
}

pub fn suggestions(app: &mut App, ctx: &egui::Context) {
    let Some(rect) = app.search_rect else { return };
    let show = (app.search_focused || app.forget_held) && !app.suggestions.1.is_empty() && !app.np_open;
    let t = theme::anim_bool(ctx, "suggest-open", show, 0.15);
    if t <= 0.0 {
        return;
    }
    egui::Area::new(egui::Id::unique("suggestions"))
        .order(egui::Order::Foreground)
        .fixed_pos(rect.left_bottom() + vec2(0.0, 6.0 - 6.0 * (1.0 - t)))
        .show(ctx, |ui| {
            ui.set_opacity(t);
            egui::Frame::new()
                .fill(theme::menu_fill())
                .corner_radius(14)
                .inner_margin(6)
                .shadow(egui::epaint::Shadow { offset: [0, 10], blur: 30, spread: 0, color: theme::shade(0.5) })
                .show(ui, |ui| {
                    ui.set_width(rect.width() - 12.0);
                    if !ui.input(|i| i.pointer.any_down() || i.pointer.any_released()) {
                        app.forget_held = false;
                    }
                    if app.forget_held {
                        app.search_refocus = true;
                    }
                    let now = ui.input(|i| i.time);
                    for (i, s) in app.suggestions.1.clone().into_iter().enumerate() {
                        let term = s.text;
                        if let Some((_, at)) = app.forgetting.as_ref().filter(|(t, _)| s.forget.as_deref() == Some(t.as_str())) {
                            let p = ((now - at) / 0.18).clamp(0.0, 1.0) as f32;
                            let e = 1.0 - (1.0 - p).powi(3);
                            let gap = ui.spacing().item_spacing.y;
                            ui.spacing_mut().item_spacing.y = 0.0;
                            let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), (40.0 + gap) * (1.0 - e)), Sense::hover());
                            ui.spacing_mut().item_spacing.y = gap;
                            ui.scope(|ui| {
                                ui.set_clip_rect(r.intersect(ui.clip_rect()));
                                ui.set_opacity(1.0 - e);
                                let cy = r.top() + 20.0;
                                icon_at(ui, pos2(r.left() + 20.0, cy), icon::HISTORY, 15.0, theme::text_dim());
                                suggestion_text(ui, pos2(r.left() + 42.0, cy - 9.0), &term, &app.suggestions.0, r.width() - 88.0);
                            });
                            ui.ctx().request_repaint();
                            continue;
                        }
                        let (r, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 40.0), Sense::click());
                        let resp = named(resp, &term).on_hover_cursor(egui::CursorIcon::PointingHand);
                        let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.contains_pointer() || app.suggest_sel == Some(i), 0.1);
                        ui.painter().rect_filled(r, 8.0, theme::glass(0.08 * h));
                        let glyph = if s.forget.is_some() { icon::HISTORY } else { icon::SEARCH };
                        icon_at(ui, pos2(r.left() + 20.0, r.center().y), glyph, 15.0, theme::text_dim());
                        let room = if s.forget.is_some() { 88.0 } else { 52.0 };
                        suggestion_text(ui, pos2(r.left() + 42.0, r.center().y - 9.0), &term, &app.suggestions.0, r.width() - room);
                        if let Some(token) = s.forget {
                            let x = Rect::from_center_size(pos2(r.right() - 22.0, r.center().y), Vec2::splat(30.0));
                            let xr = named(ui.interact(x, resp.id.with("forget"), Sense::click()), "Remove from recent searches")
                                .on_hover_text("Remove")
                                .on_hover_cursor(egui::CursorIcon::PointingHand);
                            let xh = theme::anim_bool(ui.ctx(), xr.id.with("h"), xr.hovered(), 0.1);
                            if xh > 0.0 {
                                ui.painter().circle_filled(x.center(), 15.0, theme::glass(0.12 * xh));
                            }
                            icon_at(ui, x.center(), icon::TRASH_2, 15.0, theme::lerp_color(theme::text_dim(), theme::text(), xh));
                            if (xr.is_pointer_button_down_on() || xr.clicked()) && !app.forget_held {
                                app.forget_held = true;
                                if let Some((t, _)) = app.forgetting.take() {
                                    app.suggestions.1.retain(|s| s.forget.as_deref() != Some(t.as_str()));
                                }
                                app.forgetting = Some((token.clone(), now));
                                app.suggest_sel = None;
                                app.suggest_round += 1;
                                app.suggest_cache.clear();
                                app.forgets_pending += 1;
                                app.search_refocus = true;
                                app.backend.request(crate::backend::Req::ForgetSearch(token));
                            }
                        }
                        if resp.is_pointer_button_down_on() || resp.clicked() {
                            app.suggestions = Default::default();
                            app.search_focused = false;
                            app.actions.push(Action::Go(Page::Search(term)));
                            break;
                        }
                    }
                    if let Some((t, _)) = app.forgetting.take_if(|(_, at)| now - *at >= 0.18) {
                        app.suggestions.1.retain(|s| s.forget.as_deref() != Some(t.as_str()));
                    }
                });
        });
}

fn suggestion_text(ui: &Ui, pos: Pos2, text: &str, typed: &str, width: f32) {
    let n = text.chars().zip(typed.chars()).take_while(|(a, b)| a.to_lowercase().eq(b.to_lowercase())).count();
    let split = if n == typed.chars().count() { text.char_indices().nth(n).map_or(text.len(), |(i, _)| i) } else { 0 };
    let (bold, rest) = text.split_at(split);
    let mut job = egui::text::LayoutJob::default();
    job.append(bold, 0.0, egui::TextFormat { font_id: theme::semibold(15.0), color: theme::text(), ..Default::default() });
    job.append(rest, 0.0, egui::TextFormat { font_id: theme::regular(15.0), color: theme::text_dim(), ..Default::default() });
    job.wrap = egui::text::TextWrapping::truncate_at_width(width.max(10.0));
    let g = ui.fonts_mut(|f| f.layout_job(job));
    ui.painter().galley(pos, g, theme::text());
}

pub fn player_bar(app: &mut App, ui: &mut Ui, now: f64) {
    let full = ui.max_rect();
    let ctx = ui.ctx().clone();
    ui.painter().hline(full.x_range(), full.bottom() - 0.5, egui::Stroke::new(1.0, theme::glass(0.08)));
    let cy = full.center().y;

    let status = app.audio.status.clone();
    let hold = app.mix_hold().map(|h| (h.total, h.mix, app.mix_hold_pos(h)));
    let track = match &hold {
        Some(_) => app.shown().cloned(),
        None => app.current().cloned(),
    };
    let enabled = track.is_some();
    let playing = app.playing();
    let accent = app.accent_color();
    let ink = |h: f32| {
        let c = theme::lerp_color(theme::text_dim(), theme::text(), h);
        if enabled { c } else { c.gamma_multiply(0.45) }
    };
    let btn = |ui: &mut Ui, x: f32, key: &str, name: &str| -> egui::Response {
        let r = Rect::from_center_size(pos2(x, cy), Vec2::splat(30.0));
        let resp = ui.interact(r, ui.scope_id().with(("bar", key)), Sense::click());
        named(resp, name).on_hover_text(name).on_hover_cursor(egui::CursorIcon::PointingHand)
    };

    const STEP: f32 = 31.5;
    const RSTEP: f32 = 35.0;
    let right = full.right() - super::titlebar::right_gap(&ctx, full, 16.0);
    let avatar = Rect::from_center_size(pos2(right - 16.0, cy), Vec2::splat(32.0));
    let lcd_c = full.center().x - full.width() * 0.011;
    let half_room = (lcd_c - (full.left() + 22.0 + 4.0 * STEP + 54.0)).min(avatar.left() - 12.0 - 2.0 * RSTEP - 54.0 - lcd_c);
    let lcd_w = (full.width() * 0.344).min(720.0).min(half_room * 2.0);
    let (lcd_l, lcd_r) = if lcd_w >= 160.0 { (lcd_c - lcd_w / 2.0, lcd_c + lcd_w / 2.0) } else { (avatar.left() - 2.0 * RSTEP - 40.0, avatar.left() - 2.0 * RSTEP - 40.0) };
    let x0 = ((full.left() + lcd_l) / 2.0 - 20.0 - 2.0 * STEP).max(full.left() + 22.0);
    let rc = ((lcd_r + right) / 2.0).min(avatar.left() - 12.0 - RSTEP);
    let r = btn(ui, x0, "shuffle", "Shuffle up next");
    let h = theme::anim_bool(&ctx, r.id.with("h"), r.hovered(), 0.12);
    glyphs::draw(ui, r.rect.center(), 15.0, glyphs::SHUFFLE, ink(h));
    if r.clicked() {
        app.shuffle_upcoming();
    }
    let r = btn(ui, x0 + STEP, "prev", "Previous");
    let h = theme::anim_bool(&ctx, r.id.with("h"), r.hovered(), 0.12);
    glyphs::draw(ui, r.rect.center(), 18.0, glyphs::PREVIOUS, ink(h));
    if r.clicked() && enabled {
        app.prev();
    }
    let r = btn(ui, x0 + 2.0 * STEP, "play", if playing { "Pause" } else { "Play" });
    let h = theme::anim_bool(&ctx, r.id.with("h"), r.hovered(), 0.12);
    let c = r.rect.center();
    let morph = theme::anim_bool(&ctx, "bar-play-morph", playing || app.buffering, 0.18);
    if app.buffering {
        let a = now as f32 * 6.0;
        let pts: Vec<Pos2> = (0..16).map(|i| c + vec2((a + i as f32 * 0.24).cos(), (a + i as f32 * 0.24).sin()) * 7.0).collect();
        ui.painter().add(egui::Shape::Path(egui::epaint::PathShape::line(pts, egui::epaint::PathStroke::new(2.0, ink(0.6)))));
        ctx.request_repaint_after(std::time::Duration::from_millis(33));
    } else {
        let fg = ink(0.55 + 0.45 * h);
        if morph < 1.0 {
            glyphs::draw(ui, c, 17.0, glyphs::PLAY, theme::with_alpha(fg, 1.0 - morph));
        }
        if morph > 0.0 {
            glyphs::draw(ui, c, 17.0, glyphs::PAUSE, theme::with_alpha(fg, morph));
        }
    }
    if r.clicked() && enabled {
        app.toggle();
    }
    let r = btn(ui, x0 + 3.0 * STEP, "next", "Next");
    let h = theme::anim_bool(&ctx, r.id.with("h"), r.hovered(), 0.12);
    glyphs::draw(ui, r.rect.center(), 18.0, glyphs::NEXT, ink(h));
    if r.clicked() && enabled {
        app.next();
    }
    let (glyph, on) = match app.queue.repeat {
        Repeat::Off => (glyphs::REPEAT, false),
        Repeat::All => (glyphs::REPEAT, true),
        Repeat::One => (glyphs::REPEAT_ONE, true),
    };
    let r = btn(ui, x0 + 4.0 * STEP, "repeat", "Repeat");
    let h = theme::anim_bool(&ctx, r.id.with("h"), r.hovered(), 0.12);
    glyphs::draw(ui, r.rect.center(), 15.0, glyph, if on { accent } else { ink(h) });
    if r.clicked() {
        app.queue.repeat = match app.queue.repeat {
            Repeat::Off => Repeat::All,
            Repeat::All => Repeat::One,
            Repeat::One => Repeat::Off,
        };
    }
    if app.loading {
        let spin = Rect::from_center_size(pos2(x0 + 4.0 * STEP + 30.0, cy), Vec2::splat(16.0));
        ui.new_child(egui::UiBuilder::new().max_rect(spin)).spinner();
    }

    account(app, ui, avatar);
    let queue_open = if app.np_open { app.np_tab == NpTab::UpNext } else { app.queue_open };
    let r = btn(ui, rc + RSTEP, "queue", "Up next");
    let h = theme::anim_bool(&ctx, r.id.with("h"), r.hovered(), 0.15);
    glyphs::draw(ui, r.rect.center(), 16.0, glyphs::QUEUE, if queue_open { accent } else { ink(h) });
    if r.clicked() {
        if app.np_open {
            app.np_tab = if queue_open { NpTab::Art } else { NpTab::UpNext };
        } else {
            app.queue_open = !app.queue_open;
            app.lyrics_open &= !app.queue_open;
        }
    }
    let lyrics_on = if app.np_open { app.np_tab == NpTab::Lyrics && !app.no_lyrics() } else { app.lyrics_open };
    let r = btn(ui, rc, "lyrics", "Lyrics");
    let h = theme::anim_bool(&ctx, r.id.with("h"), r.hovered(), 0.15);
    glyphs::draw(ui, r.rect.center(), 16.0, if lyrics_on { glyphs::LYRICS } else { glyphs::LYRICS_OFF }, if lyrics_on { accent } else { ink(h) });
    if r.clicked() {
        if app.np_open {
            app.np_tab = if lyrics_on { NpTab::Art } else { NpTab::Lyrics };
        } else {
            app.lyrics_open = !app.lyrics_open;
            app.queue_open &= !app.lyrics_open;
        }
    }
    let vol = app.audio.volume();
    let r = btn(ui, rc - RSTEP, "vol", "Mute");
    let h = theme::anim_bool(&ctx, r.id.with("h"), r.hovered(), 0.15);
    glyphs::draw(ui, r.rect.center(), 16.0, glyphs::VOLUME[glyphs::volume_step(vol)], theme::lerp_color(theme::text_dim(), theme::text(), h));
    volume_popover(app, ui, r.rect);
    if r.hovered() {
        let scroll = ui.input(|i| i.smooth_scroll_delta.y);
        if scroll != 0.0 {
            app.audio.set_volume((vol + scroll / 600.0).clamp(0.0, 1.0));
        }
    }
    if r.clicked() {
        let stash = unmute_id();
        if vol > 0.0 {
            ui.data_mut(|d| d.insert_temp(stash, vol));
            app.audio.set_volume(0.0);
        } else {
            app.audio.set_volume(ui.data(|d| d.get_temp::<f32>(stash)).unwrap_or(0.6));
        }
    }

    if lcd_w < 160.0 {
        return;
    }
    let lcd_x = lcd_l;
    let ppp = ctx.pixels_per_point();
    let snap = |v: f32| (v * ppp).round() / ppp;
    let lcd = Rect::from_min_max(pos2(snap(lcd_x), snap(cy - 20.0)), pos2(snap(lcd_x + lcd_w), snap(cy + 20.0)));
    const R: f32 = 4.0;
    let base = base_color(app);
    let (fill, edge) = if theme::light() {
        (theme::lerp_color(base, Color32::BLACK, 0.055), Color32::from_black_alpha(26))
    } else {
        (theme::lerp_color(base, Color32::WHITE, 0.08), Color32::from_white_alpha(28))
    };
    ui.painter().rect_filled(lcd, R, fill);
    ui.painter().rect_stroke(lcd, R, egui::Stroke::new(1.0 / ctx.pixels_per_point(), edge), egui::StrokeKind::Inside);
    let cover = Rect::from_min_size(lcd.min, Vec2::splat(lcd.height()));
    let star_x = lcd.right() - 18.0;
    let (text_l, text_r) = (cover.right() + 10.0, star_x - 16.0);
    let text_w = (text_r - text_l).max(20.0);
    let tcx = (text_l + text_r) / 2.0;

    let held = hold.is_some();
    let (total, mix) = match &hold {
        Some((total, mix, _)) => (*total, *mix),
        None => {
            let total = status
                .duration()
                .map(|d| d.as_secs_f32())
                .or(track.as_ref().and_then(|t| t.duration).map(|d| d as f32))
                .unwrap_or(0.0);
            let next = app.queue.upcoming().is_some() || app.autoplay;
            let mix = match (total > 0.0, next, app.queue.repeat) {
                (true, true, repeat) if repeat != Repeat::One => {
                    status.mix_secs(status.duration().or_else(|| track.as_ref().and_then(|t| t.duration).map(|s| std::time::Duration::from_secs(s as u64)))).min(total * 0.5)
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
    let (sx0, sx1) = (cover.right() + 1.0, lcd.right() - R);
    let seek_hit = Rect::from_min_max(pos2(sx0, lcd.bottom() - 9.0), pos2(sx1, lcd.bottom() + 3.0));
    let sresp = ui.interact(seek_hit, ui.scope_id().with("bar-seek"), if held { Sense::hover() } else { Sense::click_and_drag() });
    let sresp = if total > 0.0 && !held { sresp.on_hover_cursor(egui::CursorIcon::PointingHand) } else { sresp };
    sresp.widget_info(|| egui::WidgetInfo::slider(total > 0.0 && !held, pos as f64, "Seek"));
    let focus = theme::anim_bool(&ctx, "bar-seek-h", total > 0.0 && !held && (sresp.hovered() || sresp.dragged()), 0.15);
    let thick = 2.0 + 1.5 * focus;
    let line_r = Rect::from_min_max(pos2(sx0, lcd.bottom() - 1.5 - thick), pos2(sx1, lcd.bottom() - 1.5));
    if enabled {
        ui.painter().rect_filled(line_r, thick / 2.0, theme::glass(0.16));
    }
    if mix > 0.5 {
        let edge = sx0 + (sx1 - sx0) * ((total - mix) / total);
        ui.painter().rect_filled(Rect::from_min_max(pos2(edge, line_r.top()), line_r.max), thick / 2.0, theme::with_alpha(accent, 0.30));
    }
    let frac = if total > 0.0 { (pos / total).clamp(0.0, 1.0) } else { 0.0 };
    if frac > 0.0 {
        ui.painter().rect_filled(Rect::from_min_size(line_r.min, vec2(line_r.width() * frac, thick)), thick / 2.0, theme::text().gamma_multiply(0.8));
    }
    if enabled && app.version_loading() {
        widgets::loading_glow(ui, line_r, now, theme::text());
    }
    let at = |p: Pos2| (((p.x - sx0) / (sx1 - sx0)).clamp(0.0, 1.0) * total).max(0.0);

    let track = track.map(|t| app.with_cover(&t));
    let Some(t) = &track else {
        let slot = if theme::light() { Color32::from_black_alpha(10) } else { Color32::from_black_alpha(40) };
        ui.painter().rect_filled(cover, egui::CornerRadius { nw: R as u8, sw: R as u8, ne: 0, se: 0 }, slot);
        ui.painter().vline(cover.right(), cover.y_range(), egui::Stroke::new(1.0 / ppp, edge));
        glyphs::draw(ui, cover.center(), 18.0, glyphs::MUSIC_NOTE, theme::text_dim().gamma_multiply(0.6));
        mark(ui, Rect::from_center_size(pos2(tcx, lcd.center().y), vec2(40.0, 16.0)), theme::text_dim().gamma_multiply(0.7));
        return;
    };
    widgets::paint_cover_xfade(ui, egui::Id::unique("player-bar-art"), cover, &t.thumbs, R, 1.0);
    let ar = ui.interact(cover, ui.scope_id().with("bar-art"), Sense::click());
    let ar = named(ar, "Open now playing").on_hover_cursor(egui::CursorIcon::PointingHand);
    let ah = theme::anim_bool(&ctx, ar.id.with("h"), ar.hovered(), 0.15);
    if ah > 0.0 {
        ui.painter().rect_filled(cover, R, theme::shade(0.4 * ah));
        glyphs::draw(ui, cover.center(), 13.0, glyphs::MAXIMIZE_2, theme::with_alpha(Color32::WHITE, ah));
    }
    if ar.clicked() {
        app.actions.push(Action::OpenNowPlaying(true));
    }

    let g = widgets::galley(ui, &t.title, theme::semibold(12.5), theme::text(), text_w);
    let tp = pos2(tcx - g.size().x / 2.0, cy - 15.0);
    let tr = Rect::from_min_size(tp, g.size());
    ui.painter().galley(tp, g, theme::with_alpha(theme::text(), 1.0 - 0.5 * focus));
    let tre = ui.interact(tr, ui.scope_id().with("bar-title"), Sense::click());
    if named(tre, &t.title).on_hover_text(&t.title).on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
        app.actions.push(Action::OpenNowPlaying(true));
    }
    if focus < 0.99 {
        let names = t.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", ");
        let g = widgets::galley(ui, &names, theme::regular(11.0), theme::text_dim(), text_w);
        let ap = pos2(tcx - g.size().x / 2.0, cy + 1.0);
        let ar = Rect::from_min_size(ap, g.size());
        ui.painter().galley(ap, g, theme::with_alpha(theme::text_dim(), 1.0 - focus));
        if let Some((a, id)) = t.artists.iter().find_map(|a| a.id.as_ref().map(|id| (a, id))) {
            let resp = ui.interact(ar, ui.scope_id().with("bar-artist"), Sense::click());
            let resp = named(resp, &a.name).on_hover_cursor(egui::CursorIcon::PointingHand);
            if resp.hovered() {
                ui.painter().hline(ar.x_range(), ar.bottom(), egui::Stroke::new(1.0, theme::text_dim()));
            }
            if resp.clicked() {
                app.actions.push(Action::Open(Target::Artist(id.clone())));
            }
        }
    }
    if focus > 0.0 && total > 0.0 {
        let col = theme::with_alpha(theme::text(), focus);
        let g = widgets::galley(ui, &fmt_time(pos as u32), theme::semibold(11.0), theme::text(), 80.0);
        ui.painter().galley(pos2(text_l, cy + 1.0), g, col);
        let g = widgets::galley(ui, &format!("-{}", fmt_time((total - pos).max(0.0) as u32)), theme::semibold(11.0), theme::text(), 80.0);
        ui.painter().galley(pos2(text_r - g.size().x, cy + 1.0), g, col);
    }

    let heard = if held { track.clone() } else { app.playing_track().cloned().or_else(|| track.clone()) };
    let liked = heard.as_ref().is_some_and(|t| app.liked.contains(&t.id));
    let r = Rect::from_center_size(pos2(star_x, cy - 2.0), Vec2::splat(24.0));
    let r = ui.interact(r, ui.scope_id().with("bar-like"), Sense::click());
    let r = named(r, if liked { "Remove from favourites" } else { "Add to favourites" }).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(&ctx, r.id.with("h"), r.hovered(), 0.15);
    let pop = heard.as_ref().map_or(0.0, |t| theme::anim_bool(&ctx, ("liked", &t.id), liked, 0.3));
    let tint = theme::lerp_color(ink(h), accent, pop);
    glyphs::draw(ui, r.rect.center(), 14.0, glyphs::STAR, tint);
    if pop > 0.0 {
        glyphs::draw(ui, r.rect.center(), 14.0, glyphs::STAR_FILLED, theme::with_alpha(tint, tint.a() as f32 / 255.0 * pop));
    }
    if r.clicked()
        && let Some(t) = heard
    {
        app.actions.push(Action::Like(t, !liked));
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
}

fn account(app: &mut App, ui: &mut Ui, r: Rect) {
    let resp = ui.interact(r, ui.scope_id().with("account"), Sense::click());
    let resp = named(resp, "Account").on_hover_cursor(egui::CursorIcon::PointingHand);
    let rad = r.width() / 2.0;
    let initial = app.account_name.as_deref().and_then(|n| n.chars().next()).unwrap_or('?');
    if app.logged_in {
        ui.painter().circle_filled(r.center(), rad, theme::lerp_color(app.accent_color(), Color32::BLACK, 0.35));
        if let Some(avatar) = &app.account_avatar {
            widgets::paint_remote(ui, avatar, r, rad, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), Color32::WHITE);
        } else {
            ui.painter().text(r.center(), Align2::CENTER_CENTER, initial, theme::bold(15.0), Color32::WHITE);
        }
    } else {
        ui.painter().circle_filled(r.center(), rad, theme::glass(0.12));
        glyphs::draw(ui, r.center(), 18.0, glyphs::PERSON_CIRCLE, theme::text_dim());
    }
    if resp.clicked() {
        app.actions.push(Action::Go(Page::Settings));
    }
}

fn mark(ui: &Ui, r: Rect, tint: Color32) {
    glyphs::draw_in(ui, r, glyphs::MARK, tint);
}

pub fn unmute_id() -> egui::Id {
    egui::Id::unique("unmute")
}

pub fn lyrics_drawer(app: &mut App, ui: &mut Ui, now: f64) {
    ui.horizontal(|ui| {
        ui.label(egui::RichText::new("Lyrics").font(theme::bold(20.0)));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.add_space(super::titlebar::right_gap(ui.ctx(), ui.max_rect(), 0.0));
            if icon_button(ui, icon::X, 16.0, "Close lyrics", false).clicked() {
                app.lyrics_open = false;
            }
        });
    });
    ui.add_space(8.0);
    if app.current().is_none() {
        ui.label(egui::RichText::new("Play a song to see its lyrics here.").color(theme::text_dim()));
        return;
    }
    let rect = ui.available_rect_before_wrap();
    super::nowplaying::lyrics_panel(app, ui, rect, now, true);
}

pub fn queue_drawer(app: &mut App, ui: &mut Ui) {
    ui.horizontal(|ui| {
        ui.label(egui::RichText::new("Up next").font(theme::bold(20.0)));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.add_space(super::titlebar::right_gap(ui.ctx(), ui.max_rect(), 0.0));
            if icon_button(ui, icon::X, 16.0, "Close queue", false).clicked() {
                app.queue_open = false;
            }
        });
    });
    ui.add_space(8.0);
    queue_list(app, ui, "drawer");
}

pub fn queue_list(app: &mut App, ui: &mut Ui, salt: &str) {
    let rows = super::pages::Rows::new(app, ui.input(|i| i.time));
    egui::ScrollArea::vertical().id_salt(("queue", salt)).auto_shrink(false).show(ui, |ui| {
        if app.queue.tracks.is_empty() {
            ui.label(egui::RichText::new("Nothing queued yet").color(theme::text_dim()));
            return;
        }
        let start = app.queue.index.unwrap_or(0);
        ui.checkbox(&mut app.autoplay, egui::RichText::new("Autoplay similar songs").color(theme::text_dim())).on_hover_cursor(egui::CursorIcon::PointingHand);
        ui.add_space(6.0);
        let (queue, liked, acts, has_current, by) = (&app.queue.tracks, &app.liked, &mut app.actions, app.queue.index.is_some(), &app.jam.by);
        widgets::visible_rows(ui, queue.len() - start, |ui, k| {
            let i = start + k;
            let t = &queue[i];
            let current = k == 0 && has_current;
            let o = widgets::RowOpts { current, added_by: by.get(&t.id).cloned(), ..rows.opts(liked, t, None, false) };
            let resp = widgets::track_row(ui, t, &o, acts);
            if resp.clicked() {
                acts.push(if current { Action::TogglePlay } else { Action::QueueJump(i) });
            }
            if !current && ui.rect_contains_pointer(Rect::from_min_max(resp.rect.min, pos2(resp.rect.right() + 42.0, resp.rect.bottom()))) {
                let x = Rect::from_center_size(resp.rect.right_center() - vec2(26.0, 0.0), Vec2::splat(28.0));
                let xr = named(ui.interact(x, resp.id.with("rm"), Sense::click()), "Remove from queue").on_hover_cursor(egui::CursorIcon::PointingHand);
                ui.painter().circle_filled(x.center(), 14.0, theme::panel(0.6));
                icon_at(ui, x.center(), icon::X, 14.0, theme::text());
                if xr.clicked() {
                    acts.push(Action::QueueRemove(i));
                }
            }
        });
    });
}

pub fn toast(app: &mut App, ctx: &egui::Context, now: f64) {
    let Some((msg, shown)) = &app.toast else { return };
    if *shown < 0.0 {
        return;
    }
    let age = (now - shown) as f32;
    let t = theme::ease_out(age / 0.25) * (1.0 - ((age - 4.1) / 0.4).clamp(0.0, 1.0));
    let screen = ctx.content_rect();
    egui::Area::new(egui::Id::unique("toast"))
        .order(egui::Order::Tooltip)
        .pivot(Align2::CENTER_BOTTOM)
        .fixed_pos(Pos2::new(screen.center().x, if screen.height() < 200.0 { screen.center().y + 22.0 } else { screen.bottom() - 48.0 } + 14.0 * (1.0 - t)))
        .show(ctx, |ui| {
            ui.set_opacity(t);
            egui::Frame::new()
                .fill(theme::menu_fill())
                .corner_radius(12)
                .inner_margin(egui::Margin::symmetric(18, 12))
                .shadow(egui::epaint::Shadow { offset: [0, 8], blur: 24, spread: 0, color: theme::shade(0.45) })
                .show(ui, |ui| {
                    ui.set_max_width(520.0);
                    ui.label(egui::RichText::new(msg.as_str()).font(theme::regular(14.0)).color(theme::text()));
                });
        });
    if (0.25..4.1).contains(&age) {
        ctx.request_repaint_after(std::time::Duration::from_secs_f32(4.1 - age));
    } else {
        ctx.request_repaint();
    }
}
