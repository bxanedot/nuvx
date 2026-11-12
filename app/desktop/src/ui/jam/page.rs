use egui::{Align2, RichText, Sense, Ui, pos2, vec2};

use super::{join, leave, set_control, start};
use super::super::pages::{divider, page_title, section_label, setting_row, settings_card, toggle};
use super::super::widgets::{icon_at, pill};
use super::super::App;
use crate::jam::Jam;
use crate::theme::{self, icon};

pub fn page(app: &mut App, ui: &mut Ui) {
    let avail = ui.available_width();
    let w = avail.min(720.0);
    ui.horizontal(|ui| {
        ui.add_space(((avail - w) / 2.0).max(0.0));
        ui.vertical(|ui| {
            ui.set_width(w);
            page_title(ui, "Listen together");
            if app.jam.active() {
                room(app, ui);
            } else {
                lobby(app, ui);
            }
        });
    });
}

fn dim(ui: &mut Ui, text: &str) {
    ui.label(RichText::new(text).font(theme::regular(13.5)).color(theme::text_dim()));
}

fn lobby(app: &mut App, ui: &mut Ui) {
    dim(ui, "Start a party and friends hear what you play, at the same moment. Everyone listens on their own nuvx, and anyone can add songs.");
    section_label(ui, "Start a party", None);
    settings_card(ui, |ui| {
        let hint = app.account_name.clone().unwrap_or_else(|| "Guest".to_owned());
        setting_row(ui, "Your name", Some("What others in the party see."), |ui| {
            let (rect, _) = ui.allocate_exact_size(vec2(200.0, 36.0), Sense::hover());
            ui.painter().rect_filled(rect, 10.0, theme::glass(0.08));
            let inner = rect.shrink2(vec2(12.0, 4.0));
            ui.put(
                inner,
                egui::TextEdit::singleline(&mut app.jam.name)
                    .hint_text(RichText::new(hint).color(theme::text_faint()))
                    .font(theme::regular(14.0))
                    .char_limit(32)
                    .frame(egui::Frame::NONE)
                    .align(egui::Align2::LEFT_CENTER)
                    .desired_width(inner.width()),
            );
        });
        divider(ui);
        setting_row(ui, "Host a party", Some("You get a code and a link to share. You control playback."), |ui| {
            if pill(ui, "Start", Some(icon::USERS), true).clicked() {
                start(app);
            }
        });
    });
    section_label(ui, "Join a party", None);
    settings_card(ui, |ui| {
        ui.add_space(12.0);
        dim(ui, "Type the code from the host, or open the link they shared.");
        ui.add_space(10.0);
        let mut join_now = false;
        ui.allocate_ui_with_layout(vec2(ui.available_width(), 42.0), egui::Layout::right_to_left(egui::Align::Center), |ui| {
            join_now |= pill(ui, "Join", None, true).clicked();
            ui.add_space(10.0);
            let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 42.0), Sense::hover());
            ui.painter().rect_filled(rect, 10.0, theme::glass(0.08));
            let inner = rect.shrink2(vec2(14.0, 4.0));
            let edit = ui.put(
                inner,
                egui::TextEdit::singleline(&mut app.jam.code_input)
                    .hint_text(RichText::new("ABC234").color(theme::text_faint()))
                    .font(theme::semibold(16.0))
                    .char_limit(8)
                    .frame(egui::Frame::NONE)
                    .align(egui::Align2::LEFT_CENTER)
                    .desired_width(inner.width()),
            );
            if edit.changed() {
                app.jam.error = None;
            }
            join_now |= edit.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
        });
        if join_now {
            let code = app.jam.code_input.clone();
            join(app, &code);
        }
        if let Some(e) = &app.jam.error {
            ui.add_space(8.0);
            ui.label(RichText::new(e).font(theme::regular(13.0)).color(theme::RED));
        }
        ui.add_space(14.0);
    });
}

fn room(app: &mut App, ui: &mut Ui) {
    let code = app.jam.conn.as_ref().map(Jam::code).unwrap_or_default();
    let host = app.jam.is_host();
    let status = if !app.jam.welcomed {
        Some("Connecting…")
    } else if app.jam.reconnecting {
        Some("Connection lost, reconnecting…")
    } else {
        None
    };
    settings_card(ui, |ui| {
        ui.add_space(16.0);
        dim(ui, if host { "Share this code or link with friends" } else { "You're in the party" });
        ui.add_space(6.0);
        let spaced: String = code.chars().map(|c| c.to_string()).collect::<Vec<_>>().join(" ");
        ui.label(RichText::new(spaced).font(theme::bold(40.0)).color(theme::text()));
        if let Some(s) = status {
            ui.horizontal(|ui| {
                ui.spinner();
                dim(ui, s);
            });
        }
        ui.add_space(10.0);
        ui.horizontal(|ui| {
            if pill(ui, "Copy link", Some(icon::LINK), true).clicked() {
                app.share_pending = Some(crate::jam::share_link(&code));
                app.toast("Link copied");
            }
            ui.add_space(8.0);
            if pill(ui, "Copy code", Some(icon::COPY), false).clicked() {
                app.share_pending = Some(code.clone());
                app.toast("Code copied");
            }
        });
        ui.add_space(16.0);
    });

    section_label(ui, "Playback", None);
    settings_card(ui, |ui| {
        if host {
            let mut on = app.jam.control;
            setting_row(ui, "Everyone can control the music", Some("Guests can add songs, play and pause, skip, seek, and jump to a song in the queue. Off: only you can."), |ui| {
                if toggle(ui, &mut on, "Everyone can control the music").changed() {
                    set_control(app, on);
                }
            });
        } else {
            let desc = match (app.jam.queue, app.jam.control) {
                (true, true) => "You can add songs and control playback.",
                (true, false) => "Songs you play are added to the queue. The host controls playback.",
                (false, true) => "You can control playback. The host turned off adding songs.",
                (false, false) => "The host controls the queue and playback.",
            };
            setting_row(ui, "Following the host", Some(desc), |_| {});
        }
        divider(ui);
        let (title, desc, label) = if host {
            ("Leave the party", "The party goes on with the next host; your music keeps playing.", "Leave")
        } else {
            ("Leave the party", "The music keeps playing here, on your own.", "Leave")
        };
        setting_row(ui, title, Some(desc), |ui| {
            if pill(ui, label, Some(icon::LOG_OUT), false).clicked() {
                leave(app);
            }
        });
    });

    let n = app.jam.members.len();
    section_label(ui, "People", Some(&format!("{n} listening")));
    settings_card(ui, |ui| {
        ui.add_space(6.0);
        for m in &app.jam.members {
            let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 40.0), Sense::hover());
            let is_host = m.id == app.jam.host;
            icon_at(ui, pos2(rect.left() + 12.0, rect.center().y), if is_host { icon::CROWN } else { icon::HEADPHONES }, 17.0, if is_host { app.accent } else { theme::text_dim() });
            let you = if m.id == app.jam.you { " (you)" } else { "" };
            ui.painter().text(pos2(rect.left() + 34.0, rect.center().y), Align2::LEFT_CENTER, format!("{}{you}", m.name), theme::semibold(14.5), theme::text());
            if is_host {
                ui.painter().text(pos2(rect.right() - 4.0, rect.center().y), Align2::RIGHT_CENTER, "Host", theme::regular(12.5), theme::text_dim());
            }
        }
        ui.add_space(6.0);
    });
}
