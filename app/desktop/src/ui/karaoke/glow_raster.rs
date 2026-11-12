use egui::{Color32, ColorImage, Rect, Vec2, vec2};

use super::GlowJob;
use super::lit_gradient;
pub(super) struct Coverage {
    pub(super) size: [usize; 2],
    pub(super) alpha: Vec<u8>,
}

pub(super) fn glyph_blank(a: &Coverage, uv: Rect) -> bool {
    let (w, h) = (a.size[0] as f32, a.size[1] as f32);
    let (x0, y0, x1, y1) = ((uv.left() * w) as usize, (uv.top() * h) as usize, (uv.right() * w) as usize, (uv.bottom() * h) as usize);
    if x1 <= x0 || y1 <= y0 || x1 > a.size[0] || y1 > a.size[1] {
        return true;
    }
    for y in y0..y1 {
        for x in x0..x1 {
            if a.alpha[y * a.size[0] + x] > 0 {
                return false;
            }
        }
    }
    true
}

pub(super) fn rasterize_glow(atlas: &Coverage, j: &GlowJob, ppp: f32, scratch: &mut Vec<f32>) -> Option<(ColorImage, Rect)> {
    let sigma = (j.radius * 0.5 * ppp).max(0.3);
    let pad = (sigma * 3.0).ceil() + 2.0;
    let bounds = j.quads.iter().map(|q| q.0).chain(j.pills.iter().map(|p| p.0)).fold(Rect::NOTHING, |a, r| a.union(r));
    if !bounds.is_positive() {
        return None;
    }
    let origin = bounds.min - Vec2::splat(pad / ppp);
    let w = (bounds.width() * ppp + pad * 2.0).ceil() as usize;
    let h = (bounds.height() * ppp + pad * 2.0).ceil() as usize;
    if w == 0 || h == 0 || w * h > 4_000_000 {
        return None;
    }
    scratch.clear();
    scratch.resize(w * h, 0.0);
    for &(r, radius) in &j.pills {
        paint_pill(scratch, w, h, (r.min - origin) * ppp, r.size() * ppp, radius * ppp, ppp);
    }
    let (aw, ah) = (atlas.size[0] as f32, atlas.size[1] as f32);
    for &(r, uv, letter, letters, weight) in &j.quads {
        let (alpha, _) = lit_gradient(&r, letter, letters, j.word, &j.look, j.by_letter, j.rtl);
        let tx0 = (uv.left() * aw).round() as isize;
        let ty0 = (uv.top() * ah).round() as isize;
        let tw = ((uv.right() - uv.left()) * aw).round() as isize;
        let th = ((uv.bottom() - uv.top()) * ah).round() as isize;
        if tw <= 0 || th <= 0 {
            continue;
        }
        let dx0 = (r.left() - origin.x) * ppp;
        let dy0 = (r.top() - origin.y) * ppp;
        let dw = r.width() * ppp;
        let dh = r.height() * ppp;
        let (ix0, iy0) = (dx0.floor().max(0.0) as usize, dy0.floor().max(0.0) as usize);
        let (ix1, iy1) = (((dx0 + dw).ceil() as usize).min(w), ((dy0 + dh).ceil() as usize).min(h));
        let texel = |x: isize, y: isize| -> f32 {
            if x < 0 || y < 0 || x >= tw || y >= th {
                return 0.0;
            }
            atlas.alpha.get((ty0 + y) as usize * atlas.size[0] + (tx0 + x) as usize).map_or(0.0, |&p| p as f32 / 255.0)
        };
        for y in iy0..iy1 {
            let fy = (y as f32 + 0.5 - dy0) / dh * th as f32 - 0.5;
            let (sy, ky) = (fy.floor() as isize, fy - fy.floor());
            for x in ix0..ix1 {
                let fx = (x as f32 + 0.5 - dx0) / dw * tw as f32 - 0.5;
                let (sx, kx) = (fx.floor() as isize, fx - fx.floor());
                let top = texel(sx, sy) * (1.0 - kx) + texel(sx + 1, sy) * kx;
                let bottom = texel(sx, sy + 1) * (1.0 - kx) + texel(sx + 1, sy + 1) * kx;
                let cov = top * (1.0 - ky) + bottom * ky;
                if cov <= 0.0 {
                    continue;
                }
                let screen_x = origin.x + (x as f32 + 0.5) / ppp;
                let a = cov * alpha(screen_x) * weight;
                let d = &mut scratch[y * w + x];
                *d = (*d + a - *d * a).min(1.0);
            }
        }
    }
    gaussian(scratch, w, h, sigma);
    let pixels = scratch
        .iter()
        .map(|v| {
            let a = (v.clamp(0.0, 1.0) * 255.0).round() as u8;
            Color32::from_rgba_premultiplied(a, a, a, a)
        })
        .collect();
    let img = ColorImage { size: [w, h], source_size: vec2(w as f32, h as f32), pixels };
    Some((img, Rect::from_min_size(origin, vec2(w as f32, h as f32) / ppp)))
}

fn gaussian(buf: &mut [f32], w: usize, h: usize, sigma: f32) {
    if sigma < 2.0 {
        let r = (sigma * 3.0).ceil() as usize;
        let mut k: Vec<f32> = (0..=2 * r).map(|i| (-(i as f32 - r as f32).powi(2) / (2.0 * sigma * sigma)).exp()).collect();
        let sum: f32 = k.iter().sum();
        k.iter_mut().for_each(|v| *v /= sum);
        let mut tmp = vec![0.0f32; buf.len()];
        convolve(buf, &mut tmp, w, h, &k, true);
        convolve(&tmp, buf, w, h, &k, false);
        return;
    }
    let n = 3.0f32;
    let ideal = (12.0 * sigma * sigma / n + 1.0).sqrt();
    let mut wl = ideal.floor() as i32;
    if wl % 2 == 0 {
        wl -= 1;
    }
    let wu = wl + 2;
    let m_ideal = (12.0 * sigma * sigma - n * (wl * wl) as f32 - 4.0 * n * wl as f32 - 3.0 * n) / (-4.0 * wl as f32 - 4.0);
    let m = m_ideal.round() as i32;
    let mut tmp = vec![0.0f32; buf.len()];
    for i in 0..3 {
        let size = if i < m { wl } else { wu };
        let r = ((size - 1) / 2).max(0) as usize;
        if r == 0 {
            continue;
        }
        box_pass(buf, &mut tmp, w, h, r, true);
        box_pass(&tmp, buf, w, h, r, false);
    }
}

fn convolve(src: &[f32], dst: &mut [f32], w: usize, h: usize, k: &[f32], horizontal: bool) {
    let r = (k.len() / 2) as isize;
    let (n, lines) = if horizontal { (w, h) } else { (h, w) };
    let at = |line: usize, i: usize| if horizontal { line * w + i } else { i * w + line };
    for line in 0..lines {
        for i in 0..n {
            let mut acc = 0.0;
            for (j, kv) in k.iter().enumerate() {
                let s = i as isize + j as isize - r;
                if s >= 0 && (s as usize) < n {
                    acc += src[at(line, s as usize)] * kv;
                }
            }
            dst[at(line, i)] = acc;
        }
    }
}

fn paint_pill(buf: &mut [f32], w: usize, h: usize, min: Vec2, size: Vec2, radius: f32, ppp: f32) {
    let radius = radius.min(size.x * 0.5).min(size.y * 0.5);
    let half = size * 0.5;
    let centre = min + half;
    let (x0, y0) = (min.x.floor().max(0.0) as usize, min.y.floor().max(0.0) as usize);
    let (x1, y1) = (((min.x + size.x).ceil() as usize).min(w), ((min.y + size.y).ceil() as usize).min(h));
    let over = |d: &mut f32, a: f32| *d = (*d + a - *d * a).min(1.0);
    for y in y0..y1 {
        for x in x0..x1 {
            let p = vec2(x as f32 + 0.5, y as f32 + 0.5) - centre;
            let q = vec2(p.x.abs(), p.y.abs()) - half + Vec2::splat(radius);
            let d = vec2(q.x.max(0.0), q.y.max(0.0)).length() + q.x.max(q.y).min(0.0) - radius;
            let inside = (0.5 - d).clamp(0.0, 1.0);
            let border = inside - (0.5 - (d + ppp)).clamp(0.0, 1.0);
            let px = &mut buf[y * w + x];
            over(px, 0.05 * inside);
            over(px, 0.1 * border);
        }
    }
}

fn box_pass(src: &[f32], dst: &mut [f32], w: usize, h: usize, r: usize, horizontal: bool) {
    let (n, lines) = if horizontal { (w, h) } else { (h, w) };
    let idx = |line: usize, i: usize| if horizontal { line * w + i } else { i * w + line };
    let norm = 1.0 / (2 * r + 1) as f32;
    for line in 0..lines {
        let mut acc = 0.0;
        for i in 0..=r.min(n - 1) {
            acc += src[idx(line, i)];
        }
        for i in 0..n {
            dst[idx(line, i)] = acc * norm;
            if i + r + 1 < n {
                acc += src[idx(line, i + r + 1)];
            }
            if i >= r {
                acc -= src[idx(line, i - r)];
            }
        }
    }
}
