use std::sync::Arc;

use egui::layers::ShapeIdx;
use egui::{Mesh, Rect, Shape};
pub(super) fn fade_edges(ctx: &egui::Context, layer: egui::LayerId, from: ShapeIdx, rect: Rect, size: f32) {
    let zone = (size * 1.6).min(rect.height() * 0.15).max(1.0);
    let edge = EdgeFade { rect, zone };
    ctx.graphics_mut(|g| {
        let list = g.entry(layer);
        for i in from.0..list.next_idx().0 {
            list.mutate_shape(ShapeIdx(i), |c| edge.shape(&mut c.shape));
        }
    });
}

struct EdgeFade {
    rect: Rect,
    zone: f32,
}

impl EdgeFade {
    fn k(&self, y: f32) -> f32 {
        let s = |d: f32| {
            let x = (d / self.zone).clamp(0.0, 1.0);
            x * x * (3.0 - 2.0 * x)
        };
        s(y - self.rect.top()) * s(self.rect.bottom() - y)
    }

    fn shape(&self, shape: &mut Shape) {
        match shape {
            Shape::Mesh(m) => {
                let (top, bottom) = (self.rect.top() + self.zone, self.rect.bottom() - self.zone);
                if m.vertices.iter().any(|v| v.pos.y < top || v.pos.y > bottom) {
                    *m = Arc::new(self.mesh(m));
                }
            }
            Shape::Rect(r) => {
                let f = self.k(r.rect.center().y);
                r.fill = r.fill.gamma_multiply(f);
                r.stroke.color = r.stroke.color.gamma_multiply(f);
            }
            Shape::Text(t) => t.opacity_factor *= self.k(t.pos.y + t.galley.size().y / 2.0),
            Shape::Vec(v) => v.iter_mut().for_each(|s| self.shape(s)),
            _ => {}
        }
    }

    fn mesh(&self, m: &Mesh) -> Mesh {
        use egui::epaint::Vertex;
        const BAND: f32 = 2.0;
        let (r, z) = (self.rect, self.zone);
        let mut cuts = Vec::new();
        for (a, b) in [(r.top(), r.top() + z), (r.bottom() - z, r.bottom())] {
            let n = ((b - a) / BAND).ceil().max(1.0) as usize;
            cuts.extend((0..=n).map(|i| a + (b - a) * i as f32 / n as f32));
        }
        let lerp = |a: &Vertex, b: &Vertex, t: f32| Vertex {
            pos: a.pos + (b.pos - a.pos) * t,
            uv: a.uv + (b.uv - a.uv) * t,
            color: a.color.lerp_to_gamma(b.color, t),
        };
        let clip = |poly: &[Vertex], y: f32, keep_below: bool| -> Vec<Vertex> {
            let inside = |v: &Vertex| if keep_below { v.pos.y >= y } else { v.pos.y <= y };
            let mut out = Vec::with_capacity(poly.len() + 2);
            for i in 0..poly.len() {
                let (a, b) = (&poly[i], &poly[(i + 1) % poly.len()]);
                if inside(a) {
                    out.push(*a);
                }
                if inside(a) != inside(b) {
                    out.push(lerp(a, b, (y - a.pos.y) / (b.pos.y - a.pos.y)));
                }
            }
            out
        };
        let mut out = Mesh::with_texture(m.texture_id);
        for tri in m.indices.as_chunks::<3>().0 {
            let poly = [m.vertices[tri[0] as usize], m.vertices[tri[1] as usize], m.vertices[tri[2] as usize]];
            let lo = poly.iter().map(|v| v.pos.y).fold(f32::INFINITY, f32::min);
            let hi = poly.iter().map(|v| v.pos.y).fold(f32::NEG_INFINITY, f32::max);
            if hi <= r.top() || lo >= r.bottom() {
                continue;
            }
            let mut ys: Vec<f32> = cuts.iter().copied().filter(|&y| y > lo && y < hi).collect();
            ys.insert(0, lo.max(r.top()));
            ys.push(hi.min(r.bottom()));
            for w in ys.windows(2) {
                let piece = clip(&clip(&poly, w[0], true), w[1], false);
                if piece.len() < 3 {
                    continue;
                }
                let base = out.vertices.len() as u32;
                out.vertices.extend(piece.iter().map(|v| Vertex { color: v.color.gamma_multiply(self.k(v.pos.y)), ..*v }));
                for i in 1..piece.len() as u32 - 1 {
                    out.indices.extend([base, base + i, base + i + 1]);
                }
            }
        }
        out
    }
}
