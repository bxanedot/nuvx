use super::*;

pub fn shimmer_band(ui: &Ui, r: Rect, radius: f32, time: f64) {
    shimmer_band_alpha(ui, r, radius, time, 0.04);
}

pub fn loading_glow(ui: &Ui, r: Rect, time: f64, color: Color32) {
    let p = (time * 0.7).rem_euclid(2.0) as f32;
    let t = if p > 1.0 { 2.0 - p } else { p };
    let t = t * t * (3.0 - 2.0 * t);
    let len = r.width() * 0.22;
    let x = r.left() + (r.width() - len) * t;
    let steps = 12;
    for i in 0..steps {
        let f = (i as f32 + 0.5) / steps as f32;
        let a = 1.0 - (f * 2.0 - 1.0).abs();
        let seg = Rect::from_min_max(pos2(x + len * i as f32 / steps as f32, r.top()), pos2(x + len * (i + 1) as f32 / steps as f32, r.bottom()));
        ui.painter().rect_filled(seg, 0.0, color.gamma_multiply(a * a));
    }
    ui.ctx().request_repaint();
}

fn shimmer_band_alpha(ui: &Ui, r: Rect, radius: f32, time: f64, alpha: f32) {
    let phase = ((time * 0.8) % 1.6) as f32 - 0.3;
    let x = r.left() + (r.width() + 400.0) * phase - 200.0;
    let band = Rect::from_min_max(pos2((x - 120.0).max(r.left()), r.top()), pos2((x + 120.0).min(r.right()), r.bottom()));
    if band.width() > 0.0 {
        ui.painter().rect_filled(band, radius, theme::glass(alpha));
    }
}

pub fn paint_remote(ui: &Ui, url: &str, rect: Rect, radius: f32, uv: Rect, tint: Color32) {
    const FADE: f64 = 0.22;
    let ctx = ui.ctx();
    let now = ctx.input(|i| i.time);
    let id = egui::Id::unique(("img-fade", url));
    match egui::Image::new(url).load_for_size(ctx, rect.size()) {
        Ok(egui::load::TexturePoll::Ready { texture }) => {
            let a = match ctx.data(|d| d.get_temp::<Option<f64>>(id)) {
                Some(Some(t0)) if now - t0 < FADE => ((now - t0) / FADE) as f32,
                Some(None) => {
                    ctx.data_mut(|d| d.insert_temp(id, Some(now)));
                    0.0
                }
                _ => 1.0,
            };
            if a < 1.0 {
                ctx.request_repaint();
            }
            egui::Image::from_texture(texture).uv(uv).corner_radius(CornerRadius::same(radius as u8)).tint(tint.gamma_multiply(theme::ease_out(a))).paint_at(ui, rect);
        }
        Ok(egui::load::TexturePoll::Pending { .. }) => {
            ctx.data_mut(|d| d.insert_temp::<Option<f64>>(id, None));
            shimmer_band(ui, rect, radius, now);
            ctx.request_repaint_after(std::time::Duration::from_millis(66));
        }
        Err(_) => {}
    }
}

pub struct MotionSprite {
    pub id: egui::TextureId,
    pub size: Vec2,
}

pub fn paint_motion(ui: &Ui, rect: Rect, radius: f32, s: &MotionSprite) {
    if !ui.is_rect_visible(rect) || rect.width() <= 0.0 || rect.height() <= 0.0 {
        return;
    }
    let tex_aspect = s.size.x / s.size.y;
    let rect_aspect = rect.width() / rect.height();
    let uv = if rect_aspect > tex_aspect {
        Rect::from_center_size(pos2(0.5, 0.5), vec2(1.0, tex_aspect / rect_aspect))
    } else {
        Rect::from_center_size(pos2(0.5, 0.5), vec2(rect_aspect / tex_aspect, 1.0))
    };
    let shape = egui::epaint::RectShape::filled(rect, CornerRadius::same(radius as u8), Color32::WHITE)
        .with_texture(s.id, uv);
    ui.painter().add(shape);
}
