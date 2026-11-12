use egui::{Align2, Color32, CornerRadius, Pos2, Rect, Response, Sense, Ui, Vec2, pos2, vec2};

use super::Action;
use crate::model::{Card, Link, Target, Thumb, Track, pick_thumb, sized};
use crate::theme::{self, icon};
mod shelf;
pub use shelf::{h_scroll, shelf_header};
mod covers;
pub use covers::{paint_cover, paint_cover_fill, paint_cover_xfade, paint_cover_xfade_px, paint_disc, preload_cover};
mod menus;
use menus::{card_menu_contents, more_button};
pub use menus::{menu_action, menu_choice, menu_glyph_action, song_actions, song_menu};
const ROW_H: f32 = 56.0;
mod rows;
pub use rows::{active_state, equalizer_shown, play_pause_glyph, play_triangle, track_row, visible_rows, NowPlaying, RowOpts};
mod text;
pub use text::{galley, galley_wrapped, linked_text, text_at};
mod controls;
pub use controls::{chip, heart, icon_at, icon_button, pill};
mod cards;
pub use cards::card;

pub fn paint_cover_px(ui: &Ui, rect: Rect, thumbs: &[Thumb], radius: f32, zoom: f32, px: u32) {
    covers::paint_cover_px(ui, rect, thumbs, radius, zoom, px);
}

pub fn named(resp: Response, name: &str) -> Response {
    resp.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, true, name));
    resp
}

pub fn soft_shadow(ui: &Ui, rect: Rect, radius: f32, blur: f32, alpha: f32) {
    let s = egui::epaint::Shadow { offset: [0, (blur * 0.35) as i8], blur: blur as u8, spread: 0, color: theme::shade(alpha) };
    ui.painter().add(s.as_shape(rect, CornerRadius::same(radius as u8)));
}

pub fn shimmer_band(ui: &Ui, r: Rect, radius: f32, time: f64) {
    shimmer_band_alpha(ui, r, radius, time, 0.04);
}

pub fn loading_glow(ui: &Ui, r: Rect, time: f64, color: Color32) {
    let p = (time * 0.7).rem_euclid(2.0) as f32;
    let t = if p > 1.0 { 2.0 - p } else { p };
    let t = t * t * (3.0 - 2.0 * t);
    let len = r.width() * 0.22;
    let x = r.left() + (r.width() - len) * t;
    let steps = 12;
    for i in 0..steps {
        let f = (i as f32 + 0.5) / steps as f32;
        let a = 1.0 - (f * 2.0 - 1.0).abs();
        let seg = Rect::from_min_max(pos2(x + len * i as f32 / steps as f32, r.top()), pos2(x + len * (i + 1) as f32 / steps as f32, r.bottom()));
        ui.painter().rect_filled(seg, 0.0, color.gamma_multiply(a * a));
    }
    ui.ctx().request_repaint();
}

fn shimmer_band_alpha(ui: &Ui, r: Rect, radius: f32, time: f64, alpha: f32) {
    let phase = ((time * 0.8) % 1.6) as f32 - 0.3;
    let x = r.left() + (r.width() + 400.0) * phase - 200.0;
    let band = Rect::from_min_max(pos2((x - 120.0).max(r.left()), r.top()), pos2((x + 120.0).min(r.right()), r.bottom()));
    if band.width() > 0.0 {
        ui.painter().rect_filled(band, radius, theme::glass(alpha));
    }
}

pub fn paint_remote(ui: &Ui, url: &str, rect: Rect, radius: f32, uv: Rect, tint: Color32) {
    const FADE: f64 = 0.22;
    let ctx = ui.ctx();
    let now = ctx.input(|i| i.time);
    let id = egui::Id::unique(("img-fade", url));
    match egui::Image::new(url).load_for_size(ctx, rect.size()) {
        Ok(egui::load::TexturePoll::Ready { texture }) => {
            let a = match ctx.data(|d| d.get_temp::<Option<f64>>(id)) {
                Some(Some(t0)) if now - t0 < FADE => ((now - t0) / FADE) as f32,
                Some(None) => {
                    ctx.data_mut(|d| d.insert_temp(id, Some(now)));
                    0.0
                }
                _ => 1.0,
            };
            if a < 1.0 {
                ctx.request_repaint();
            }
            egui::Image::from_texture(texture).uv(uv).corner_radius(CornerRadius::same(radius as u8)).tint(tint.gamma_multiply(theme::ease_out(a))).paint_at(ui, rect);
        }
        Ok(egui::load::TexturePoll::Pending { .. }) => {
            ctx.data_mut(|d| d.insert_temp::<Option<f64>>(id, None));
            shimmer_band(ui, rect, radius, now);
            ctx.request_repaint_after(std::time::Duration::from_millis(66));
        }
        Err(_) => {}
    }
}

pub struct MotionSprite {
    pub id: egui::TextureId,
    pub size: Vec2,
}

pub fn paint_motion(ui: &Ui, rect: Rect, radius: f32, s: &MotionSprite) {
    if !ui.is_rect_visible(rect) || rect.width() <= 0.0 || rect.height() <= 0.0 {
        return;
    }
    let tex_aspect = s.size.x / s.size.y;
    let rect_aspect = rect.width() / rect.height();
    let uv = if rect_aspect > tex_aspect {
        Rect::from_center_size(pos2(0.5, 0.5), vec2(1.0, tex_aspect / rect_aspect))
    } else {
        Rect::from_center_size(pos2(0.5, 0.5), vec2(rect_aspect / tex_aspect, 1.0))
    };
    let shape = egui::epaint::RectShape::filled(rect, CornerRadius::same(radius as u8), Color32::WHITE)
        .with_texture(s.id, uv);
    ui.painter().add(shape);
}

pub fn skeleton_rows(ui: &mut Ui, time: f64, rows: usize) {
    for _ in 0..rows {
        let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), ROW_H), Sense::hover());
        if !ui.is_rect_visible(r) {
            continue;
        }
        let cy = r.center().y;
        for (b, radius) in [
            (Rect::from_min_size(pos2(r.left() + 10.0, cy - 20.0), Vec2::splat(40.0)), theme::thumb_radius()),
            (Rect::from_min_size(pos2(r.left() + 64.0, cy - 13.0), vec2((r.width() * 0.3).min(260.0), 11.0)), 4.0),
            (Rect::from_min_size(pos2(r.left() + 64.0, cy + 4.0), vec2((r.width() * 0.18).min(160.0), 9.0)), 4.0),
        ] {
            ui.painter().rect_filled(b, radius, theme::glass(0.06));
            shimmer_band(ui, b, radius, time);
        }
        ui.ctx().request_repaint_after(std::time::Duration::from_millis(66));
    }
}

pub fn skeleton(ui: &mut Ui, time: f64, rows: usize, card: f32) {
    let shimmer = |ui: &Ui, r: Rect, radius: f32| {
        ui.painter().rect_filled(r, radius, theme::glass(0.06));
        shimmer_band(ui, r, radius, time);
    };
    let top = ui.cursor().top();
    for _ in 0..rows {
        ui.add_space(26.0);
        let (t, _) = ui.allocate_exact_size(vec2(220.0, 26.0), Sense::hover());
        shimmer(ui, t, 6.0);
        ui.add_space(14.0);
        ui.horizontal(|ui| {
            for _ in 0..8 {
                let (r, _) = ui.allocate_exact_size(vec2(card, card + 50.0), Sense::hover());
                shimmer(ui, Rect::from_min_size(r.min, Vec2::splat(card)), 8.0);
                shimmer(ui, Rect::from_min_size(r.min + vec2(0.0, card + 12.0), vec2(card * 0.8, 12.0)), 4.0);
                shimmer(ui, Rect::from_min_size(r.min + vec2(0.0, card + 32.0), vec2(card * 0.5, 10.0)), 4.0);
            }
        });
    }
    if ui.is_rect_visible(Rect::from_x_y_ranges(ui.max_rect().x_range(), top..=ui.cursor().top())) {
        ui.ctx().request_repaint_after(std::time::Duration::from_millis(66));
    }
}
