//! nuvx's icon set: SF Symbols exported from GlyphStudio (Semibold, as the Android tab
//! bar draws them), kept as vector paths and rasterised at the exact pixel size they're
//! drawn at, then tinted. Each path carries its animation group, so a symbol can bounce
//! layer by layer with GlyphStudio's own curves.

use egui::{Color32, ColorImage, Id, Pos2, Rect, TextureHandle, TextureOptions, Ui, pos2, vec2};
use std::sync::Arc;
use tiny_skia::{BlendMode, FillRule, Paint, PathBuilder, Pixmap, Transform};

/// An SVG as written by the GlyphStudio export (or any single-colour SVG of M/L/C/Z paths).
pub type Glyph = &'static str;

pub const SHUFFLE: Glyph = include_str!("../../assets/icon/glyphs/shuffle.svg");
pub const PREVIOUS: Glyph = include_str!("../../assets/icon/glyphs/prev.svg");
pub const PLAY: Glyph = include_str!("../../assets/icon/glyphs/play.svg");
pub const PAUSE: Glyph = include_str!("../../assets/icon/glyphs/pause.svg");
pub const NEXT: Glyph = include_str!("../../assets/icon/glyphs/next.svg");
pub const REPEAT: Glyph = include_str!("../../assets/icon/glyphs/repeat.svg");
pub const REPEAT_ONE: Glyph = include_str!("../../assets/icon/glyphs/repeat_one.svg");
pub const STAR: Glyph = include_str!("../../assets/icon/glyphs/star.svg");
pub const STAR_FILLED: Glyph = include_str!("../../assets/icon/glyphs/star_filled.svg");
pub const LYRICS: Glyph = include_str!("../../assets/icon/glyphs/lyrics.svg");
pub const LYRICS_OFF: Glyph = include_str!("../../assets/icon/glyphs/lyrics_off.svg");
pub const QUEUE: Glyph = include_str!("../../assets/icon/glyphs/queue.svg");
pub const MAXIMIZE_2: Glyph = include_str!("../../assets/icon/glyphs/maximize2.svg");
pub const VOLUME: [Glyph; 4] = [
    include_str!("../../assets/icon/glyphs/volume_0.svg"),
    include_str!("../../assets/icon/glyphs/volume_1.svg"),
    include_str!("../../assets/icon/glyphs/volume_2.svg"),
    include_str!("../../assets/icon/glyphs/volume_3.svg"),
];

pub const HOME: Glyph = include_str!("../../assets/icon/glyphs/home.svg");
pub const EXPLORE: Glyph = include_str!("../../assets/icon/glyphs/explore.svg");
pub const LIBRARY: Glyph = include_str!("../../assets/icon/glyphs/library.svg");
pub const SEARCH: Glyph = include_str!("../../assets/icon/glyphs/search.svg");
pub const DOWNLOAD: Glyph = include_str!("../../assets/icon/glyphs/download.svg");
pub const SETTINGS: Glyph = include_str!("../../assets/icon/glyphs/settings.svg");
pub const PERSON_CIRCLE: Glyph = include_str!("../../assets/icon/glyphs/person_circle.svg");
pub const SPARKLES: Glyph = include_str!("../../assets/icon/glyphs/sparkles.svg");
pub const PERSON: Glyph = include_str!("../../assets/icon/glyphs/person.svg");
pub const PLAY_OUTLINE: Glyph = include_str!("../../assets/icon/glyphs/play_outline.svg");
pub const PENCIL: Glyph = include_str!("../../assets/icon/glyphs/pencil.svg");
pub const PAINTBRUSH: Glyph = include_str!("../../assets/icon/glyphs/paintbrush.svg");
pub const WIFI: Glyph = include_str!("../../assets/icon/glyphs/wifi.svg");
pub const IMPORT: Glyph = include_str!("../../assets/icon/glyphs/import.svg");
pub const KEYBOARD: Glyph = include_str!("../../assets/icon/glyphs/keyboard.svg");
pub const HISTORY: Glyph = include_str!("../../assets/icon/glyphs/history.svg");
/// GlyphStudio's broadcast symbol, as Android's Radio tab; it carries Variable Color's layer order.
pub const RADIO: Glyph = include_str!("../../assets/icon/glyphs/radio.svg");
/// Beside the station on air, pulsing while it plays (as Android's).
pub const WAVEFORM: Glyph = include_str!("../../assets/icon/glyphs/waveform.svg");
/// Brand marks (Simple Icons, CC0), drawn in one colour like every other glyph.
pub const SPOTIFY: Glyph = include_str!("../../assets/icon/glyphs/spotify.svg");
pub const DISCORD: Glyph = include_str!("../../assets/icon/glyphs/discord.svg");
pub const YOUTUBE_MUSIC: Glyph = include_str!("../../assets/icon/glyphs/youtube_music.svg");
/// The nuvx mark (white, 880×360).
pub const MARK: Glyph = include_str!("../../assets/icon/mark.svg");

/// The box the sidebar's section symbols fit in.
pub const APPLE_GLYPH: f32 = 20.0;
/// The box the smaller utility symbols fit in.
pub const LUCIDE_GLYPH: f32 = 17.0;

struct Layer {
    path: tiny_skia::Path,
    alpha: f32,
    group: u32,
    erase: bool,
}

struct Shape {
    view: [f32; 4],
    groups: u32,
    layers: Vec<Layer>,
    /// Variable Color's layer order (`data-variable`), first lit first.
    variable: Vec<usize>,
}

fn attr<'a>(tag: &'a str, name: &str) -> Option<&'a str> {
    let i = tag.find(&format!(" {name}=\""))? + name.len() + 3;
    Some(&tag[i..i + tag[i..].find('"')?])
}

fn parse(svg: &str) -> Option<Shape> {
    let root = &svg[..svg.find('>')?];
    let view: Vec<f32> = attr(root, "viewBox")?.split_whitespace().map(|x| x.parse().ok()).collect::<Option<_>>()?;
    let mut layers = Vec::new();
    for tag in svg.split("<path").skip(1) {
        let tag = &tag[..tag.find('>')?];
        layers.push(Layer {
            path: path(attr(tag, "d")?)?,
            alpha: attr(tag, "fill-opacity").and_then(|a| a.parse().ok()).unwrap_or(1.0),
            group: attr(tag, "data-group").and_then(|g| g.parse().ok()).unwrap_or(0),
            erase: attr(tag, "data-erase").is_some(),
        });
    }
    let groups = attr(root, "data-groups").and_then(|g| g.parse().ok()).unwrap_or(1);
    let variable = attr(root, "data-variable").map_or_else(Vec::new, |v| v.split_whitespace().filter_map(|i| i.parse().ok()).collect());
    Some(Shape { view: view.try_into().ok()?, groups, layers, variable })
}

fn number(b: &[u8], i: &mut usize) -> Option<f32> {
    let start = *i;
    if matches!(b.get(*i), Some(b'-' | b'+')) {
        *i += 1;
    }
    let mut dot = false;
    while let Some(&c) = b.get(*i) {
        match c {
            b'0'..=b'9' => {}
            b'.' if !dot => dot = true,
            b'e' | b'E' => {
                *i += 1;
                if matches!(b.get(*i), Some(b'-' | b'+')) {
                    *i += 1;
                }
                continue;
            }
            _ => break,
        }
        *i += 1;
    }
    std::str::from_utf8(&b[start..*i]).ok()?.parse().ok()
}

/// Absolute M/L/C/Z path data, which is all the exports use.
fn path(d: &str) -> Option<tiny_skia::Path> {
    let b = d.as_bytes();
    let mut pb = PathBuilder::new();
    let (mut i, mut cmd) = (0, b'M');
    let mut n = [0f32; 6];
    loop {
        while matches!(b.get(i), Some(c) if c.is_ascii_whitespace() || *c == b',') {
            i += 1;
        }
        let Some(&c) = b.get(i) else { break };
        if c.is_ascii_alphabetic() {
            cmd = c;
            i += 1;
            if c == b'Z' {
                pb.close();
                continue;
            }
        }
        let args = match cmd {
            b'M' | b'L' => 2,
            b'C' => 6,
            _ => return None,
        };
        for v in n.iter_mut().take(args) {
            while matches!(b.get(i), Some(c) if c.is_ascii_whitespace() || *c == b',') {
                i += 1;
            }
            *v = number(b, &mut i)?;
        }
        match cmd {
            b'M' => {
                pb.move_to(n[0], n[1]);
                cmd = b'L';
            }
            b'L' => pb.line_to(n[0], n[1]),
            _ => pb.cubic_to(n[0], n[1], n[2], n[3], n[4], n[5]),
        }
    }
    pb.finish()
}

fn shape(ui: &Ui, glyph: Glyph) -> Option<Arc<Shape>> {
    // Each const has its own address, so it doubles as the cache key.
    let id = Id::unique(("nuvx-glyph-shape", glyph.as_ptr()));
    if let Some(s) = ui.ctx().data(|d| d.get_temp::<Arc<Shape>>(id)) {
        return Some(s);
    }
    let s = Arc::new(parse(glyph)?);
    ui.ctx().data_mut(|d| d.insert_temp(id, s.clone()));
    Some(s)
}

/// One em of an SF Symbol, in its own units (what GlyphStudio's offsets are measured in).
const EM: f32 = 2048.0;
/// The room a bounce needs above and below the symbol, in ems.
const LIFT_ROOM: f32 = 0.26;

/// `shape` in white on a `w`×`h` pixmap, `k` pixels per unit, centred, each group raised by
/// `lift(group)` ems and each layer's opacity scaled by `alpha(layer)`.
fn raster(shape: &Shape, w: u32, h: u32, k: f32, lift: impl Fn(u32) -> f32, alpha: impl Fn(usize) -> f32) -> Option<ColorImage> {
    let mut pix = Pixmap::new(w, h)?;
    let [vx, vy, vw, vh] = shape.view;
    let ox = (w as f32 - vw * k) / 2.0 - vx * k;
    let oy = (h as f32 - vh * k) / 2.0 - vy * k;
    for (i, l) in shape.layers.iter().enumerate() {
        let mut paint = Paint::default();
        paint.anti_alias = true;
        if l.erase {
            paint.blend_mode = BlendMode::Clear;
        } else {
            paint.set_color_rgba8(255, 255, 255, ((l.alpha * alpha(i)).clamp(0.0, 1.0) * 255.0).round() as u8);
        }
        let t = Transform::from_row(k, 0.0, 0.0, k, ox, oy - lift(l.group) * EM * k);
        pix.fill_path(&l.path, &paint, FillRule::Winding, t, None);
    }
    Some(ColorImage::from_rgba_premultiplied([w as usize, h as usize], pix.data()))
}

const OPTIONS: TextureOptions = TextureOptions {
    magnification: egui::TextureFilter::Linear,
    minification: egui::TextureFilter::Linear,
    wrap_mode: egui::TextureWrapMode::ClampToEdge,
    mipmap_mode: None,
};

/// Draws `glyph` fitted (aspect kept) and centred in `rect`, tinted; `bounce` is seconds into a
/// bounce, `pulse` seconds into Variable Color's loop.
fn paint(ui: &Ui, rect: Rect, glyph: Glyph, tint: Color32, bounce: Option<(Id, f32)>, pulse: Option<(Id, f32)>) {
    if tint.a() == 0 {
        return;
    }
    let Some(shape) = shape(ui, glyph) else { return };
    let ppp = ui.ctx().pixels_per_point();
    let [_, _, vw, vh] = shape.view;
    let k = (rect.width() / vw).min(rect.height() / vh) * ppp;
    let pad = if bounce.is_some() { (LIFT_ROOM * EM * k).ceil() } else { 0.0 };
    let (w, h) = ((vw * k).ceil() as u32, (vh * k).ceil() as u32 + 2 * pad as u32);
    let key = match (bounce, pulse) {
        (Some((id, _)), _) => id.with("nuvx-glyph-bounce-tex"),
        (None, Some((id, _))) => id.with(("nuvx-glyph-pulse-tex", w, h)),
        (None, None) => Id::unique(("nuvx-glyph", glyph.as_ptr(), w, h)),
    };
    let cached = ui.ctx().data(|d| d.get_temp::<TextureHandle>(key));
    let tex = match (cached, bounce.or(pulse)) {
        (Some(t), None) => t,
        (cached, _) => {
            let lift = |g| bounce.map_or(0.0, |(_, t)| bounce_lift(t, g, shape.groups));
            let alpha = |i| pulse.map_or(1.0, |(_, t)| variable_alpha(t, i, &shape.variable));
            let Some(img) = raster(&shape, w.max(1), h.max(1), k, lift, alpha) else { return };
            match cached {
                Some(mut t) => {
                    t.set(img, OPTIONS);
                    t
                }
                None => {
                    let t = ui.ctx().load_texture("nuvx-glyph", img, OPTIONS);
                    ui.ctx().data_mut(|d| d.insert_temp(key, t.clone()));
                    t
                }
            }
        }
    };
    // Whole pixels, so the edges stay as sharp as they were rasterised.
    let size = vec2(w as f32, h as f32) / ppp;
    let min = ((rect.center() - size / 2.0) * ppp).round() / ppp;
    let dst = Rect::from_min_size(min, size);
    ui.painter().image(tex.id(), dst, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), tint);
}

/// Draws `glyph` centred on `center`, fitted in a `size` square, tinted `tint`.
pub fn draw(ui: &Ui, center: Pos2, size: f32, glyph: Glyph, tint: Color32) {
    paint(ui, Rect::from_center_size(center, vec2(size, size)), glyph, tint, None, None);
}

/// Draws `glyph` fitted inside `rect` (for wide marks).
pub fn draw_in(ui: &Ui, rect: Rect, glyph: Glyph, tint: Color32) {
    paint(ui, rect, glyph, tint, None, None);
}

/// Like [draw], with GlyphStudio's Variable Color running on repeat while `active`:
/// the layers light one after another, as Android's Radio heading does while on air.
pub fn draw_pulsing(ui: &Ui, center: Pos2, size: f32, glyph: Glyph, tint: Color32, id: Id, active: bool) {
    let t = active.then(|| {
        ui.ctx().request_repaint();
        ui.input(|i| i.time) as f32
    });
    paint(ui, Rect::from_center_size(center, vec2(size, size)), glyph, tint, None, t.map(|t| (id, t)));
}

/// Like [draw], but plays the bounce last started with [bounce] for `id`.
pub fn draw_bouncing(ui: &Ui, center: Pos2, size: f32, glyph: Glyph, tint: Color32, id: Id) {
    let start = ui.ctx().data(|d| d.get_temp::<f64>(id.with("nuvx-glyph-bounce")));
    let t = start.map(|s| (ui.input(|i| i.time) - s) as f32).filter(|t| (0.0..BOUNCE_SECS).contains(t));
    if t.is_some() {
        ui.ctx().request_repaint();
    }
    paint(ui, Rect::from_center_size(center, vec2(size, size)), glyph, tint, t.map(|t| (id, t)), None);
}

/// Starts the symbol drawn by [draw_bouncing] with `id` bouncing, layer by layer.
pub fn bounce(ctx: &egui::Context, id: Id) {
    let now = ctx.input(|i| i.time);
    ctx.data_mut(|d| d.insert_temp(id.with("nuvx-glyph-bounce"), now));
}

// GlyphStudio's Bounce (Up, ByLayer): the offset goes 1 → 1.25 → 0.9094 → 1 over three eased
// segments, each animation group starting a little after the one before.
const BOUNCE_UP: f32 = 0.21667;
const BOUNCE_LAST: f32 = 0.26667;
const BOUNCE_SECS: f32 = 2.0 * BOUNCE_UP + BOUNCE_LAST;
const BOUNCE_A: [f32; 4] = [0.55, 0.0, 0.67, 1.0];
const BOUNCE_B: [f32; 4] = [0.45, 0.0, 0.67, 1.0];
const BOUNCE_C: [f32; 4] = [0.25, 0.0, 0.55, 1.0];

/// Cubic-bezier easing (x1, y1, x2, y2), solving x by bisection as GlyphStudio does.
fn ease([x1, y1, x2, y2]: [f32; 4], p: f32) -> f32 {
    let x = p.clamp(0.0, 1.0);
    if x == 0.0 || x == 1.0 {
        return x;
    }
    let (mut lo, mut hi, mut t) = (0.0f32, 1.0f32, x);
    for _ in 0..24 {
        let u = 1.0 - t;
        let bx = 3.0 * u * u * t * x1 + 3.0 * u * t * t * x2 + t * t * t;
        if bx < x { lo = t } else { hi = t }
        t = (lo + hi) / 2.0;
    }
    let u = 1.0 - t;
    3.0 * u * u * t * y1 + 3.0 * u * t * t * y2 + t * t * t
}

fn segment(c: [f32; 4], from: f32, to: f32, t0: f32, dur: f32, t: f32) -> f32 {
    from + (to - from) * ease(c, (t - t0) / dur)
}

/// How far (ems, up) group `group` of `groups` is raised `t` seconds into a bounce.
fn bounce_lift(t: f32, group: u32, groups: u32) -> f32 {
    let d = group as f32 * 0.05f32.min(0.15 / groups.max(1) as f32);
    let tl = t - d;
    let v = if tl <= 0.0 {
        1.0
    } else if tl < BOUNCE_UP {
        segment(BOUNCE_A, 1.0, 1.25, 0.0, BOUNCE_UP, tl)
    } else if tl < 2.0 * BOUNCE_UP {
        segment(BOUNCE_B, 1.25, 0.9094, BOUNCE_UP, BOUNCE_UP, tl)
    } else {
        segment(BOUNCE_C, 0.9094, 1.0, 2.0 * BOUNCE_UP, BOUNCE_LAST - d, tl)
    };
    v - 1.0
}

// GlyphStudio's Variable Color (Iterative): in `order`, each layer rises from 30% to full over
// one slot and falls back over the next (smoothstep), the loop lasting a slot more than the layers.
const VARIABLE_SLOT: f32 = 1.0 / 3.0;
const VARIABLE_DIM: f32 = 0.3;

/// Layer `layer`'s opacity `t` seconds into Variable Color's loop (1 for layers outside `order`).
fn variable_alpha(t: f32, layer: usize, order: &[usize]) -> f32 {
    let Some(k) = order.iter().position(|&l| l == layer) else { return 1.0 };
    let t = t.rem_euclid(VARIABLE_SLOT * (order.len() + 1) as f32);
    let f = t / VARIABLE_SLOT - k as f32;
    let f = if f > 1.0 { 2.0 - f } else { f }.clamp(0.0, 1.0);
    VARIABLE_DIM + (1.0 - VARIABLE_DIM) * f * f * (3.0 - 2.0 * f)
}

/// How many of the speaker's three arcs are lit at `volume`, as the desktop app counts them.
pub fn volume_step(volume: f32) -> usize {
    if volume <= 0.0 {
        0
    } else if volume < 1.0 / 3.0 {
        1
    } else if volume < 2.0 / 3.0 {
        2
    } else {
        3
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_glyph_parses_and_draws() {
        let all = [
            SHUFFLE, PREVIOUS, PLAY, PAUSE, NEXT, REPEAT, REPEAT_ONE, STAR, STAR_FILLED, LYRICS, LYRICS_OFF, QUEUE,
            MAXIMIZE_2, HOME, EXPLORE, LIBRARY, SEARCH, DOWNLOAD, SETTINGS, PERSON_CIRCLE, SPARKLES, MARK, SPOTIFY, DISCORD,
            YOUTUBE_MUSIC, PERSON, PLAY_OUTLINE, PENCIL, PAINTBRUSH, WIFI, IMPORT, KEYBOARD, HISTORY, RADIO, WAVEFORM,
        ];
        for g in all.into_iter().chain(VOLUME) {
            let s = parse(g).unwrap_or_else(|| panic!("{}", &g[..80]));
            assert!(!s.layers.is_empty());
            let img = raster(&s, 24, 24, 24.0 / s.view[2].max(s.view[3]), |_| 0.0, |_| 1.0).unwrap();
            assert!(img.pixels.iter().any(|p| p.a() > 200), "{}", &g[..80]);
        }
    }

    #[test]
    fn variable_color_matches_glyphstudio() {
        // Sampled from GlyphStudio's own VariableColor on dot.radiowaves.left.and.right.
        let order = parse(RADIO).unwrap().variable;
        assert_eq!(order, [3, 2, 1, 0]);
        let samples = [(0.0, [0.3, 0.3, 0.3, 0.3]), (0.167, [0.3, 0.3, 0.3, 0.65]), (0.5, [0.3, 0.3, 0.65, 0.65]), (1.0, [0.3, 1.0, 0.3, 0.3]), (1.125, [0.522, 0.778, 0.3, 0.3])];
        for (t, want) in samples {
            for (layer, w) in want.iter().enumerate() {
                assert!((variable_alpha(t, layer, &order) - w).abs() < 0.01, "t={t} layer={layer}");
            }
        }
        assert_eq!(variable_alpha(0.5, 0, &[]), 1.0);
    }

    #[test]
    fn bounce_rises_then_settles() {
        assert_eq!(bounce_lift(0.0, 0, 1), 0.0);
        assert!((bounce_lift(BOUNCE_UP, 0, 1) - 0.25).abs() < 1e-3);
        assert!(bounce_lift(2.0 * BOUNCE_UP, 0, 1) < 0.0);
        assert!(bounce_lift(BOUNCE_SECS, 2, 3).abs() < 1e-4);
        // Later groups lag behind.
        assert!(bounce_lift(0.1, 2, 3) < bounce_lift(0.1, 0, 3));
    }
}
