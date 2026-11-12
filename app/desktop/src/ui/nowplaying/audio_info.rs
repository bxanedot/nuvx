use super::*;

fn badge_font(small: bool) -> egui::FontId {
    theme::semibold(if small { 9.0 } else { 11.0 })
}

fn glyph_side(small: bool) -> f32 {
    if small { 11.0 } else { 13.0 }
}

fn has_quality_glyph(info: &crate::hires::Info) -> bool {
    info.atmos || info.lossless()
}

fn quality_icon(atmos: bool) -> &'static [u8] {
    if atmos {
        include_bytes!("../../../assets/quality/atmos.png")
    } else {
        include_bytes!("../../../assets/quality/lossless.png")
    }
}

fn quality_icon_aspect(atmos: bool) -> f32 {
    if atmos { 78.0 / 60.0 } else { 86.0 / 60.0 }
}

fn quality_icon_texture(ui: &Ui, atmos: bool) -> egui::TextureHandle {
    let ctx = ui.ctx();
    let id = egui::Id::unique(("quality-icon", atmos));
    if let Some(tex) = ctx.data(|d| d.get_temp::<egui::TextureHandle>(id)) {
        return tex;
    }
    let rgba = image::load_from_memory(quality_icon(atmos)).expect("quality icon").to_rgba8();
    let mut img = egui::ColorImage::from_rgba_unmultiplied([rgba.width() as usize, rgba.height() as usize], rgba.as_raw());
    for p in &mut img.pixels {
        *p = Color32::from_white_alpha(p.a());
    }
    let tex = ctx.load_texture(if atmos { "apple-atmos" } else { "apple-lossless" }, img, egui::TextureOptions::LINEAR);
    ctx.data_mut(|d| d.insert_temp(id, tex.clone()));
    tex
}

fn paint_quality_icon(ui: &Ui, center: egui::Pos2, height: f32, atmos: bool, tint: Color32) {
    let w = height * quality_icon_aspect(atmos);
    let rect = Rect::from_center_size(center, vec2(w, height));
    let uv = Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0));
    ui.painter().image(quality_icon_texture(ui, atmos).id(), rect, uv, tint);
}

pub fn audio_badge_width(ui: &Ui, app: &App, small: bool) -> f32 {
    let Some((_, info)) = app.audio_info.as_ref() else { return 0.0 };
    let label = if app.mixing() { "Mixing" } else { info.badge() };
    let text = widgets::galley(ui, label, badge_font(small), Color32::WHITE, 200.0).size().x;
    if has_quality_glyph(info) { text + glyph_side(small) * quality_icon_aspect(info.atmos) + 5.0 } else { text }
}

pub fn audio_badge(app: &App, ui: &mut Ui, at: egui::Pos2, small: bool) -> f32 {
    let Some((_, info)) = app.audio_info.as_ref() else { return 0.0 };
    let font = badge_font(small);
    let g = widgets::galley(ui, if app.mixing() { "Mixing" } else { info.badge() }, font, Color32::WHITE, 200.0);
    let gh = glyph_side(small);
    let glyph = has_quality_glyph(info);
    let w = audio_badge_width(ui, app, small);
    let h = g.size().y.max(if glyph { gh } else { 0.0 });
    let rect = Rect::from_min_size(at, vec2(w, h));
    let resp = ui.interact(rect, ui.scope_id().with("audio-badge"), Sense::click());
    let resp = named(resp, "Audio quality").on_hover_cursor(egui::CursorIcon::PointingHand);
    let open = egui::Popup::is_id_open(ui.ctx(), egui::Popup::default_response_id(&resp));
    let hover = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered() || open, 0.12);
    let (ink, mark) = if info.lossless() || info.atmos {
        let accent = app.accent_color();
        (theme::lerp_color(accent, Color32::WHITE, 0.10 + 0.30 * hover), theme::with_alpha(accent, 0.85 + 0.15 * hover))
    } else {
        (theme::with_alpha(theme::text_dim(), 0.85 + 0.15 * hover), theme::text_dim())
    };
    let mut x = at.x;
    if glyph {
        let icon_w = gh * quality_icon_aspect(info.atmos);
        paint_quality_icon(ui, pos2(x + icon_w / 2.0, at.y + h / 2.0), gh, info.atmos, mark);
        x += icon_w + 5.0;
    }
    ui.painter().galley(pos2(x, at.y + (h - g.size().y) / 2.0), g, ink);
    egui::Popup::menu(&resp).close_behavior(egui::PopupCloseBehavior::CloseOnClickOutside).show(|ui| audio_info_menu(ui, info));
    w
}

pub fn audio_badge_icon_width(app: &App, small: bool) -> f32 {
    let Some((_, info)) = app.audio_info.as_ref().filter(|(_, i)| has_quality_glyph(i)) else { return 0.0 };
    glyph_side(small) * quality_icon_aspect(info.atmos)
}

pub fn audio_badge_icon(app: &App, ui: &mut Ui, center: egui::Pos2, small: bool) {
    let Some((_, info)) = app.audio_info.as_ref().filter(|(_, i)| has_quality_glyph(i)) else { return };
    let gh = glyph_side(small);
    let rect = Rect::from_center_size(center, vec2(gh * quality_icon_aspect(info.atmos), gh));
    let resp = ui.interact(rect, ui.scope_id().with("audio-badge"), Sense::click());
    let resp = named(resp, "Audio quality").on_hover_cursor(egui::CursorIcon::PointingHand);
    let open = egui::Popup::is_id_open(ui.ctx(), egui::Popup::default_response_id(&resp));
    let hover = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered() || open, 0.12);
    let accent = app.accent_color();
    let mark = theme::with_alpha(accent, 0.85 + 0.15 * hover);
    paint_quality_icon(ui, center, gh, info.atmos, mark);
    egui::Popup::menu(&resp).close_behavior(egui::PopupCloseBehavior::CloseOnClickOutside).show(|ui| audio_info_menu(ui, info));
}

fn audio_info_menu(ui: &mut Ui, info: &crate::hires::Info) {
    ui.set_width(300.0);
    ui.spacing_mut().item_spacing = vec2(0.0, 0.0);
    ui.add_space(4.0);
    menu_caption(ui, "AUDIO QUALITY");
    let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), 34.0), Sense::hover());
    let g = widgets::galley(ui, info.badge(), theme::semibold(17.0), theme::text(), 200.0);
    let mut x = r.left() + 12.0;
    if has_quality_glyph(info) {
        let ih = 17.0;
        let icon_w = ih * quality_icon_aspect(info.atmos);
        paint_quality_icon(ui, pos2(x + icon_w / 2.0, r.center().y), ih, info.atmos, theme::text());
        x += icon_w + 8.0;
    }
    ui.painter().galley(pos2(x, r.center().y - g.size().y / 2.0), g.clone(), theme::text());
    menu_divider(ui);
    info_row(ui, "Source", if info.source.is_empty() { &info.host } else { &info.source });
    if !info.codec.is_empty() {
        info_row(ui, "Codec", &info.codec.to_ascii_uppercase());
    }
    if let Some(kbps) = bitrate(info) {
        info_row(ui, "Bitrate", &format!("{} kbps", thousands(kbps)));
    }
    if let Some(hz) = info.sample_rate {
        info_row(ui, "Sample rate", &rate_text(hz));
    }
    if let Some(bits) = info.bit_depth {
        info_row(ui, "Bit depth", &format!("{bits}-bit"));
    }
    if let Some(n) = info.channels {
        let text = match n {
            1 => "Mono".to_owned(),
            2 => "Stereo".to_owned(),
            _ => format!("{n} channels"),
        };
        info_row(ui, "Channels", &text);
    }
    if info.size > 0 {
        info_row(ui, "Size", &size_text(info.size));
    }
    if info.duration_ms > 0 {
        info_row(ui, "Duration", &super::super::fmt_time((info.duration_ms / 1000) as u32));
    }
    ui.add_space(6.0);
}

fn info_row(ui: &mut Ui, label: &str, value: &str) {
    let (r, _) = ui.allocate_exact_size(vec2(ui.available_width(), 30.0), Sense::hover());
    ui.painter().text(pos2(r.left() + 12.0, r.center().y), egui::Align2::LEFT_CENTER, label, theme::regular(13.0), theme::text_dim());
    let g = widgets::galley(ui, value, theme::semibold(13.0), theme::text(), 190.0);
    ui.painter().galley(pos2(r.right() - 12.0 - g.size().x, r.center().y - g.size().y / 2.0), g, theme::text());
}

fn bitrate(info: &crate::hires::Info) -> Option<u32> {
    info.bitrate_kbps
        .or_else(|| (info.duration_ms > 0 && info.size > 0).then(|| (info.size * 8 / info.duration_ms) as u32))
        .filter(|k| *k > 0)
}

fn thousands(n: u32) -> String {
    let s = n.to_string();
    let mut out = String::with_capacity(s.len() + s.len() / 3);
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    out
}

fn rate_text(hz: u32) -> String {
    if hz % 1000 == 0 { format!("{} kHz", hz / 1000) } else { format!("{:.1} kHz", hz as f64 / 1000.0) }
}

fn size_text(bytes: u64) -> String {
    if bytes >= 1024 * 1024 { format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0)) } else { format!("{:.0} KB", bytes as f64 / 1024.0) }
}
