use egui::{Align2, Rect, RichText, Sense, Ui, Vec2, pos2, vec2};

use super::glyphs;
use super::pages::{divider, page_title, section_label, setting_row, settings_card, toggle};
use super::widgets::{self, pill};
use super::{Action, App, Page};
use crate::theme::{self, icon};
pub(super) fn settings(app: &mut App, ui: &mut Ui, pane: Option<super::SettingsPane>) {
    let avail = ui.available_width();
    let w = avail.min(640.0);
    ui.horizontal(|ui| {
        ui.add_space(((avail - w) / 2.0).max(0.0));
        ui.vertical(|ui| {
            ui.set_width(w);
            match pane {
                None => settings_list(app, ui),
                Some(p) => settings_pane(app, ui, p),
            }
            ui.add_space(36.0);
            let (rect, _) = ui.allocate_exact_size(vec2(w, 16.0), Sense::hover());
            let line = format!("nuvx {} · by bxane · nuvx.lol", env!("CARGO_PKG_VERSION"));
            ui.painter().text(rect.center(), Align2::CENTER_CENTER, line, theme::regular(11.5), theme::text_dim());
            ui.add_space(40.0);
        });
    });
}

const PANES: [(super::SettingsPane, glyphs::Glyph, &str, &str); 11] = {
    use super::SettingsPane::*;
    [
        (Account, glyphs::PERSON, "Account & integrations", "YouTube Music, Spotify, Discord and Last.fm"),
        (Playback, glyphs::PLAY_OUTLINE, "Playback", "Autoplay, volume, output device, Automix, crossfade, Hi-Res and Atmos"),
        (Equalizer, glyphs::WAVEFORM, "Equalizer", "Tone, seven bands and balance"),
        (Downloads, glyphs::DOWNLOAD, "Downloads", "Where downloaded songs go"),
        (Lyrics, glyphs::LYRICS_OFF, "Lyrics", "Lyrics providers, timing, translation and romanization"),
        (Artists, glyphs::PENCIL, "Artists", "Artist signatures"),
        (Appearance, glyphs::PAINTBRUSH, "Appearance", "Light or dark, background, font, hidden playlists, classic search, tabs"),
        (Window, glyphs::MAXIMIZE_2, "Window", "Material, close to tray, sidebar, renderer"),
        (Network, glyphs::WIFI, "Network", "Proxy and connection test"),
        (Import, glyphs::IMPORT, "Import", "Transfer from Spotify"),
        (Shortcuts, glyphs::KEYBOARD, "Keyboard shortcuts", "Every key the app answers to"),
    ]
};

fn settings_list(app: &mut App, ui: &mut Ui) {
    page_title(ui, "Settings");
    ui.add_space(14.0);
    for (pane, glyph, title, desc) in PANES {
        let w = ui.available_width();
        let (rect, resp) = ui.allocate_exact_size(vec2(w, 54.0), Sense::click());
        let resp = widgets::named(resp, title).on_hover_cursor(egui::CursorIcon::PointingHand);
        let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
        if h > 0.0 {
            ui.painter().rect_filled(rect.expand2(vec2(8.0, 0.0)), 10.0, theme::glass(0.06 * h));
        }
        let tile = Rect::from_center_size(pos2(rect.left() + 16.0, rect.center().y), Vec2::splat(32.0));
        ui.painter().rect_filled(tile, 8.0, theme::glass(0.1));
        glyphs::draw(ui, tile.center(), 17.0, glyph, theme::text());
        let tx = tile.right() + 14.0;
        let tw = (rect.right() - 30.0 - tx).max(40.0);
        let tg = widgets::galley(ui, title, theme::semibold(13.5), theme::text(), tw);
        let dg = widgets::galley(ui, desc, theme::regular(11.5), theme::text_dim(), tw);
        let top = rect.center().y - (tg.size().y + 2.0 + dg.size().y) / 2.0;
        let th = tg.size().y;
        ui.painter().galley(pos2(tx, top), tg, theme::text());
        ui.painter().galley(pos2(tx, top + th + 2.0), dg, theme::text_dim());
        widgets::icon_at(ui, pos2(rect.right() - 8.0, rect.center().y), icon::CHEVRON_RIGHT, 15.0, theme::text_dim());
        if resp.clicked() {
            app.actions.push(Action::Go(Page::Setting(pane)));
        }
    }
}

fn settings_pane(app: &mut App, ui: &mut Ui, pane: super::SettingsPane) {
    use super::SettingsPane::*;
    ui.add_space(18.0);
    let g = widgets::galley(ui, "‹ Settings", theme::semibold(13.0), theme::text_dim(), 200.0);
    let (rect, resp) = ui.allocate_exact_size(g.size(), Sense::click());
    let resp = widgets::named(resp, "Back to Settings").on_hover_cursor(egui::CursorIcon::PointingHand);
    ui.painter().galley(rect.min, g, if resp.hovered() { theme::text() } else { theme::text_dim() });
    if resp.clicked() {
        app.actions.push(Action::Go(Page::Settings));
    }
    let title = PANES.iter().find(|p| p.0 == pane).map_or("Settings", |p| p.2);
    let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 42.0), Sense::hover());
    ui.painter().text(pos2(rect.left(), rect.center().y), Align2::LEFT_CENTER, title, theme::bold(28.0), theme::text());
    match pane {
        Account => super::account_settings::settings_account(app, ui),
        Playback => super::playback_settings::settings_playback(app, ui),
        Equalizer => super::equalizer_settings::settings_equalizer(app, ui),
        Downloads => settings_downloads(ui),
        Lyrics => settings_lyrics(app, ui),
        Artists => settings_artists(app, ui),
        Appearance => {
            super::appearance_settings::settings_appearance(app, ui);
            section_label(ui, "Search", None);
            settings_card(ui, |ui| {
                setting_row(ui, "Classic search", Some("A plain search page instead of the colour category tiles."), |ui| {
                    toggle(ui, &mut app.classic_search, "Classic search");
                });
                divider(ui);
                setting_row(ui, "Enable YouTube Search", Some("A YouTube switch over the results, for videos beyond YouTube Music's."), |ui| {
                    if toggle(ui, &mut app.youtube_search, "Enable YouTube Search").changed() && !app.youtube_search {
                        app.search_youtube = false;
                    }
                });
            });
            section_label(ui, "Tabs", None);
            settings_card(ui, |ui| {
                setting_row(ui, "Hide Radio tab", Some("Removes live radio stations from the sidebar."), |ui| {
                    toggle(ui, &mut app.hide_radio, "Hide Radio tab");
                });
                divider(ui);
                setting_row(ui, "Hide Podcasts tab", Some("Removes podcasts from the sidebar."), |ui| {
                    toggle(ui, &mut app.hide_podcasts, "Hide Podcasts tab");
                });
                divider(ui);
                setting_row(ui, "Hide Quick picks", Some("Removes the Quick picks shelf from Home."), |ui| {
                    toggle(ui, &mut app.hide_quick_picks, "Hide Quick picks");
                });
                divider(ui);
                setting_row(ui, "Hide videos from Home", Some("Leaves music videos out of Home's shelves."), |ui| {
                    toggle(ui, &mut app.hide_home_videos, "Hide videos from Home");
                });
            });
        }
        Window => settings_window(app, ui),
        Network => {
            section_label(ui, "Network", None);
            settings_card(ui, |ui| super::network_settings::network(app, ui));
        }
        Import => super::transfer::settings_section(app, ui),
        Shortcuts => settings_shortcuts(ui),
    }
}

fn settings_downloads(ui: &mut Ui) {
    section_label(ui, "Downloads", None);
    ui.label(
        RichText::new("Download songs from their menu. They're saved at the best quality your account gets, tagged with their cover art, and play from the file, even offline.")
            .font(theme::regular(13.0))
            .color(theme::text_dim()),
    );
    ui.add_space(12.0);
    settings_card(ui, |ui| {
        let folder = crate::downloads::folder().display().to_string();
        setting_row(ui, "Download folder", Some(&folder), |ui| {
            if pill(ui, "Change", None, false).clicked() {
                crate::downloads::pick_folder(ui.ctx());
            }
            ui.add_space(6.0);
            if pill(ui, "Open", Some(icon::FOLDER_OPEN), false).clicked() {
                crate::downloads::open_folder();
            }
        });
    });
}

fn settings_lyrics(app: &mut App, ui: &mut Ui) {
    section_label(ui, "Lyrics", None);
    settings_card(ui, |ui| {
        setting_row(ui, "Romanization", Some("Japanese, Korean, Chinese, Cyrillic and other scripts in Latin letters. Timed with the lyrics when the source has it."), |ui| {
            toggle(ui, &mut app.show_roman, "Romanization");
        });
        divider(ui);
        setting_row(ui, "Translation", Some("Translated by Unison, or Google when it can't. Lyrics that ship their own translation use it."), |ui| {
            toggle(ui, &mut app.show_trans, "Translation");
        });
        divider(ui);
        setting_row(ui, "Translate into", None, |ui| {
            let current = super::lyric_prefs::LANGUAGES.iter().find(|(c, _)| *c == app.lyric_prefs.target).map_or(app.lyric_prefs.target.as_str(), |(_, n)| n);
            let btn = dropdown_button(ui, current, 200.0, "Translate into");
            let just_opened = btn.clicked();
            egui::Popup::menu(&btn).show(|ui| {
                ui.set_width(200.0);
                ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
                egui::ScrollArea::vertical().max_height(320.0).show(ui, |ui| {
                    ui.add_space(4.0);
                    for (code, name) in super::lyric_prefs::LANGUAGES {
                        let on = app.lyric_prefs.target == *code;
                        let row = option_row(ui, name, on);
                        if on && just_opened {
                            row.scroll_to_me(Some(egui::Align::Center));
                        }
                        if row.clicked() {
                            app.lyric_prefs.target = (*code).to_owned();
                            ui.close();
                        }
                    }
                    ui.add_space(4.0);
                });
            });
        });
        divider(ui);
        setting_row(ui, "Text size", Some("Make the lyrics larger or smaller. Works on top of the theme's own size."), |ui| {
            let (min, max, step) = super::lyric_prefs::TEXT_SCALE;
            let v = &mut app.lyric_prefs.text_scale;
            if ui.add_enabled(*v < max - 0.01, egui::Button::new("+").min_size(vec2(28.0, 26.0))).on_hover_text("Larger").clicked() {
                *v = ((*v + step) * 10.0).round() / 10.0;
            }
            ui.label(egui::RichText::new(format!("{:.0}%", *v * 100.0)).font(theme::semibold(13.0)));
            if ui.add_enabled(*v > min + 0.01, egui::Button::new("−").min_size(vec2(28.0, 26.0))).on_hover_text("Smaller").clicked() {
                *v = ((*v - step) * 10.0).round() / 10.0;
            }
        });
        divider(ui);
        for (title, desc, which) in [
            ("Timing offset", "Shift all lyrics later (+) or earlier (−).", 0),
            ("Word-synced trim", "Extra shift for syllable and word-synced lyrics.", 1),
            ("Line-synced trim", "Extra shift for line-synced lyrics.", 2),
        ] {
            setting_row(ui, title, Some(desc), |ui| {
                let v = match which {
                    0 => &mut app.lyric_prefs.global_offset,
                    1 => &mut app.lyric_prefs.word_trim,
                    _ => &mut app.lyric_prefs.line_trim,
                };
                if ui.add(egui::Button::new("+").min_size(vec2(28.0, 26.0))).on_hover_text("Later").clicked() {
                    *v = ((*v + 0.1) * 10.0).round() / 10.0;
                }
                ui.label(egui::RichText::new(format!("{:+.1} s", *v).replace("+0.0", "0.0")).font(theme::semibold(13.0)));
                if ui.add(egui::Button::new("−").min_size(vec2(28.0, 26.0))).on_hover_text("Earlier").clicked() {
                    *v = ((*v - 0.1) * 10.0).round() / 10.0;
                }
            });
            divider(ui);
        }
        setting_row(ui, "Lyrics sources", Some("Tried in this order. Switch one off to skip it; the toolbar under the lyrics picks a different one for a single song."), |_| {});
        let n = app.lyric_prefs.order.len();
        let mut swap: Option<(usize, usize)> = None;
        for i in 0..n {
            let (p, on) = app.lyric_prefs.order[i];
            let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 34.0), Sense::hover());
            let mut row = ui.new_child(egui::UiBuilder::new().max_rect(rect).layout(egui::Layout::left_to_right(egui::Align::Center)));
            let mut enabled = on;
            if toggle(&mut row, &mut enabled, p.name()).changed() {
                app.lyric_prefs.order[i].1 = enabled;
            }
            row.add_space(10.0);
            let kind = match p.sync() {
                crate::lyrics::SyncKind::Syllable => "syllable",
                crate::lyrics::SyncKind::Word => "word",
                crate::lyrics::SyncKind::Line => "line",
                crate::lyrics::SyncKind::Unsynced => "plain",
            };
            row.label(egui::RichText::new(p.name()).font(theme::semibold(13.5)).color(if on { theme::text() } else { theme::text_dim() }));
            row.label(egui::RichText::new(kind).font(theme::regular(12.0)).color(theme::text_dim()));
            row.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.add_enabled(i + 1 < n, egui::Button::new("↓").min_size(vec2(28.0, 24.0))).on_hover_text("Lower").clicked() {
                    swap = Some((i, i + 1));
                }
                if ui.add_enabled(i > 0, egui::Button::new("↑").min_size(vec2(28.0, 24.0))).on_hover_text("Higher").clicked() {
                    swap = Some((i, i - 1));
                }
            });
        }
        if let Some((a, b)) = swap {
            app.lyric_prefs.order.swap(a, b);
        }
        ui.add_space(6.0);
    });
}

fn settings_artists(app: &mut App, ui: &mut Ui) {
    section_label(ui, "Artist pages", None);
    settings_card(ui, |ui| {
        setting_row(ui, "Signature service", Some("A link to your own signature API. Artists get their handwritten signature, with “Signed by …” above it — beside About when Apple also letters them a wordmark; without a signature, their name is set in type."), |_| {});
        divider(ui);
        ui.add_space(12.0);
        if super::text_field(ui, "Link", &mut app.signature_api, false, "https://your-signature-service.example").changed() {
            app.signature_reset();
        }
        ui.add_space(12.0);
    });
}

fn settings_window(app: &mut App, ui: &mut Ui) {
    section_label(ui, "Window", None);
    settings_card(ui, |ui| {
        setting_row(ui, "Close to tray", Some("Clicking × hides the window and keeps the music playing. Click the tray icon to bring it back, or right-click it to quit. If the icon never shows up, launching nuvx again brings the window back; --no-tray starts a session where × quits for real."), |ui| {
            toggle(ui, &mut app.close_to_tray, "Close to tray");
        });
        divider(ui);
        setting_row(ui, "Collapse sidebar", Some("Show only icons in the sidebar, for narrow windows. The menu button at its top does the same."), |ui| {
            toggle(ui, &mut app.sidebar_collapsed, "Collapse sidebar");
        });
        divider(ui);
        let api = crate::renderer::api_name();
        let title = format!("Draw with {api}");
        let desc = format!(
            "Use {api} (wgpu) instead of OpenGL. It isn't faster for this app and takes about three times the memory; try it if OpenGL draws wrong on this PC. If {api} won't start, the app goes back to OpenGL."
        );
        setting_row(ui, &title, Some(&desc), |ui| {
            if toggle(ui, &mut app.wgpu_renderer, &title).changed() {
                use crate::renderer::Renderer;
                let r = if app.wgpu_renderer { Renderer::Wgpu } else { Renderer::OpenGl };
                if let Err(e) = crate::renderer::save(app.backend.data_dir(), r) {
                    log::warn!("renderer: couldn't save the setting: {e}");
                }
            }
        });
        if crate::material::SUPPORTED {
            divider(ui);
            use crate::material::Material;
            let desc = if cfg!(windows) {
                "Dynamic is nuvx's own background. Mica and Acrylic let Windows 11 tint the window with your desktop behind it, Acrylic with more blur."
            } else if cfg!(target_os = "macos") {
                "Dynamic is nuvx's own background. Window and Sidebar are macOS's own vibrancy, with your desktop blurred behind the window."
            } else {
                "Dynamic is nuvx's own background. Tinted and Translucent let your desktop show through the window; your compositor decides any blur."
            };
            setting_row(ui, "Window material", Some(desc), |ui| {
                let now = crate::material::active();
                for m in Material::ALL.into_iter().rev() {
                    if widgets::chip(ui, m.name(), now == m).clicked() && now != m {
                        crate::material::set(m);
                        if let Err(e) = crate::material::save(app.backend.data_dir(), m) {
                            log::warn!("material: couldn't save the setting: {e}");
                        }
                    }
                }
            });
        }
        if super::titlebar::custom() {
            divider(ui);
            setting_row(ui, "Window buttons", Some("Close, minimize and maximize: at the top right as on Windows, or as macOS's coloured buttons at the top left."), |ui| {
                let mac = super::titlebar::mac();
                if widgets::chip(ui, "macOS", mac).clicked() {
                    super::titlebar::set_mac(true);
                }
                if widgets::chip(ui, "Windows", !mac).clicked() {
                    super::titlebar::set_mac(false);
                }
            });
        }
        if app.wgpu_renderer != (crate::renderer::active() == crate::renderer::Renderer::Wgpu) {
            divider(ui);
            let mut restart = false;
            setting_row(ui, "Restart to apply", Some("The window changes from the next start."), |ui| {
                restart = pill(ui, "Restart now", None, true).clicked();
            });
            if restart {
                app.restart(ui.ctx());
            }
        }
    });
}

fn settings_shortcuts(ui: &mut Ui) {
    section_label(ui, "Keyboard shortcuts", None);
    settings_card(ui, |ui| {
        let keys: &[(&str, &[&str])] = &[
            ("Play / pause", &["Space"]),
            ("Next / previous song", &["Ctrl", "→ / ←"]),
            ("Volume up / down", &["Ctrl", "↑ / ↓"]),
            ("Search", &["Ctrl", "F"]),
            ("Lyrics", &["L"]),
            ("Queue", &["Q"]),
            ("Full-screen lyrics (in now playing)", &["F"]),
            ("Back / forward", &["Alt", "← / →"]),
            ("Close now playing", &["Esc"]),
        ];
        for (i, (what, combo)) in keys.iter().enumerate() {
            if i > 0 {
                divider(ui);
            }
            let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), 44.0), Sense::hover());
            ui.painter().text(pos2(rect.left(), rect.center().y), Align2::LEFT_CENTER, *what, theme::regular(14.0), theme::text());
            let mut x = rect.right();
            for (j, k) in combo.iter().enumerate().rev() {
                x = keycap(ui, pos2(x, rect.center().y), k) - 6.0;
                if j > 0 {
                    let plus = ui.painter().text(pos2(x, rect.center().y), Align2::RIGHT_CENTER, "+", theme::regular(13.0), theme::text_dim());
                    x = plus.left() - 6.0;
                }
            }
        }
    });
}

pub(super) fn dropdown_button(ui: &mut Ui, value: &str, width: f32, name: &str) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(vec2(width, 36.0), Sense::click());
    let resp = widgets::named(resp, name).on_hover_cursor(egui::CursorIcon::PointingHand);
    let open = egui::Popup::is_id_open(ui.ctx(), egui::Popup::default_response_id(&resp));
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered() || open, 0.12);
    ui.painter().rect_filled(rect, 10.0, theme::glass(0.08 + 0.05 * h));
    widgets::text_at(ui, pos2(rect.left() + 14.0, rect.center().y - 9.0), value, theme::regular(14.0), theme::text(), rect.width() - 44.0);
    let glyph = if open { theme::icon::CHEVRON_UP } else { theme::icon::CHEVRON_DOWN };
    widgets::icon_at(ui, pos2(rect.right() - 18.0, rect.center().y), glyph, 15.0, theme::lerp_color(theme::text_dim(), theme::text(), h));
    resp
}

pub(super) fn option_row(ui: &mut Ui, label: &str, selected: bool) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 34.0), Sense::click());
    let resp = widgets::named(resp, label).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
    if h > 0.0 || selected {
        ui.painter().rect_filled(rect, 8.0, theme::glass(0.05 * selected as u8 as f32 + 0.08 * h));
    }
    let font = if selected { theme::semibold(14.0) } else { theme::regular(14.0) };
    widgets::text_at(ui, pos2(rect.left() + 12.0, rect.center().y - 9.0), label, font, theme::text(), rect.width() - 44.0);
    if selected {
        widgets::icon_at(ui, pos2(rect.right() - 16.0, rect.center().y), theme::icon::CHECK, 15.0, theme::text());
    }
    resp
}

fn keycap(ui: &Ui, right: egui::Pos2, key: &str) -> f32 {
    let g = widgets::galley(ui, key, theme::semibold(12.5), theme::text(), 200.0);
    let size = vec2((g.size().x + 18.0).max(30.0), 26.0);
    let rect = Rect::from_min_size(pos2(right.x - size.x, right.y - size.y / 2.0), size);
    ui.painter().rect_filled(rect, 7.0, theme::glass(0.08));
    ui.painter().rect_stroke(rect, 7.0, egui::Stroke::new(1.0, theme::glass(0.12)), egui::StrokeKind::Inside);
    ui.painter().rect_filled(Rect::from_min_max(pos2(rect.left() + 2.0, rect.bottom() - 2.0), pos2(rect.right() - 2.0, rect.bottom())), 2.0, theme::shade(0.25));
    ui.painter().galley(rect.center() - g.size() / 2.0, g, theme::text());
    rect.left()
}
