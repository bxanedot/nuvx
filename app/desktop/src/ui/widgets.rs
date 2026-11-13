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
pub use rows::{active_state, play_pause_glyph, play_triangle, track_row, visible_rows, NowPlaying, RowOpts};
mod text;
pub use text::{galley, galley_wrapped, linked_text, text_at};
mod controls;
pub use controls::{chip, heart, icon_at, icon_button, pill};
mod cards;
pub use cards::card;
mod imagery;
pub use imagery::{loading_glow, paint_motion, paint_remote, shimmer_band, MotionSprite};
mod skeletons;
pub use skeletons::{skeleton, skeleton_rows};

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
