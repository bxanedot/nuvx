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
mod search;
pub use search::{search_field, suggestions};
mod player_bar;
pub use player_bar::{player_bar, unmute_id};
mod drawers;
pub use drawers::{lyrics_drawer, queue_drawer, queue_list};
mod toast;
pub use toast::toast;

fn mark(ui: &Ui, r: Rect, tint: Color32) {
    glyphs::draw_in(ui, r, glyphs::MARK, tint);
}
