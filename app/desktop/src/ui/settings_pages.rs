use std::sync::atomic::Ordering;

use egui::{Align2, Color32, Rect, RichText, Sense, Ui, Vec2, pos2, vec2};

use super::glyphs;
use super::pages::{divider, glyph_row, page_title, section_label, setting_row, settings_card, thousands, toggle};
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
        Account => settings_account(app, ui),
        Playback => settings_playback(app, ui),
        Equalizer => super::equalizer_settings::settings_equalizer(app, ui),
        Downloads => settings_downloads(ui),
        Lyrics => settings_lyrics(app, ui),
        Artists => settings_artists(app, ui),
        Appearance => {
            settings_appearance(app, ui);
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
            settings_card(ui, |ui| network(app, ui));
        }
        Import => super::transfer::settings_section(app, ui),
        Shortcuts => settings_shortcuts(ui),
    }
}

fn settings_account(app: &mut App, ui: &mut Ui) {
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

fn settings_playback(app: &mut App, ui: &mut Ui) {
    section_label(ui, "Playback", None);
    settings_card(ui, |ui| {
        setting_row(ui, "Autoplay", Some("Keep playing similar songs when the queue ends."), |ui| {
            toggle(ui, &mut app.autoplay, "Autoplay");
        });
        divider(ui);
        setting_row(ui, "Volume normalization", Some("Turn loud songs down to the same level, like YouTube Music does."), |ui| {
            if toggle(ui, &mut app.normalize, "Volume normalization").changed() {
                app.audio.set_normalize(app.normalize);
            }
        });
        divider(ui);
        setting_row(ui, "Spatial audio", Some("Widens stereo songs for a more immersive feel."), |ui| {
            if toggle(ui, &mut app.spatial, "Spatial audio").changed() {
                app.audio.set_spatial(app.spatial);
            }
        });
        divider(ui);
        setting_row(ui, "Logarithmic volume", Some("Space the volume slider out the way ears hear loudness, so quiet levels aren't squeezed into its first few pixels."), |ui| {
            if toggle(ui, &mut app.log_volume, "Logarithmic volume").changed() {
                app.audio.set_log_volume(app.log_volume);
            }
        });
        divider(ui);
        setting_row(ui, "Play songs, not music videos", Some("An official music video plays as its song, the album recording, when YouTube Music has one: no intros, skits or video-only edits. Lists still show what they hold."), |ui| {
            toggle(ui, &mut app.play_songs, "Play songs, not music videos");
        });
        divider(ui);
        setting_row(ui, "Load songs and videos together", Some("As a song starts, its music video loads too (and a video's song), so switching between them is instant. Uses the data of both."), |ui| {
            toggle(ui, &mut app.load_both, "Load songs and videos together");
        });
        if crate::motion::SUPPORTED {
            divider(ui);
            setting_row(ui, "Music video quality", Some("How sharp a music video's picture is in Now playing. Higher uses more data."), |ui| {
                let btn = dropdown_button(ui, &format!("{}p", app.video_height), 210.0, "Music video quality");
                let mut pick = None;
                egui::Popup::menu(&btn).show(|ui| {
                    ui.set_width(210.0);
                    ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
                    ui.add_space(4.0);
                    for q in [1080, 720, 480, 360] {
                        if option_row(ui, &format!("{q}p"), app.video_height == q).clicked() {
                            pick = Some(q);
                            ui.close();
                        }
                    }
                    ui.add_space(4.0);
                });
                if let Some(q) = pick {
                    app.set_video_height(q);
                }
            });
        }
        divider(ui);
        setting_row(ui, "Output device", Some("Where the music plays. A picked device that isn't connected leaves it to Windows' default until it's back."), |ui| {
            output_device_picker(app, ui);
        });
        divider(ui);
        setting_row(ui, "Automix", Some("Slide from one song into the next the way a DJ would: both hold their level for a moment, then swap in one smooth move, instead of a straight fade. The mix picks its own length, in phrases of 8 or 16 bars (16 on long songs), following the beat it hears."), |ui| {
            if toggle(ui, &mut app.automix, "Automix").changed() {
                app.set_crossfade();
            }
        });
        if !app.automix {
            divider(ui);
            setting_row(ui, "Crossfade songs", Some("Blend the end of each song into the start of the next."), |ui| {
                if toggle(ui, &mut app.crossfade, "Crossfade songs").changed() {
                    app.set_crossfade();
                }
            });
            if app.crossfade {
                divider(ui);
                setting_row(ui, "Crossfade length", None, |ui| {
                    if seconds_slider(ui, &mut app.crossfade_secs, 1..=super::CROSSFADE_MAX, "Crossfade length").changed() {
                        app.set_crossfade();
                    }
                });
            }
        }
        divider(ui);
        setting_row(ui, "Skip non-music parts", Some("Jump over talking, skits and sponsor breaks in music videos, as marked by the SponsorBlock community. Album songs don't have any."), |ui| {
            if toggle(ui, &mut app.sponsorblock, "Skip non-music parts").changed() {
                app.request_segments();
            }
        });
        divider(ui);
        setting_row(ui, "Hi-Res audio", Some("Addon servers that serve hi-res copies of songs. When one has the song, its copy plays instead of YouTube's, and its quality shows in the player."), |_| {});
        divider(ui);
        ui.add_space(12.0);
        hires_sources_editor(app, ui);
        ui.add_space(10.0);
        divider(ui);
        setting_row(ui, "Prefer Dolby Atmos", Some("Ask the hi-res sources for a Dolby Atmos copy when they offer one. Falls back to the best stereo copy when the stream's codec can't be decoded."), |ui| {
            if toggle(ui, &mut app.prefer_atmos, "Prefer Dolby Atmos").changed() {
                app.backend.set_prefer_atmos(app.prefer_atmos);
            }
        });
    });
}

fn hires_sources_editor(app: &mut App, ui: &mut Ui) {
    let mut edited = false;
    let mut remove = None;
    for i in 0..app.hires_sources.len() {
        ui.horizontal(|ui| {
            let field_w = (ui.available_width() - 34.0).max(120.0);
            ui.allocate_ui_with_layout(vec2(field_w, 42.0), egui::Layout::top_down(egui::Align::Min), |ui| {
                ui.set_width(field_w);
                if super::text_field(ui, "", &mut app.hires_sources[i], false, "https://your-addon.example").changed() {
                    edited = true;
                }
            });
            if widgets::icon_button(ui, icon::X, 14.0, "Remove source", false).clicked() {
                remove = Some(i);
            }
        });
        ui.add_space(6.0);
    }
    if let Some(i) = remove {
        app.hires_sources.remove(i);
        edited = true;
    }
    if edited {
        app.backend.set_hires_source(&live_sources(app));
    }
    if app.hires_adding {
        let resp = super::text_field(ui, "", &mut app.hires_draft, false, "https://your-addon.example");
        if app.hires_focus {
            resp.request_focus();
            app.hires_focus = false;
        }
        if resp.lost_focus() {
            let link = app.hires_draft.trim().to_owned();
            if !link.is_empty() && !app.hires_sources.iter().any(|s| s.trim() == link) {
                app.hires_sources.push(link);
                app.backend.set_hires_source(&live_sources(app));
            }
            app.hires_draft.clear();
            app.hires_adding = false;
        }
        ui.add_space(6.0);
    } else if pill(ui, "+  Add a source", None, false).clicked() {
        app.hires_adding = true;
        app.hires_focus = true;
        app.hires_draft.clear();
    }
}

fn live_sources(app: &App) -> String {
    app.hires_sources.iter().map(|s| s.trim()).filter(|s| !s.is_empty()).collect::<Vec<_>>().join("\n")
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

fn settings_appearance(app: &mut App, ui: &mut Ui) {
    section_label(ui, "Appearance", None);
    settings_card(ui, |ui| {
        let desc = if cfg!(windows) {
            "Light or dark pages. System follows Windows' app mode. Now playing stays dark, over the album art."
        } else {
            "Light or dark pages. System follows your system's setting. Now playing stays dark, over the album art."
        };
        let mut pick = None;
        setting_row(ui, "Mode", Some(desc), |ui| {
            let btn = dropdown_button(ui, app.appearance.label(), 160.0, "Mode");
            egui::Popup::menu(&btn).show(|ui| {
                ui.set_width(160.0);
                ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
                ui.add_space(4.0);
                for a in theme::Appearance::ALL {
                    if option_row(ui, a.label(), a == app.appearance).clicked() {
                        pick = Some(a);
                        ui.close();
                    }
                }
                ui.add_space(4.0);
            });
        });
        if let Some(a) = pick {
            app.appearance = a;
        }
        divider(ui);
        setting_row(ui, "Animated background", Some("The album art slowly drifts behind everything."), |ui| {
            toggle(ui, &mut app.anim_bg, "Animated background");
        });
        if crate::motion::SUPPORTED {
            divider(ui);
            setting_row(ui, "Animated artwork", Some("Covers move where Apple Music or Tidal has a moving version: in Now playing, the player and on album pages."), |ui| {
                toggle(ui, &mut app.anim_canvas, "Animated artwork");
            });
        }
        divider(ui);
        setting_row(ui, "Reduce animation", Some("Panels, menus and pages change at once instead of sliding and fading."), |ui| {
            if toggle(ui, &mut app.reduce_motion, "Reduce animation").changed() {
                theme::set_reduce_motion(app.reduce_motion);
            }
        });
        divider(ui);
        setting_row(ui, "Pulse with the music", Some("The background swells with the beat."), |ui| {
            toggle(ui, &mut app.reactive_bg, "Pulse with the music");
        });
        divider(ui);
        setting_row(ui, "Font", Some("For the whole app and the lyrics. Any font installed on this PC works, e.g. one made for your language."), |ui| {
            if let Some(name) = font_picker(ui) {
                theme::set_font_choice(&name);
                app.chrome_applied = None;
            }
        });
        divider(ui);
        let hidden = app.hidden_playlists.len();
        let desc = match hidden {
            0 => "Nothing hidden. Right-click a playlist in the sidebar to hide it from there.".to_owned(),
            1 => "1 playlist is hidden from the sidebar. Right-click a playlist there to hide or show it.".to_owned(),
            n => format!("{} playlists are hidden from the sidebar. Right-click a playlist there to hide or show it.", thousands(n as u64)),
        };
        setting_row(ui, "Hidden playlists", Some(&desc), |ui| {
            if hidden > 0 && pill(ui, "Show all", None, false).clicked() {
                app.hidden_playlists.clear();
            }
        });
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

fn network(app: &mut App, ui: &mut Ui) {
    use crate::proxy::{self, Setting};
    let same = |a: &Setting, b: &Setting| std::mem::discriminant(a) == std::mem::discriminant(b);
    let mut pick = None;
    setting_row(ui, "Proxy", Some("Send everything nuvx fetches through a proxy: songs, lyrics, sign-in and Listen together."), |ui| {
        let btn = dropdown_button(ui, app.proxy_mode.label(), 220.0, "Proxy");
        egui::Popup::menu(&btn).show(|ui| {
            ui.set_width(220.0);
            ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
            ui.add_space(4.0);
            for s in [Setting::System, Setting::Off, Setting::Custom(String::new())] {
                if option_row(ui, s.label(), same(&s, &app.proxy_mode)).clicked() {
                    pick = Some(s);
                    ui.close();
                }
            }
            ui.add_space(4.0);
        });
    });
    if let Some(s) = pick {
        app.proxy_error = None;
        *app.proxy_test.lock().unwrap() = None;
        app.proxy_mode = s.clone();
        if !matches!(s, Setting::Custom(_)) {
            match proxy::save(app.backend.data_dir(), &s) {
                Ok(()) => app.proxy_saved = s,
                Err(e) => app.proxy_error = Some(format!("{e:#}")),
            }
        }
    }
    match &app.proxy_mode {
        Setting::System => {
            divider(ui);
            let desc = match proxy::system() {
                Some(p) => format!("Windows is set to use {}.", proxy::redact(&p)),
                None => "Windows isn't set to use a proxy, so nuvx connects directly.".to_owned(),
            };
            setting_row(ui, "Windows proxy", Some(&desc), |_| {});
        }
        Setting::Custom(_) => {
            divider(ui);
            ui.add_space(12.0);
            super::text_field(ui, "Address", &mut app.proxy_url, false, "http://127.0.0.1:8080 or socks5://user:pass@host:1080");
            ui.add_space(10.0);
            let custom = Setting::Custom(app.proxy_url.trim().to_owned());
            let saved = app.proxy_saved == custom;
            let mut save = false;
            ui.horizontal(|ui| {
                save = pill(ui, if saved { "Saved" } else { "Save" }, None, !saved).clicked() && !saved;
                ui.add_space(8.0);
                ui.label(RichText::new("A password in the address is kept in Windows Credential Manager.").font(theme::regular(12.5)).color(theme::text_dim()));
            });
            if save {
                match proxy::save(app.backend.data_dir(), &custom) {
                    Ok(()) => {
                        app.proxy_saved = custom;
                        app.proxy_error = None;
                    }
                    Err(e) => app.proxy_error = Some(format!("{e:#}")),
                }
            }
            ui.add_space(12.0);
        }
        Setting::Off => {}
    }
    if let Some(e) = &app.proxy_error {
        ui.label(RichText::new(e).font(theme::regular(12.5)).color(theme::RED));
        ui.add_space(8.0);
    }
    divider(ui);
    let result = app.proxy_test.lock().unwrap().clone();
    let desc = match &result {
        Some(Some((_, msg))) => msg.clone(),
        Some(None) => "Checking…".to_owned(),
        None => "Checks that YouTube Music answers through this setting.".to_owned(),
    };
    setting_row(ui, "Test connection", Some(&desc), |ui| {
        if matches!(result, Some(None)) {
            ui.spinner();
        } else if pill(ui, "Test", None, false).clicked() {
            let s = match &app.proxy_mode {
                Setting::Custom(_) => Setting::Custom(app.proxy_url.trim().to_owned()),
                other => other.clone(),
            };
            let (slot, b) = (app.proxy_test.clone(), app.backend.clone());
            *slot.lock().unwrap() = Some(None);
            app.backend.spawn(async move {
                let out = match proxy::test(&s).await {
                    Ok(t) => (true, format!("Working: YouTube Music answered in {} ms.", t.as_millis())),
                    Err(e) => (false, format!("Didn't connect: {e:#}")),
                };
                *slot.lock().unwrap() = Some(Some(out));
                b.wake();
            });
        }
    });
    if app.proxy_saved != *proxy::active() {
        divider(ui);
        let mut restart = false;
        setting_row(ui, "Restart to apply", Some("The new proxy setting is used from the next start."), |ui| {
            restart = pill(ui, "Restart now", None, true).clicked();
        });
        if restart {
            app.restart(ui.ctx());
        }
    }
}

fn font_picker(ui: &mut Ui) -> Option<String> {
    let current = theme::font_choice();
    let btn = dropdown_button(ui, if current.is_empty() { "Theme's font" } else { &current }, 220.0, "Font");
    let just_opened = btn.clicked();
    let mut picked = None;
    egui::Popup::menu(&btn).show(|ui| {
        ui.set_width(220.0);
        ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
        egui::ScrollArea::vertical().max_height(360.0).show(ui, |ui| {
            ui.add_space(4.0);
            let mut row = |ui: &mut Ui, label: &str, value: &str| {
                let on = current.eq_ignore_ascii_case(value);
                let r = option_row(ui, label, on);
                if on && just_opened {
                    r.scroll_to_me(Some(egui::Align::Center));
                }
                if r.clicked() {
                    picked = Some(value.to_owned());
                    ui.close();
                }
            };
            row(ui, "Theme's font", "");
            for name in theme::BUNDLED_FONTS {
                row(ui, name, name);
            }
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                ui.add_space(12.0);
                ui.label(RichText::new("Installed on this PC").font(theme::semibold(12.0)).color(theme::text_faint()));
            });
            ui.add_space(4.0);
            let ctx = ui.ctx().clone();
            match theme::installed_fonts(move || ctx.request_repaint()) {
                Some(fonts) => {
                    for f in fonts {
                        row(ui, &f.name, &f.name);
                    }
                }
                None => {
                    ui.horizontal(|ui| {
                        ui.add_space(12.0);
                        ui.label(RichText::new("Looking for fonts…").font(theme::regular(13.0)).color(theme::text_dim()));
                    });
                }
            }
            ui.add_space(4.0);
        });
    });
    picked.filter(|p| *p != current)
}

const SYSTEM_DEFAULT: &str = "Windows default";

fn output_device_picker(app: &mut App, ui: &mut Ui) {
    let current = match &app.output_device {
        None => SYSTEM_DEFAULT.to_owned(),
        Some((id, name)) => match app.output_devices.iter().find(|d| d.id == *id) {
            Some(d) => d.name.clone(),
            None if app.output_devices.is_empty() => name.clone(),
            None => format!("{name} (not connected)"),
        },
    };
    let btn = dropdown_button(ui, &current, 210.0, "Output device");
    if btn.clicked() || app.output_devices.is_empty() {
        app.output_devices = crate::audio::output_devices();
    }
    let mut pick: Option<Option<(String, String)>> = None;
    egui::Popup::menu(&btn).show(|ui| {
        ui.set_width(340.0);
        ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
        egui::ScrollArea::vertical().max_height(320.0).show(ui, |ui| {
            ui.add_space(4.0);
            if option_row(ui, SYSTEM_DEFAULT, app.output_device.is_none()).clicked() {
                pick = Some(None);
                ui.close();
            }
            for d in &app.output_devices {
                if option_row(ui, &d.name, app.output_device.as_ref().is_some_and(|(id, _)| *id == d.id)).clicked() {
                    pick = Some(Some((d.id.clone(), d.name.clone())));
                    ui.close();
                }
            }
            ui.add_space(4.0);
        });
    });
    if let Some(pick) = pick {
        app.audio.send(crate::audio::AudioCmd::SetDevice(pick.as_ref().map(|(id, _)| id.clone())));
        app.output_device = pick;
    }
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

fn seconds_slider(ui: &mut Ui, v: &mut u32, range: std::ops::RangeInclusive<u32>, name: &str) -> egui::Response {
    let (lo, hi) = (*range.start(), *range.end());
    let label = |ui: &mut Ui, text: String, w: f32, font: egui::FontId, color: Color32| {
        let (rect, _) = ui.allocate_exact_size(vec2(w, 26.0), Sense::hover());
        let g = widgets::galley(ui, &text, font, color, w);
        ui.painter().galley(pos2(rect.right() - g.size().x, rect.center().y - g.size().y / 2.0), g, color);
    };
    label(ui, format!("{v} s"), 34.0, theme::semibold(13.0), theme::text());
    ui.add_space(6.0);
    label(ui, format!("{hi} s"), 26.0, theme::regular(12.0), theme::text_dim());
    let (rect, mut resp) = ui.allocate_exact_size(vec2(170.0, 26.0), Sense::click_and_drag());
    label(ui, format!("{lo} s"), 22.0, theme::regular(12.0), theme::text_dim());
    let track = Rect::from_center_size(rect.center(), vec2(rect.width() - 16.0, 4.0));
    let old = *v;
    if let Some(p) = resp.interact_pointer_pos()
        && (resp.dragged() || resp.clicked())
    {
        let t = ((p.x - track.left()) / track.width()).clamp(0.0, 1.0);
        *v = (lo as f32 + t * (hi - lo) as f32).round() as u32;
    }
    if *v != old {
        resp.mark_changed();
    }
    let value = *v;
    resp.widget_info(|| egui::WidgetInfo::slider(ui.is_enabled(), value as f64, name));
    let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered() || resp.dragged(), 0.12);
    let accent = ui.ctx().data(|d| d.get_temp::<Color32>(egui::Id::unique("accent"))).unwrap_or(theme::RED);
    let t = (value - lo) as f32 / (hi - lo) as f32;
    let knob = pos2(egui::lerp(track.left()..=track.right(), t), track.center().y);
    ui.painter().rect_filled(track, 2.0, theme::glass(0.15));
    ui.painter().rect_filled(Rect::from_min_max(track.min, pos2(knob.x, track.max.y)), 2.0, accent);
    ui.painter().circle_filled(knob + vec2(0.0, 1.0), 7.0 + h, theme::shade(0.25));
    ui.painter().circle_filled(knob, 7.0 + h, Color32::WHITE);
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
