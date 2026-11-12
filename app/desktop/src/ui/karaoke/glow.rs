use std::collections::HashMap;
use std::sync::Arc;

use super::*;
use super::glow_raster::{Coverage, glyph_blank, rasterize_glow};
use egui::{ColorImage, TextureHandle, TextureOptions};

struct Slot {
    tex: TextureHandle,
    size: [usize; 2],
    used: [usize; 2],
    hash: u64,
}

const SLOT_STEP: usize = 64;
const SPARE_SLOTS: usize = 8;

impl Slot {
    fn uv(&self) -> Rect {
        Rect::from_min_max(pos2(0.0, 0.0), pos2(self.used[0] as f32 / self.size[0] as f32, self.used[1] as f32 / self.size[1] as f32))
    }
}

type LineStep = (i32, i32, u64);

#[derive(Default)]
pub(super) struct Glow {
    atlas: Option<Arc<Coverage>>,
    textures: HashMap<(u64, usize, u32, u32), (Slot, f64)>,
    lines: HashMap<(u64, usize), (Slot, LineStep, Rect, f64)>,
    spare: Vec<Slot>,
    scratch: Vec<f32>,
}

impl Glow {
    fn atlas(&mut self, ctx: &egui::Context, size: [usize; 2], need: &[Rect]) -> Arc<Coverage> {
        let stale = match &self.atlas {
            None => true,
            Some(a) => a.size != size || need.iter().any(|uv| glyph_blank(a, *uv)),
        };
        if stale {
            let img = ctx.fonts(|f| f.image());
            self.atlas = Some(Arc::new(Coverage { size: img.size, alpha: img.pixels.iter().map(|p| p.a()).collect() }));
        }
        self.atlas.clone().unwrap()
    }

    fn upload(&mut self, ctx: &egui::Context, old: Option<Slot>, img: ColorImage, name: &str) -> Slot {
        let used = img.size;
        let image_hash = hash((used, &img.pixels));
        if let Some(s) = &old
            && s.used == used
            && s.hash == image_hash
        {
            return old.unwrap();
        }
        let (w, h) = (used[0] + 1, used[1] + 1);
        let bucket = [w.next_multiple_of(SLOT_STEP), h.next_multiple_of(SLOT_STEP)];
        let fits = |s: &Slot| s.size[0] >= w && s.size[1] >= h && s.size[0] * s.size[1] <= 2 * bucket[0] * bucket[1];
        let slot = match old {
            Some(s) if fits(&s) => Some(s),
            Some(s) => {
                self.recycle(s);
                None
            }
            None => None,
        };
        let slot = slot.or_else(|| self.spare.iter().position(fits).map(|k| self.spare.swap_remove(k)));
        let size = slot.as_ref().map_or(bucket, |s| s.size);
        let (pw, ph) = if slot.is_some() { (w, h) } else { (size[0], size[1]) };
        let mut pixels = vec![Color32::TRANSPARENT; pw * ph];
        for (y, row) in img.pixels.chunks_exact(used[0]).enumerate() {
            pixels[y * pw..y * pw + used[0]].copy_from_slice(row);
        }
        let img = ColorImage { size: [pw, ph], source_size: vec2(pw as f32, ph as f32), pixels };
        match slot {
            Some(mut s) => {
                s.tex.set_partial([0, 0], img, TextureOptions::LINEAR);
                s.used = used;
                s.hash = image_hash;
                s
            }
            None => Slot { tex: ctx.load_texture(name, img, TextureOptions::LINEAR), size, used, hash: image_hash },
        }
    }

    fn recycle(&mut self, s: Slot) {
        if self.spare.len() >= SPARE_SLOTS {
            self.spare.remove(0);
        }
        self.spare.push(s);
    }

    pub(super) fn finish(&mut self, ctx: &egui::Context, painter: &egui::Painter, jobs: Vec<GlowJob>, atlas_size: [usize; 2], ppp: f32) {
        let now = ctx.input(|i| i.time);
        if !jobs.is_empty() {
            let uvs: Vec<Rect> = jobs.iter().flat_map(|j| j.quads.iter().map(|q| q.1)).collect();
            let atlas = self.atlas(ctx, atlas_size, &uvs);
            for j in jobs {
                let Some((img, rect)) = rasterize_glow(&atlas, &j, ppp, &mut self.scratch) else { continue };
                let old = self.textures.remove(&j.key).map(|(s, _)| s);
                let slot = self.upload(ctx, old, img, "lyric-glow");
                let mut mesh = Mesh::with_texture(slot.tex.id());
                mesh.add_rect_with_uv(rect, slot.uv(), j.color);
                self.textures.insert(j.key, (slot, now));
                painter.set(j.layer, Shape::mesh(mesh));
            }
        }
        let gone: Vec<Slot> = (self.textures.extract_if(|_, (_, used)| now - *used >= 1.0).map(|(_, (s, _))| s))
            .chain(self.lines.extract_if(|_, (.., used)| now - *used >= 4.0).map(|(_, (s, ..))| s))
            .collect();
        for s in gone {
            self.recycle(s);
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) fn blurred_line(
        &mut self,
        ctx: &egui::Context,
        key: (u64, usize),
        radius: f32,
        words: &[(&Word, f32)],
        texts: &[(Arc<Galley>, Pos2, f32)],
        pills: &[(Rect, f32)],
        scale: f32,
        atlas_size: [usize; 2],
        ppp: f32,
    ) -> Option<(TextureId, Rect, Rect)> {
        if dev_off("blur") {
            return None;
        }
        let now = ctx.input(|i| i.time);
        let weights: Vec<u8> = words.iter().map(|(_, a)| (a.clamp(0.0, 1.0) * 255.0).round() as u8)
            .chain(texts.iter().map(|(_, _, a)| (a.clamp(0.0, 1.0) * 255.0).round() as u8))
            .chain(pills.iter().map(|(_, a)| (a.clamp(0.0, 1.0) * 255.0).round() as u8))
            .collect();
        let step = ((radius * 4.0) as i32, (scale * 1000.0).round() as i32, hash(&weights));
        if let Some((s, r, rect, used)) = self.lines.get_mut(&key)
            && *r == step
        {
            *used = now;
            return Some((s.tex.id(), *rect, s.uv()));
        }
        let uv_of = |min: [u16; 2], max: [u16; 2]| {
            Rect::from_min_max(
                pos2(min[0] as f32 / atlas_size[0] as f32, min[1] as f32 / atlas_size[1] as f32),
                pos2(max[0] as f32 / atlas_size[0] as f32, max[1] as f32 / atlas_size[1] as f32),
            )
        };
        let mut quads = Vec::new();
        let push_galley = |g: &Galley, origin: Pos2, weight: f32, quads: &mut Vec<(Rect, Rect, usize, usize, f32)>| {
            for row in &g.rows {
                for gl in &row.row.glyphs {
                    let uv = gl.uv_rect;
                    if uv.is_nothing() {
                        continue;
                    }
                    let r = Rect::from_min_size(origin + row.pos.to_vec2() + gl.pos.to_vec2() + uv.offset, uv.size);
                    quads.push((r, uv_of(uv.min, uv.max), 0, 1, weight));
                }
            }
        };
        for (w, weight) in words {
            let g = ctx.fonts_mut(|fv| fv.layout_no_wrap(w.text.clone(), w.font.clone(), Color32::WHITE));
            push_galley(&g, w.pos, *weight, &mut quads);
        }
        for (g, at, weight) in texts {
            push_galley(g, *at, *weight, &mut quads);
        }
        if quads.is_empty() {
            return None;
        }
        let uvs: Vec<Rect> = quads.iter().map(|q| q.1).collect();
        let atlas = self.atlas(ctx, atlas_size, &uvs);
        let bounds = quads.iter().fold(Rect::NOTHING, |a, q| a.union(q.0));
        let job = GlowJob {
            key: (0, 0, 0, 0),
            quads,
            word: bounds,
            look: WordLook { opacity: 1.0, start: 2.0, end: 3.0, glow: 0.0, glow_alpha: 0.0 },
            by_letter: false,
            rtl: false,
            radius: radius * 2.0,
            pills: pills.to_vec(),
            color: Color32::WHITE,
            layer: ShapeIdx(0),
        };
        let (img, content_rect) = rasterize_glow(&atlas, &job, ppp * scale, &mut self.scratch)?;
        let old = self.lines.remove(&key).map(|(s, ..)| s);
        let slot = self.upload(ctx, old, img, "lyric-blur");
        let out = (slot.tex.id(), content_rect, slot.uv());
        self.lines.insert(key, (slot, step, content_rect, now));
        Some(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slots_are_rewritten_in_place() {
        let ctx = egui::Context::default();
        let img = |w: usize, h: usize| ColorImage::filled([w, h], Color32::WHITE);
        let mut g = Glow::default();
        let a = g.upload(&ctx, None, img(100, 40), "t");
        assert_eq!(a.size, [128, 64]);
        let id = a.tex.id();
        let b = g.upload(&ctx, Some(a), img(60, 30), "t");
        assert_eq!((b.tex.id(), b.used), (id, [60, 30]));
        assert_eq!(b.uv().max, pos2(60.0 / 128.0, 30.0 / 64.0));
        let c = g.upload(&ctx, Some(b), img(120, 60), "t");
        assert_eq!(c.tex.id(), id);
        let d = g.upload(&ctx, Some(c), img(300, 40), "t");
        assert_ne!(d.tex.id(), id);
        assert_eq!(g.spare.len(), 1);
        let e = g.upload(&ctx, None, img(90, 50), "t");
        assert_eq!(e.tex.id(), id);
        let f = g.upload(&ctx, Some(d), img(10, 10), "t");
        assert_eq!(f.size, [64, 64]);
    }
}
