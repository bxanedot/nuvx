use super::*;

pub fn icon_at(ui: &Ui, center: Pos2, glyph: char, size: f32, color: Color32) {
    let g = ui.painter().layout_no_wrap(glyph.to_string(), theme::icon_font(size), color);
    let ink = g.mesh_bounds;
    let pos = if ink.is_positive() { center - ink.center().to_vec2() } else { center - g.size() / 2.0 };
    ui.painter().galley(pos, g, color);
}

pub fn heart(ui: &Ui, center: Pos2, size: f32, fill: f32, outline: Color32, fill_color: Color32) {
    use std::f32::consts::TAU;
    static UNIT: std::sync::OnceLock<Vec<Vec2>> = std::sync::OnceLock::new();
    let unit = UNIT.get_or_init(|| {
        (0..64)
            .map(|i| {
                let t = i as f32 / 64.0 * TAU;
                let x = 16.0 * t.sin().powi(3);
                let y = 13.0 * t.cos() - 5.0 * (2.0 * t).cos() - 2.0 * (3.0 * t).cos() - (4.0 * t).cos();
                vec2(x, -y - 2.5)
            })
            .collect()
    });
    let s = size / 34.0;
    let pts: Vec<Pos2> = unit.iter().map(|v| center + *v * s).collect();
    if fill > 0.0 {
        let mut mesh = egui::Mesh::default();
        let c = theme::with_alpha(fill_color, fill * fill_color.a() as f32 / 255.0);
        mesh.colored_vertex(center + vec2(0.0, 1.0 * s), c);
        for p in &pts {
            mesh.colored_vertex(*p, c);
        }
        let n = pts.len() as u32;
        for i in 0..n {
            mesh.add_triangle(0, 1 + i, 1 + (i + 1) % n);
        }
        ui.painter().add(egui::Shape::mesh(mesh));
    }
    let w = 1.7;
    for (i, p) in pts.iter().enumerate() {
        let q = pts[(i + 1) % pts.len()];
        ui.painter().line_segment([*p, q], egui::Stroke::new(w, outline));
        ui.painter().circle_filled(*p, w / 2.0, outline);
    }
}

pub fn icon_button(ui: &mut Ui, glyph: char, size: f32, name: &str, active: bool) -> Response {
    let d = size + 16.0;
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(d), Sense::click());
    let resp = named(resp, name).on_hover_text(name).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
    if h > 0.0 {
        ui.painter().circle_filled(rect.center(), d / 2.0, theme::glass(0.10 * h));
    }
    let color = if active { theme::text() } else { theme::lerp_color(theme::text_dim(), theme::text(), h) };
    let press = if resp.is_pointer_button_down_on() { 0.9 } else { 1.0 };
    icon_at(ui, rect.center(), glyph, size * press, color);
    resp
}

pub fn pill(ui: &mut Ui, label: &str, glyph: Option<char>, primary: bool) -> Response {
    let text = ui.painter().layout_no_wrap(label.to_owned(), theme::semibold(14.0), Color32::PLACEHOLDER);
    let icon_w = if glyph.is_some() { 24.0 } else { 0.0 };
    let (rect, resp) = ui.allocate_exact_size(vec2(text.size().x + icon_w + 36.0, 38.0), Sense::click());
    let resp = named(resp, label).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
    let (fill, fg) = if primary {
        if theme::light() { (theme::lerp_color(theme::text(), Color32::BLACK, h), Color32::WHITE) } else { (theme::lerp_color(Color32::from_gray(240), Color32::WHITE, h), Color32::from_gray(12)) }
    } else {
        (theme::glass(0.10 + 0.08 * h), theme::text())
    };
    let r = rect.shrink(if resp.is_pointer_button_down_on() { 1.0 } else { 0.0 });
    ui.painter().rect_filled(r, 19.0, fill);
    let mut x = r.left() + 18.0;
    if let Some(g) = glyph {
        icon_at(ui, pos2(x + 8.0, r.center().y), g, 17.0, fg);
        x += icon_w;
    }
    ui.painter().galley(pos2(x, r.center().y - text.size().y / 2.0), text, fg);
    resp
}

pub fn chip(ui: &mut Ui, label: &str, selected: bool) -> Response {
    let text = ui.painter().layout_no_wrap(label.to_owned(), theme::semibold(13.5), Color32::PLACEHOLDER);
    let (rect, resp) = ui.allocate_exact_size(vec2(text.size().x + 28.0, 34.0), Sense::click());
    let resp = named(resp, label).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
    let s = theme::anim_bool(ui.ctx(), resp.id.with("s"), selected, 0.2);
    use crate::preset::ChipStyle;
    let style = crate::preset::active().app.chip;
    let radius = (crate::preset::active().chrome.radius * 0.55).clamp(4.0, 17.0);
    let (on_fill, on_fg) = match style {
        ChipStyle::Accent => {
            let a = ui.ctx().data(|d| d.get_temp::<Color32>(egui::Id::unique("accent"))).unwrap_or(theme::RED);
            (a, if a.r() as u32 + a.g() as u32 + a.b() as u32 > 480 { Color32::from_gray(15) } else { Color32::WHITE })
        }
        _ => (theme::text(), theme::on_text()),
    };
    let off_fill = if style == ChipStyle::Outline { theme::glass(0.04 * h) } else { theme::glass(0.09 + 0.06 * h) };
    let fill = theme::lerp_color(off_fill, on_fill, s);
    ui.painter().rect_filled(rect, radius, fill);
    if style == ChipStyle::Outline {
        ui.painter().rect_stroke(rect, radius, egui::Stroke::new(1.0, theme::glass(0.3 * (1.0 - s))), egui::StrokeKind::Inside);
    }
    let fg = theme::lerp_color(theme::text(), on_fg, s);
    ui.painter().galley(rect.center() - text.size() / 2.0, text, fg);
    resp
}
