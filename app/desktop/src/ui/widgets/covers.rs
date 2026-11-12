use super::*;

fn fetch_px(px: u32) -> u32 {
    const STEPS: [u32; 9] = [60, 120, 180, 240, 360, 544, 720, 960, 1200];
    STEPS.into_iter().find(|&s| s >= px).unwrap_or(1200)
}

fn steady_url(ctx: &egui::Context, base: &str, px: u32) -> String {
    let want = sized(base, px);
    let id = egui::Id::unique(("cover-shown", base));
    let ready = matches!(egui::Image::new(want.as_str()).load_for_size(ctx, Vec2::splat(px as f32)), Ok(egui::load::TexturePoll::Ready { .. }));
    if ready {
        ctx.data_mut(|d| d.insert_temp(id, px));
        return want;
    }
    match ctx.data(|d| d.get_temp::<u32>(id)) {
        Some(shown) if shown != px => sized(base, shown),
        _ => want,
    }
}

pub fn paint_cover(ui: &Ui, rect: Rect, thumbs: &[Thumb], radius: f32, zoom: f32) {
    let px = (rect.width() * ui.ctx().pixels_per_point()) as u32;
    super::paint_cover_px(ui, rect, thumbs, radius, zoom, px);
}

pub fn paint_cover_xfade(ui: &Ui, site: egui::Id, rect: Rect, thumbs: &[Thumb], radius: f32, zoom: f32) {
    let px = (rect.width() * ui.ctx().pixels_per_point()) as u32;
    paint_cover_xfade_px(ui, site, rect, thumbs, radius, zoom, px);
}

pub fn paint_cover_xfade_px(ui: &Ui, site: egui::Id, rect: Rect, thumbs: &[Thumb], radius: f32, zoom: f32, px: u32) {
    if !ui.is_rect_visible(rect) {
        return;
    }
    let px = fetch_px(px);
    ui.painter().rect_filled(rect, radius, theme::glass(0.07));
    let Some(t) = pick_thumb(thumbs, px.min(544)) else {
        icon_at(ui, rect.center(), icon::MUSIC, rect.width() * 0.3, theme::text_faint());
        return;
    };
    let mut uv = t.square_uv();
    if zoom > 1.0 {
        let c = uv.center();
        let half = uv.size() / (2.0 * zoom);
        uv = Rect::from_center_size(c, half * 2.0);
    }
    let url = steady_url(ui.ctx(), &t.url, px);
    cover_xfade(ui, site, rect, radius, &url, uv, Color32::WHITE);
}

pub fn paint_cover_px(ui: &Ui, rect: Rect, thumbs: &[Thumb], radius: f32, zoom: f32, px: u32) {
    if !ui.is_rect_visible(rect) {
        return;
    }
    let px = fetch_px(px);
    ui.painter().rect_filled(rect, radius, theme::glass(0.07));
    let Some(t) = pick_thumb(thumbs, px.min(544)) else {
        icon_at(ui, rect.center(), icon::MUSIC, rect.width() * 0.3, theme::text_faint());
        return;
    };
    let mut uv = t.square_uv();
    if zoom > 1.0 {
        let c = uv.center();
        let half = uv.size() / (2.0 * zoom);
        uv = Rect::from_center_size(c, half * 2.0);
    }
    let url = steady_url(ui.ctx(), &t.url, px);
    paint_remote(ui, &url, rect, radius, uv, Color32::WHITE);
}

pub fn paint_cover_fill(ui: &Ui, rect: Rect, thumbs: &[Thumb], tint: Color32) {
    let ppp = ui.ctx().pixels_per_point();
    let px = fetch_px((rect.width().max(rect.height()) * ppp) as u32).clamp(200, 1200);
    let Some(t) = pick_thumb(thumbs, px) else { return };
    let uv = t.square_uv();
    let aspect = rect.width() / rect.height().max(1.0);
    let size = if aspect >= 1.0 { vec2(uv.width(), uv.height() / aspect) } else { vec2(uv.width() * aspect, uv.height()) };
    let url = steady_url(ui.ctx(), &t.url, px);
    paint_remote(ui, &url, rect, 0.0, Rect::from_center_size(uv.center(), size), tint);
}

pub fn preload_cover(ctx: &egui::Context, thumbs: &[Thumb], px: u32) {
    let px = fetch_px(px);
    if let Some(t) = pick_thumb(thumbs, px.min(544)) {
        let _ = egui::Image::new(sized(&t.url, px)).load_for_size(ctx, Vec2::splat(px as f32));
    }
}

#[allow(clippy::too_many_arguments)]
pub fn paint_disc(ui: &Ui, site: egui::Id, center: Pos2, r: f32, thumbs: &[Thumb], angle: f32, tint: Color32, px: u32) {
    ui.painter().circle_filled(center, r, theme::glass(0.07));
    let px = fetch_px(px);
    let Some(t) = pick_thumb(thumbs, px.min(544)) else { return };
    let url = steady_url(ui.ctx(), &t.url, px);
    let ready = matches!(egui::Image::new(&url).load_for_size(ui.ctx(), Vec2::splat(r * 2.0)), Ok(egui::load::TexturePoll::Ready { .. }));
    let uv = t.square_uv();
    dissolve(ui, site, &url, uv, ready, &mut |u, uv, a| {
        disc_at(ui, center, r, u, uv, angle, tint.gamma_multiply(a));
    });
}

fn disc_at(ui: &Ui, center: Pos2, r: f32, url: &str, uv: Rect, angle: f32, tint: Color32) -> bool {
    let Ok(egui::load::TexturePoll::Ready { texture }) = egui::Image::new(url).load_for_size(ui.ctx(), Vec2::splat(r * 2.0)) else { return false };
    let (uc, uh) = (uv.center(), uv.width() / 2.0);
    let mut mesh = egui::Mesh::with_texture(texture.id);
    mesh.colored_vertex(center, tint);
    mesh.vertices[0].uv = uc;
    let n = 96;
    for i in 0..=n {
        let a = i as f32 / n as f32 * std::f32::consts::TAU;
        let (s, c) = a.sin_cos();
        let (sr, cr) = (a - angle).sin_cos();
        mesh.vertices.push(egui::epaint::Vertex { pos: center + vec2(c, s) * r, uv: uc + vec2(cr * uh, sr * uv.height() / 2.0), color: tint });
        if i > 0 {
            mesh.add_triangle(0, i, i + 1);
        }
    }
    ui.painter().add(egui::Shape::mesh(mesh));
    true
}

const COVER_XFADE: f64 = 0.45;

#[derive(Clone)]
struct Shown {
    url: String,
    uv: Rect,
    to: Option<String>,
    swap_at: f64,
    fade_from: Option<f64>,
}

fn dissolve(ui: &Ui, site: egui::Id, url: &str, uv: Rect, ready: bool, paint: &mut dyn FnMut(&str, Rect, f32)) {
    let ctx = ui.ctx();
    let now = ctx.input(|i| i.time);
    let key = site.with("cover-shown");
    let Some(mut s) = ctx.data(|d| d.get_temp::<Shown>(key)) else {
        paint(url, uv, 1.0);
        ctx.data_mut(|d| d.insert_temp(key, Shown { url: url.to_owned(), uv, to: None, swap_at: now, fade_from: None }));
        return;
    };
    if s.url == url {
        paint(url, uv, 1.0);
        if s.to.is_some() {
            ctx.data_mut(|d| d.insert_temp(key, Shown { url: url.to_owned(), uv, to: None, swap_at: now, fade_from: None }));
        }
        return;
    }
    if s.to.as_deref() != Some(url) {
        s.to = Some(url.to_owned());
        s.swap_at = now;
        s.fade_from = None;
    }
    if !ready {
        if now - s.swap_at < 3.0 {
            paint(&s.url, s.uv, 1.0);
            ctx.data_mut(|d| d.insert_temp(key, s));
            ctx.request_repaint();
            return;
        }
        paint(url, uv, 1.0);
        ctx.data_mut(|d| d.insert_temp(key, Shown { url: url.to_owned(), uv, to: None, swap_at: now, fade_from: None }));
        return;
    }
    let from = *s.fade_from.get_or_insert(now);
    let p = (((now - from) / COVER_XFADE) as f32).clamp(0.0, 1.0);
    let e = theme::ease_in_out(p);
    paint(&s.url, s.uv, 1.0 - e);
    paint(url, uv, e);
    if p >= 1.0 {
        s = Shown { url: url.to_owned(), uv, to: None, swap_at: now, fade_from: None };
    } else {
        ctx.request_repaint();
    }
    ctx.data_mut(|d| d.insert_temp(key, s));
}

fn cover_xfade(ui: &Ui, site: egui::Id, rect: Rect, radius: f32, url: &str, uv: Rect, tint: Color32) {
    let ready = matches!(egui::Image::new(url).load_for_size(ui.ctx(), rect.size()), Ok(egui::load::TexturePoll::Ready { .. }));
    dissolve(ui, site, url, uv, ready, &mut |u, uv, a| paint_remote(ui, u, rect, radius, uv, tint.gamma_multiply(a)));
}
