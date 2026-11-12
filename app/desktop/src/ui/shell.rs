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

fn mark(ui: &Ui, r: Rect, tint: Color32) {
    glyphs::draw_in(ui, r, glyphs::MARK, tint);
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
