use egui::{Color32, Rect, Sense, Ui, pos2, vec2};

use super::pages::{divider, section_label, setting_row, settings_card, toggle};
use super::settings_pages::{dropdown_button, option_row};
use super::widgets::{self, pill};
use super::App;
use crate::theme::{self, icon};
pub(super) fn settings_playback(app: &mut App, ui: &mut Ui) {
    section_label(ui, "Playback", None);
    settings_card(ui, |ui| {
        setting_row(ui, "Autoplay", Some("Keep playing similar songs when the queue ends."), |ui| {
            toggle(ui, &mut app.autoplay, "Autoplay");
        });
        divider(ui);
        setting_row(ui, "Loudness target", Some("Level songs are brought to: lower is quieter, -14 matches YouTube Music."), |ui| {
            let active = effective_norm(app.norm_target, &app.norm_by_output, app.output_device.as_ref().map(|(id, _)| id.as_str()));
            let label = active.map_or("Off".to_string(), |t| format!("{t} LUFS"));
            let btn = dropdown_button(ui, &label, 150.0, "Loudness target");
            let mut pick = None;
            egui::Popup::menu(&btn).show(|ui| {
                ui.set_width(150.0);
                ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
                ui.add_space(4.0);
                for t in std::iter::once(None).chain((-20..=-10).map(Some)) {
                    let name = t.map_or("Off".to_string(), |t| format!("{t} LUFS"));
                    if option_row(ui, &name, active == t).clicked() {
                        pick = Some(t);
                        ui.close();
                    }
                }
                ui.add_space(4.0);
            });
            if let Some(t) = pick {
                if let Some((id, _)) = &app.output_device {
                    app.norm_by_output.insert(id.clone(), t);
                } else {
                    app.norm_target = t;
                }
                app.audio.set_normalize(t);
            }
        });
        divider(ui);
        setting_row(ui, "Sleep timer", Some("Fades the music out and pauses after a set time."), |ui| {
            let now = ui.input(|i| i.time);
            let label = app.sleep.remaining(now).map_or("Off".to_string(), super::sleep_timer::label);
            let btn = dropdown_button(ui, &label, 150.0, "Sleep timer");
            let mut pick = None;
            egui::Popup::menu(&btn).show(|ui| {
                ui.set_width(150.0);
                ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
                ui.add_space(4.0);
                for m in std::iter::once(None).chain(super::sleep_timer::CHOICES_MIN.map(Some)) {
                    let name = m.map_or("Off".to_string(), |m| format!("{m} minutes"));
                    if option_row(ui, &name, false).clicked() {
                        pick = Some(m);
                        ui.close();
                    }
                }
                ui.add_space(4.0);
            });
            match pick {
                Some(Some(m)) => app.sleep.start(m, now),
                Some(None) => {
                    app.sleep.cancel();
                    app.audio.set_sleep_cut(0);
                }
                None => {}
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
            if toggle(ui, &mut app.load_both, "Load songs and videos together").changed() && app.load_both
                && let Some(track) = app.current().cloned()
            {
                app.load_other_version(&track);
            }
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
    });
    section_label(ui, "Sources", None);
    settings_card(ui, |ui| {
        setting_row(ui, "Use JioSaavn fallback", Some("Try a matching JioSaavn track after YouTube Music playback fails."), |ui| {
            if toggle(ui, &mut app.jiosaavn_enabled, "Use JioSaavn fallback").changed() {
                app.backend.set_music_sources(app.jiosaavn_enabled, app.radiojavan_enabled);
            }
        });
        divider(ui);
        setting_row(ui, "Use Radio Javan fallback", Some("Try Radio Javan after YouTube Music and JioSaavn fail."), |ui| {
            if toggle(ui, &mut app.radiojavan_enabled, "Use Radio Javan fallback").changed() {
                app.backend.set_music_sources(app.jiosaavn_enabled, app.radiojavan_enabled);
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

/// An explicit per-device Off override is distinct from no override.
pub(super) fn effective_norm(
    global: Option<i32>,
    outputs: &std::collections::HashMap<String, Option<i32>>,
    id: Option<&str>,
) -> Option<i32> {
    id.and_then(|key| outputs.get(key).copied()).unwrap_or(global)
}

#[cfg(test)]
mod normalization_output_tests {
    use super::effective_norm;
    use std::collections::HashMap;

    #[test]
    fn device_targets_are_isolated_and_unknown_outputs_keep_global_default() {
        let mut saved = HashMap::new();
        let global = Some(-14);
        saved.insert("headphones-id".to_owned(), Some(-17));
        saved.insert("speaker-id".to_owned(), None);
        assert_eq!(effective_norm(global, &saved, Some("headphones-id")), Some(-17));
        assert_eq!(effective_norm(global, &saved, Some("speaker-id")), None);
        assert_eq!(effective_norm(global, &saved, None), Some(-14));
        assert_eq!(effective_norm(global, &saved, Some("not-connected")), Some(-14));
        saved.insert("headphones-id".to_owned(), Some(-19));
        assert_eq!(effective_norm(global, &saved, Some("speaker-id")), None);
        let decoded: HashMap<String, Option<i32>> = serde_json::from_str(&serde_json::to_string(&saved).unwrap()).unwrap();
        assert_eq!(effective_norm(global, &decoded, Some("headphones-id")), Some(-19));
    }
}

fn output_key(device: &Option<(String, String)>) -> &str {
    device.as_ref().map_or("", |(id, _)| id.as_str())
}

#[cfg(test)]
mod stable_eq_output_tests {
    use super::output_key;
    use std::collections::HashMap;

    #[test]
    fn equalizer_profiles_are_isolated_by_stable_device_identity() {
        let left = Some(("headset-1".to_owned(), "USB Headset".to_owned()));
        let right = Some(("headset-2".to_owned(), "USB Headset".to_owned()));
        let flat = crate::eq::Prefs::default();
        let bass = crate::eq::Prefs { on: true, manual: true, bands: crate::eq::PRESETS[2].1, ..flat };
        let mut profiles = HashMap::new();
        assert_eq!(crate::eq::switch_output(&mut profiles, "", output_key(&left), bass), bass);
        assert_eq!(crate::eq::switch_output(&mut profiles, output_key(&left), output_key(&right), flat), flat);
        assert_eq!(crate::eq::switch_output(&mut profiles, output_key(&right), output_key(&left), flat), flat);
        assert_eq!(crate::eq::switch_output(&mut profiles, output_key(&left), "", bass), bass);
        assert_ne!(output_key(&left), output_key(&right));
        assert_eq!(output_key(&None), "");
    }
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
        // The saved EQ map used display names in older versions. Migrate only the
        // selected output's legacy profile, then use stable IDs (two devices may share a name).
        let from = output_key(&app.output_device).to_owned();
        let to = output_key(&pick).to_owned();
        if let Some((id, name)) = &pick
            && !app.eq_by_output.contains_key(id)
            && let Some(legacy) = app.eq_by_output.get(name).copied()
        {
            app.eq_by_output.insert(id.clone(), legacy);
        }
        app.eq = crate::eq::switch_output(&mut app.eq_by_output, &from, &to, app.eq);
        app.audio.set_eq(app.eq.tuning());
        app.output_device = pick;
        app.audio.set_normalize(effective_norm(app.norm_target, &app.norm_by_output, app.output_device.as_ref().map(|(id, _)| id.as_str())));
    }
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
