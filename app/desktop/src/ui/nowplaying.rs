use egui::{Color32, Rect, Sense, Ui, Vec2, pos2, vec2};

use super::widgets::{self, icon_button, named, text_at};
mod lyrics;
mod audio_info;
mod cover;
use cover::{art_side, cover_column};
pub(super) use cover::lyrics_panel;
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
