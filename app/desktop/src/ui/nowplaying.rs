use egui::{Color32, Rect, Sense, Ui, Vec2, pos2, vec2};

use super::widgets::{self, icon_button, named, text_at};
mod lyrics;
mod audio_info;
mod cover;
use cover::{art_side, cover_column};
pub(super) use cover::lyrics_panel;
mod menus;
mod view;
pub use view::overlay;
use menus::options_menu;
pub(super) use audio_info::{audio_badge, audio_badge_icon, audio_badge_icon_width, audio_badge_width};
use lyrics::{plain_lyrics, synced, sync_color, sync_label};
use super::{Action, App, NpTab, Repeat, karaoke};
use crate::lyrics::{Lyrics, Sync, SyncKind};
use crate::shader::Params;
use crate::theme::{self, icon};

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
