use super::word_mesh::push_quad;
use egui::{Color32, Galley, Mesh, Pos2, Rect, Shape, TextureId, pos2};

#[derive(Clone, Copy)]
pub(super) struct Xf {
    pub(super) origin: Pos2,
    pub(super) scale: f32,
    pub(super) content: Pos2,
}

impl Xf {
    pub(super) fn point(&self, p: Pos2) -> Pos2 {
        let s = self.content + p.to_vec2();
        self.origin + (s - self.origin) * self.scale
    }
    pub(super) fn rect(&self, r: Rect) -> Rect {
        Rect::from_min_max(self.point(r.min), self.point(r.max))
    }
}

pub(super) fn paint_galley(painter: &egui::Painter, g: &Galley, at: Pos2, xf: &Xf, color: Color32, atlas: [usize; 2]) {
    let mut mesh = Mesh::with_texture(TextureId::default());
    let none: [f32; 0] = [];
    for row in &g.rows {
        for gl in &row.row.glyphs {
            let uv = gl.uv_rect;
            if uv.is_nothing() {
                continue;
            }
            let r = Rect::from_min_size(at + row.pos.to_vec2() + gl.pos.to_vec2() + uv.offset, uv.size);
            let uvr = Rect::from_min_max(
                pos2(uv.min[0] as f32 / atlas[0] as f32, uv.min[1] as f32 / atlas[1] as f32),
                pos2(uv.max[0] as f32 / atlas[0] as f32, uv.max[1] as f32 / atlas[1] as f32),
            );
            push_quad(&mut mesh, xf.rect(r), uvr, color, &|_| 1.0, &none);
        }
    }
    painter.add(Shape::mesh(mesh));
}
