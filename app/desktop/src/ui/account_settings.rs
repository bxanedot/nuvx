use std::sync::atomic::Ordering;

use egui::{RichText, Ui, vec2};

use super::glyphs;
use super::pages::{divider, glyph_row, section_label, setting_row, settings_card, toggle};
use super::settings_pages::{dropdown_button, option_row};
use super::widgets::pill;
use super::App;
use crate::theme::{self, icon};
pub(super) fn settings_account(app: &mut App, ui: &mut Ui) {
    let busy = app.signing_in.load(Ordering::Relaxed);
    let (mut sign_in, mut sign_out) = (false, false);
    let now = ui.input(|i| i.time);
    if app.logged_in && app.channels_asked.is_none_or(|at| now - at > 60.0) {
        app.channels_asked = Some(now);
        app.backend.request(crate::backend::Req::Channels);
    }
    settings_card(ui, |ui| {
        if app.logged_in {
            let name = app.account_name.clone().unwrap_or_else(|| "YouTube Music".to_owned());
            glyph_row(ui, Some(glyphs::YOUTUBE_MUSIC), &name, Some("YouTube Music account. Saved encrypted in Windows Credential Manager."), |ui| {
                sign_out = pill(ui, "Sign out", Some(icon::LOG_OUT), false).clicked();
            });
            if app.channels.as_ref().is_some_and(|c| c.list.len() > 1 || !c.list.iter().any(|l| l.page_id == c.current)) {
                divider(ui);
                glyph_row(ui, Some(glyphs::PERSON_CIRCLE), "Listen as", Some("A Google account can have Personal and Brand profiles. Each has its own library, likes and history."), |ui| {
                    channel_picker(app, ui);
                });
            }
        } else {
            glyph_row(ui, Some(glyphs::YOUTUBE_MUSIC), "Not signed in", Some("Sign in with Google to get your YouTube Music home feed, library and likes."), |ui| {
                if busy {
                    ui.spinner();
                } else {
                    sign_in = pill(ui, "Sign in with Google", Some(icon::CIRCLE_USER), true).clicked();
                }
            });
        }
    });
    if busy {
        ui.add_space(6.0);
        ui.label(RichText::new("Finish signing in in the window that opened.").font(theme::regular(12.5)).color(theme::text_dim()));
    }
    if sign_in {
        app.start_sign_in(ui.ctx());
    }
    if sign_out {
        app.backend.request(crate::backend::Req::Logout);
    }
    super::transfer::spotify_section(app, ui);
    settings_integrations(app, ui);
}

fn channel_picker(app: &mut App, ui: &mut Ui) {
    let Some(crate::backend::Channels { list, current, epoch }) = &app.channels else { return };
    let label = |c: &crate::ytm::Channel| match (&c.handle, c.has_channel) {
        (Some(h), _) => format!("{} ({h})", c.name),
        (None, false) => format!("{} (No channel)", c.name),
        (None, true) => c.name.clone(),
    };
    let shown = match list.iter().find(|c| c.page_id == *current) {
        Some(c) => c.handle.clone().unwrap_or_else(|| label(c)),
        None => "Another channel".to_owned(),
    };
    if app.transfer.running() {
        ui.label(RichText::new("Not while a transfer runs").font(theme::regular(13.0)).color(theme::text_dim()));
        return;
    }
    let switching = app.switching_to.is_some();
    let btn = dropdown_button(ui, if switching { "Switching…" } else { &shown }, 220.0, "Channel");
    let mut pick = None;
    egui::Popup::menu(&btn).show(|ui| {
        ui.set_width(300.0);
        ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
        egui::ScrollArea::vertical().max_height(320.0).show(ui, |ui| {
            ui.add_space(4.0);
            for c in list {
                if option_row(ui, &label(c), c.page_id == *current).clicked() {
                    pick = Some(c.clone());
                    ui.close();
                }
            }
            ui.add_space(4.0);
        });
    });
    if let Some(c) = pick.filter(|c| c.page_id != *current && !switching) {
        app.switching_to = Some(label(&c));
        app.backend.request(crate::backend::Req::SwitchChannel { page_id: c.page_id, epoch: *epoch });
    }
}

fn settings_integrations(app: &mut App, ui: &mut Ui) {
    let mut discord_enabled = app.discord_enabled;
    section_label(ui, "Rich presence", None);
    settings_card(ui, |ui| {
        glyph_row(ui, Some(glyphs::DISCORD), "Discord", Some("Show what you are playing on your Discord profile."), |ui| {
            toggle(ui, &mut discord_enabled, "Discord");
        });
    });
    section_label(ui, "Scrobbling", None);
    settings_card(ui, |ui| {
        let busy = app.lastfm_busy.load(Ordering::Relaxed);
        let desc = match &app.lastfm_user {
            Some(name) => format!("Scrobbling what you play to {name}'s profile."),
            None if busy => "Allow nuvx on the Last.fm page that opened in your browser.".to_owned(),
            None => "Scrobble what you play to your Last.fm profile.".to_owned(),
        };
        let (mut connect, mut disconnect) = (false, false);
        glyph_row(ui, Some(glyphs::HISTORY), "Last.fm", Some(&desc), |ui| {
            if app.lastfm_user.is_some() {
                disconnect = pill(ui, "Disconnect", None, false).clicked();
            } else if busy {
                ui.spinner();
            } else {
                connect = pill(ui, "Connect", None, false).clicked();
            }
        });
        if connect {
            app.lastfm_sign_in();
        }
        if disconnect {
            app.lastfm_sign_out();
        }
        if app.lastfm_user.is_some() {
            divider(ui);
            setting_row(ui, "Send now playing", Some("Your Last.fm profile shows the song as it starts, before it's scrobbled."), |ui| {
                toggle(ui, &mut app.lastfm_now_playing, "Send now playing");
            });
            divider(ui);
            setting_row(ui, "Primary artist only", Some("Scrobble a song under its first artist, not everyone credited on it."), |ui| {
                toggle(ui, &mut app.lastfm_primary_only, "Primary artist only");
            });
        }
    });
    if discord_enabled != app.discord_enabled {
        app.set_discord_enabled(discord_enabled);
    }
}
