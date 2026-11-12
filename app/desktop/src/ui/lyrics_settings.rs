use egui::{Sense, Ui, vec2};

use super::pages::{divider, section_label, setting_row, settings_card, toggle};
use super::settings_pages::{dropdown_button, option_row};
use super::App;
use crate::theme;
pub(super) fn settings_lyrics(app: &mut App, ui: &mut Ui) {
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
