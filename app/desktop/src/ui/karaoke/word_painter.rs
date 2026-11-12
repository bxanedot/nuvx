use super::*;
struct GlyphQuad {
    rect: Rect,
    uv: Rect,
    letter: usize,
    letters: usize,
}

fn word_glyphs(ctx: &egui::Context, w: &Word, lit: &LitState, cfg: &Cfg, wob: Option<(f32, f32, f32)>, atlas: [usize; 2]) -> Vec<GlyphQuad> {
    let g = ctx.fonts_mut(|fv| fv.layout_no_wrap(w.text.clone(), w.font.clone(), Color32::WHITE));
    let n: usize = g.rows.iter().map(|r| r.row.glyphs.len()).sum();
    let waves = if w.letters && lit.live && lit.exit.is_none() { Some(letter_waves(n, w.dur.max(0.0), cfg.wave_duration)) } else { None };
    let e = lit.t - w.start;
    let em = w.font.size;
    let mut out = Vec::with_capacity(n);
    let mut k = 0;
    for row in &g.rows {
        for gl in &row.row.glyphs {
            let idx = k;
            k += 1;
            let uv = gl.uv_rect;
            if uv.is_nothing() {
                continue;
            }
            let mut r = Rect::from_min_size(w.pos + row.pos.to_vec2() + gl.pos.to_vec2() + uv.offset, uv.size);
            if let Some(waves) = &waves {
                let (lift, s) = letter_float(e, waves[idx], w.long, cfg);
                let c = pos2(w.pos.x + row.pos.x + gl.pos.x + gl.advance_width * 0.5, r.center().y);
                r = Rect::from_min_max(c + (r.min - c) * s, c + (r.max - c) * s).translate(vec2(0.0, -lift * em));
            }
            if let Some((cx, tx, sx)) = wob {
                let f = |x: f32| cx + (x - cx) * sx + tx;
                r = Rect::from_x_y_ranges(f(r.left())..=f(r.right()), r.y_range());
            }
            let uvr = Rect::from_min_max(
                pos2(uv.min[0] as f32 / atlas[0] as f32, uv.min[1] as f32 / atlas[1] as f32),
                pos2(uv.max[0] as f32 / atlas[0] as f32, uv.max[1] as f32 / atlas[1] as f32),
            );
            out.push(GlyphQuad { rect: r, uv: uvr, letter: idx, letters: n });
        }
    }
    out
}

pub(super) fn paint_words(
    painter: &egui::Painter,
    ctx: &egui::Context,
    cfg: &Cfg,
    words: &[Word],
    groups: &[Group],
    xf: &Xf,
    lit: &LitState,
    dim: f32,
    opacity: f32,
    atlas: [usize; 2],
    glow: &mut Vec<GlowJob>,
    key: (u64, usize, u32),
) {
    let no_cut: [f32; 0] = [];
    let mut base = Mesh::with_texture(TextureId::default());
    let mut per_word = Vec::with_capacity(words.len());
    for w in words {
        let gr = &groups[w.group];
        let wob = if cfg.wobble && gr.wobbles && lit.live && lit.exit.is_none() {
            let first = &words[gr.first];
            let (tx, sx) = wobble(lit.t - first.start);
            let ws = &words[gr.words.clone()];
            let l = ws.iter().map(|w| w.pos.x).fold(f32::INFINITY, f32::min);
            let r = ws.iter().map(|w| w.pos.x + w.w).fold(f32::NEG_INFINITY, f32::max);
            ((sx - 1.0).abs() > 1e-4 || tx.abs() > 1e-4).then_some(((l + r) * 0.5, tx * first.font.size, sx))
        } else {
            None
        };
        let quads = word_glyphs(ctx, w, lit, cfg, wob, atlas);
        let dim_c = if w.bg { dim * 0.78 } else { dim } * opacity;
        for q in &quads {
            push_quad(&mut base, xf.rect(q.rect), q.uv, theme::with_alpha(Color32::WHITE, dim_c), &|_| 1.0, &no_cut);
        }
        per_word.push((quads, wob));
    }
    painter.add(Shape::mesh(base));
    for (wi, (w, (quads, wob))) in words.iter().zip(per_word).enumerate() {
        let look = word_look(w, lit, cfg);
        if look.opacity <= 0.0 || look.end <= 0.0 {
            continue;
        }
        let word_box = {
            let (cx, tx, sx) = wob.unwrap_or((0.0, 0.0, 1.0));
            let f = |x: f32| cx + (x - cx) * sx + tx;
            xf.rect(Rect::from_min_size(pos2(f(w.pos.x), w.pos.y), vec2(w.w * sx, w.h)))
        };
        let mut m = Mesh::with_texture(TextureId::default());
        let color = theme::with_alpha(cfg.active, look.opacity * opacity);
        let mut screen_quads = Vec::with_capacity(quads.len());
        for q in &quads {
            let r = xf.rect(q.rect);
            let (alpha, cuts) = lit_gradient(&r, q.letter, q.letters, word_box, &look, w.letters, w.rtl);
            push_quad(&mut m, r, q.uv, color, alpha.as_ref(), &cuts);
            screen_quads.push((r, q.uv, q.letter, q.letters, 1.0));
        }
        if look.glow > 0.05 && look.glow_alpha >= GLOW_INVISIBLE && !dev_off("glow") {
            glow.push(GlowJob {
                key: (key.0, key.1, key.2, wi as u32),
                quads: screen_quads,
                word: word_box,
                by_letter: w.letters,
                rtl: w.rtl,
                radius: look.glow * xf.scale,
                pills: Vec::new(),
                color: theme::with_alpha(cfg.glow, look.glow_alpha * look.opacity * opacity),
                look,
                layer: painter.add(Shape::Noop),
            });
        }
        if !m.is_empty() {
            painter.add(Shape::mesh(m));
        }
    }
}

