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
use menus::lyric_options;
pub(super) use audio_info::{audio_badge, audio_badge_icon, audio_badge_icon_width, audio_badge_width};
use lyrics::{plain_lyrics, synced, sync_color, sync_label};
use super::{Action, App, NpTab, Repeat, karaoke};
use crate::lyrics::{Lyrics, Sync, SyncKind};
use crate::shader::Params;
use crate::theme::{self, icon};

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
