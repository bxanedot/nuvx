use egui::epaint::Vertex;
use egui::{Color32, Mesh, Rect, pos2};

use super::WordLook;
use crate::theme;
pub(super) fn push_quad(mesh: &mut Mesh, r: Rect, uv: Rect, color: Color32, alpha: &dyn Fn(f32) -> f32, cuts: &[f32]) {
    let mut xs = vec![r.left()];
    xs.extend(cuts.iter().copied().filter(|&c| c > r.left() && c < r.right()));
    xs.push(r.right());
    for s in xs.windows(2) {
        let (x0, x1) = (s[0], s[1]);
        if x1 - x0 <= 0.0 {
            continue;
        }
        let (a0, a1) = (alpha(x0), alpha(x1));
        if a0 <= 0.0 && a1 <= 0.0 {
            continue;
        }
        let u = |x: f32| uv.left() + (x - r.left()) / r.width().max(1e-6) * uv.width();
        let base = mesh.vertices.len() as u32;
        let c = |a: f32| theme::with_alpha(color, a);
        mesh.vertices.push(Vertex { pos: pos2(x0, r.top()), uv: pos2(u(x0), uv.top()), color: c(a0) });
        mesh.vertices.push(Vertex { pos: pos2(x1, r.top()), uv: pos2(u(x1), uv.top()), color: c(a1) });
        mesh.vertices.push(Vertex { pos: pos2(x1, r.bottom()), uv: pos2(u(x1), uv.bottom()), color: c(a1) });
        mesh.vertices.push(Vertex { pos: pos2(x0, r.bottom()), uv: pos2(u(x0), uv.bottom()), color: c(a0) });
        mesh.indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    }
}

pub(super) fn lit_gradient(q: &Rect, letter: usize, letters: usize, word: Rect, look: &WordLook, by_letter: bool, rtl: bool) -> (Box<dyn Fn(f32) -> f32>, Vec<f32>) {
    let (s, e) = (look.start, look.end);
    if rtl && !by_letter {
        let xs = word.right() - word.width() * s;
        let xe = word.right() - word.width() * e - 1.0;
        let f = move |x: f32| if x >= xs { 1.0 } else if x <= xe { 0.0 } else { 1.0 - (xs - x) / (xs - xe) };
        return (Box::new(f), vec![xe, xs]);
    }
    if by_letter {
        let n = letters.max(1) as f32;
        let (l, w) = (q.left(), q.width().max(1e-6));
        let i = letter as f32;
        let x_at = |v: f32| l + (v * n - i) * w;
        let (xs, xe) = (x_at(s), x_at(e) + 1.0);
        let f = move |x: f32| if x <= xs { 1.0 } else if x >= xe { 0.0 } else { 1.0 - (x - xs) / (xe - xs) };
        (Box::new(f), vec![xs, xe])
    } else {
        let xs = word.left() + word.width() * s;
        let xe = word.left() + word.width() * e + 1.0;
        let f = move |x: f32| if x <= xs { 1.0 } else if x >= xe { 0.0 } else { 1.0 - (x - xs) / (xe - xs) };
        (Box::new(f), vec![xs, xe])
    }
}

