use egui::{Align2, Color32, Rect, RichText, Sense, Ui, pos2, vec2};

use super::pages::{section_label, setting_row, settings_card, toggle};
use super::settings_pages::{dropdown_button, option_row};
use super::widgets;
use super::App;
use crate::theme;
pub(super) fn settings_equalizer(app: &mut App, ui: &mut Ui) {
    let before = app.eq;
    section_label(ui, "Equalizer", None);
    settings_card(ui, |ui| {
        setting_row(ui, "Equalizer", Some("Shapes the sound inside nuvx, before it reaches the system."), |ui| {
            toggle(ui, &mut app.eq.on, "Equalizer");
        });
    });
    let eq = &mut app.eq;
    ui.add_enabled_ui(eq.on, |ui| {
        ui.add_space(18.0);
        ui.horizontal(|ui| {
            for (manual, label) in [(false, "Dynamic"), (true, "Manual")] {
                if widgets::chip(ui, label, eq.manual == manual).clicked() {
                    eq.manual = manual;
                }
            }
        });
        if !eq.manual {
            section_label(ui, "Tone", None);
            tone_pad(ui, &mut eq.tilt, &mut eq.contour);
            let sign = |v: i32| if v > 0 { format!("+{v}") } else { v.to_string() };
            let readout = format!("Tilt {}      Contour {}", sign(eq.tilt), sign(eq.contour));
            let (rect, _) = ui.allocate_exact_size(vec2(pad_width(ui), 28.0), Sense::hover());
            ui.painter().text(rect.center(), Align2::CENTER_CENTER, readout, theme::semibold(13.5), theme::text());
            for (focused, label) in [(false, "Broad"), (true, "Focused")] {
                if option_row(ui, label, eq.focused == focused).clicked() {
                    eq.focused = focused;
                }
            }
            eq_footer(
                ui,
                if eq.focused {
                    "Drag left for a warmer sound and right for a brighter one; down scoops the middle, up brings it forward. Focused works on narrower stretches, so it changes less around what it moves."
                } else {
                    "Drag left for a warmer sound and right for a brighter one; down scoops the middle, up brings it forward. Broad moves wide stretches of the spectrum at once."
                },
            );
        } else {
            section_label(ui, "Preset", None);
            let current = crate::eq::preset_of(&eq.bands);
            let btn = dropdown_button(ui, current.map_or("Custom", |i| crate::eq::PRESETS[i].0), 220.0, "Preset");
            egui::Popup::menu(&btn).show(|ui| {
                ui.set_width(240.0);
                ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
                egui::ScrollArea::vertical().max_height(340.0).show(ui, |ui| {
                    ui.add_space(4.0);
                    for (i, (name, bands)) in crate::eq::PRESETS.iter().enumerate() {
                        if option_row(ui, name, current == Some(i)).clicked() {
                            eq.bands = *bands;
                            ui.close();
                        }
                    }
                    ui.add_space(4.0);
                });
            });
            section_label(ui, "Bands", None);
            band_faders(ui, &mut eq.bands);
            let (rect, resp) = ui.allocate_exact_size(vec2(130.0, 30.0), Sense::click());
            let resp = widgets::named(resp, "Reset all bands").on_hover_cursor(egui::CursorIcon::PointingHand);
            ui.painter().text(rect.left_center(), Align2::LEFT_CENTER, "Reset all bands", theme::semibold(13.5), theme::RED);
            if resp.clicked() {
                eq.bands = [0.0; 7];
            }
            eq_footer(ui, "Moving a band leaves the preset behind and the curve becomes your own.");
        }
        section_label(ui, "Balance", None);
        balance_slider(ui, &mut eq.balance);
        eq_footer(ui, "Shifts the sound towards one ear by quietening the other.");
    });
    if app.eq != before {
        app.audio.set_eq(app.eq.tuning());
    }
}

fn eq_footer(ui: &mut Ui, text: &str) {
    ui.add_space(4.0);
    ui.add(egui::Label::new(RichText::new(text).font(theme::regular(11.5)).color(theme::text_dim())).wrap());
}

fn pad_width(ui: &Ui) -> f32 {
    ui.available_width().min(420.0)
}

fn eq_accent(ui: &Ui) -> Color32 {
    ui.ctx().data(|d| d.get_temp::<Color32>(egui::Id::unique("accent"))).unwrap_or(theme::RED)
}

fn eq_knob(ui: &Ui, c: egui::Pos2, r: f32) {
    ui.painter().circle_filled(c + vec2(0.0, 1.5), r + 1.5, theme::shade(0.18));
    ui.painter().circle_filled(c, r, Color32::WHITE);
}

fn tone_pad(ui: &mut Ui, x: &mut i32, y: &mut i32) {
    const PUCK: f32 = 19.0;
    let steps = crate::eq::TONE_STEPS;
    let (rect, resp) = ui.allocate_exact_size(vec2(pad_width(ui), 230.0), Sense::click_and_drag());
    let resp = widgets::named(resp, "Tone").on_hover_cursor(egui::CursorIcon::Crosshair);
    let usable = rect.shrink(PUCK);
    if let Some(p) = resp.interact_pointer_pos()
        && (resp.dragged() || resp.clicked() || resp.is_pointer_button_down_on())
    {
        let fx = ((p.x - usable.left()) / usable.width()).clamp(0.0, 1.0);
        let fy = ((p.y - usable.top()) / usable.height()).clamp(0.0, 1.0);
        *x = (fx * (steps * 2) as f32).round() as i32 - steps;
        *y = steps - (fy * (steps * 2) as f32).round() as i32;
    }
    let cols = (steps * 2) as f32;
    let at = |cx: f32, cy: f32| pos2(usable.left() + usable.width() * cx / cols, usable.top() + usable.height() * cy / cols);
    for c in 0..=steps * 2 {
        for r in 0..=steps * 2 {
            let axis = c == steps || r == steps;
            ui.painter().circle_filled(at(c as f32, r as f32), 2.5, theme::with_alpha(theme::text_dim(), if axis { 0.55 } else { 0.3 }));
        }
    }
    let ax = ui.ctx().animate_value_with_time(resp.id.with("x"), *x as f32, 0.12);
    let ay = ui.ctx().animate_value_with_time(resp.id.with("y"), *y as f32, 0.12);
    let puck = at(ax + steps as f32, steps as f32 - ay);
    for ring in [3.0f32, 2.0, 1.0] {
        ui.painter().circle_filled(puck + vec2(0.0, ring), PUCK + ring * 2.0, theme::shade(0.16 / ring));
    }
    ui.painter().circle_filled(puck, PUCK, Color32::WHITE);
}

fn band_faders(ui: &mut Ui, bands: &mut [f32; 7]) {
    const KNOB: f32 = 10.0;
    let range = crate::eq::BAND_RANGE_DB;
    let (area, _) = ui.allocate_exact_size(vec2(pad_width(ui), 200.0), Sense::hover());
    let col_w = area.width() / 7.0;
    let accent = eq_accent(ui);
    for (i, hz) in crate::eq::BANDS_HZ.iter().enumerate() {
        let col = Rect::from_min_size(pos2(area.left() + col_w * i as f32, area.top()), vec2(col_w, area.height()));
        let label = if *hz < 1000.0 {
            format!("{hz}")
        } else if hz % 1000.0 == 0.0 {
            format!("{}k", hz / 1000.0)
        } else {
            format!("{:.1}k", hz / 1000.0)
        };
        let fader = Rect::from_center_size(col.center(), vec2(34.0, 150.0));
        let resp = ui.interact(fader, ui.scope_id().with(("band", i)), Sense::click_and_drag());
        let resp = widgets::named(resp, &format!("{label} Hz")).on_hover_cursor(egui::CursorIcon::PointingHand);
        let usable = fader.shrink2(vec2(0.0, KNOB));
        if let Some(p) = resp.interact_pointer_pos()
            && (resp.dragged() || resp.clicked() || resp.is_pointer_button_down_on())
        {
            let f = ((p.y - usable.top()) / usable.height()).clamp(0.0, 1.0);
            let raw = (1.0 - f * 2.0) * range;
            bands[i] = if raw.abs() < 0.6 { 0.0 } else { ((raw * 2.0).round() / 2.0).clamp(-range, range) };
        }
        let v = bands[i];
        let flat = v.abs() < 0.05;
        let gain = if flat {
            "0".to_owned()
        } else if v.fract().abs() < 0.05 {
            format!("{:+}", v.round() as i32)
        } else {
            format!("{v:+.1}")
        };
        ui.painter().text(pos2(col.center().x, fader.top() - 12.0), Align2::CENTER_CENTER, gain, theme::semibold(11.5), if flat { theme::text_dim() } else { accent });
        ui.painter().text(pos2(col.center().x, fader.bottom() + 12.0), Align2::CENTER_CENTER, label, theme::regular(11.5), theme::text_dim());
        let track = Rect::from_center_size(usable.center(), vec2(5.0, usable.height()));
        let knob_y = usable.top() + usable.height() * (1.0 - (v / range + 1.0) / 2.0);
        ui.painter().rect_filled(track, 2.5, theme::glass(0.18));
        if !flat {
            let (a, b) = (track.center().y.min(knob_y), track.center().y.max(knob_y));
            ui.painter().rect_filled(Rect::from_x_y_ranges(track.x_range(), a..=b), 2.5, accent);
        }
        ui.painter().line_segment([pos2(track.left() - 2.5, track.center().y), pos2(track.right() + 2.5, track.center().y)], egui::Stroke::new(1.0, theme::glass(0.4)));
        eq_knob(ui, pos2(track.center().x, knob_y), KNOB);
    }
}

fn balance_slider(ui: &mut Ui, balance: &mut f32) {
    const KNOB: f32 = 10.0;
    let w = pad_width(ui);
    let (head, _) = ui.allocate_exact_size(vec2(w, 22.0), Sense::hover());
    ui.painter().text(head.left_center(), Align2::LEFT_CENTER, "L", theme::regular(14.0), theme::text());
    ui.painter().text(head.center(), Align2::CENTER_CENTER, format!("{balance:.2}"), theme::regular(14.0), theme::text_dim());
    ui.painter().text(head.right_center(), Align2::RIGHT_CENTER, "R", theme::regular(14.0), theme::text());
    let (rect, resp) = ui.allocate_exact_size(vec2(w, KNOB * 2.0 + 8.0), Sense::click_and_drag());
    let resp = widgets::named(resp, "Balance").on_hover_cursor(egui::CursorIcon::PointingHand);
    let usable = rect.shrink2(vec2(KNOB, 0.0));
    if let Some(p) = resp.interact_pointer_pos()
        && (resp.dragged() || resp.clicked() || resp.is_pointer_button_down_on())
    {
        let raw = ((p.x - usable.left()) / usable.width()).clamp(0.0, 1.0) * 2.0 - 1.0;
        *balance = if raw.abs() < 0.04 { 0.0 } else { raw };
    }
    let track = Rect::from_center_size(usable.center(), vec2(usable.width(), 5.0));
    let knob_x = usable.left() + usable.width() * (*balance + 1.0) / 2.0;
    ui.painter().rect_filled(track, 2.5, theme::glass(0.18));
    if balance.abs() > 0.004 {
        let (a, b) = (track.center().x.min(knob_x), track.center().x.max(knob_x));
        ui.painter().rect_filled(Rect::from_x_y_ranges(a..=b, track.y_range()), 2.5, eq_accent(ui));
    }
    eq_knob(ui, pos2(knob_x, track.center().y), KNOB);
}
