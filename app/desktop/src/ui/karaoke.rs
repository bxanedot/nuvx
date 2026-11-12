use std::sync::Arc;

mod edge_fade;
use edge_fade::fade_edges;
mod glow_raster;
mod glow;
mod transform;
mod instrumental;
mod motion;
mod model;
mod word_look;
mod word_mesh;
mod word_painter;
use word_painter::paint_words;
mod flow;
use flow::{Align, Tok, flow, line_synced_parts, split_part};
mod block;
mod config;
use block::build_block;
use config::{Cfg, GLOW_INVISIBLE};
#[cfg(test)]
use flow::{Flow, text_size};
use word_mesh::{lit_gradient, push_quad};
use word_look::{LitState, WordLook, word_look};
use model::{Block, Deco, DecoBody, Group, Layout, Word};
use motion::{glow_radius, letter_float, letter_waves, lerp, scroll_duration, transform_origin, wobble};
#[cfg(test)]
use instrumental::gap_times;
use instrumental::paint_instrumental;
use glow::Glow;
use transform::{Xf, paint_galley};

use egui::epaint::text::FontsView;
use egui::layers::ShapeIdx;
#[cfg(test)]
use egui::ColorImage;
use egui::{Color32, FontId, Galley, Mesh, Pos2, Rect, Shape, TextureId, Vec2, pos2, vec2};

use crate::lyrics::{Line, Lyrics, Part, Sync};
use crate::preset::{BlurScope, LyricStyle};
use crate::theme;

#[derive(Clone, Copy, Debug)]
pub struct Bezier(pub f32, pub f32, pub f32, pub f32);

impl Bezier {
    pub fn at(self, x: f32) -> f32 {
        if x <= 0.0 {
            return 0.0;
        }
        if x >= 1.0 {
            return 1.0;
        }
        let Bezier(x1, y1, x2, y2) = self;
        let curve = |t: f32, a: f32, b: f32| {
            let u = 1.0 - t;
            3.0 * u * u * t * a + 3.0 * u * t * t * b + t * t * t
        };
        let slope = |t: f32, a: f32, b: f32| {
            let u = 1.0 - t;
            3.0 * u * u * a + 6.0 * u * t * (b - a) + 3.0 * t * t * (1.0 - b)
        };
        let mut t = x;
        for _ in 0..8 {
            let e = curve(t, x1, x2) - x;
            if e.abs() < 1e-6 {
                return curve(t, y1, y2);
            }
            let d = slope(t, x1, x2);
            if d.abs() < 1e-6 {
                break;
            }
            t = (t - e / d).clamp(0.0, 1.0);
        }
        let (mut lo, mut hi) = (0.0f32, 1.0f32);
        t = x;
        for _ in 0..40 {
            let v = curve(t, x1, x2);
            if (v - x).abs() < 1e-6 {
                break;
            }
            if v < x {
                lo = t;
            } else {
                hi = t;
            }
            t = (lo + hi) * 0.5;
        }
        curve(t, y1, y2)
    }
}

pub const EASE: Bezier = Bezier(0.25, 0.1, 0.25, 1.0);
pub const EASE_OUT: Bezier = Bezier(0.0, 0.0, 0.58, 1.0);
pub const EASE_IN_OUT: Bezier = Bezier(0.42, 0.0, 0.58, 1.0);
pub const SCROLL_EASE: Bezier = Bezier(0.86, 0.0, 0.2, 1.0);
const DECO_EASE: Bezier = Bezier(0.25, 1.0, 0.5, 1.0);

pub const RICHSYNC_OFFSET: f64 = 0.150;
pub const LINE_OFFSET: f64 = 0.115;
const SCROLL_OFFSET: f64 = 0.5;
const EARLY_SCROLL: f64 = 0.54;
const ENDING_THRESHOLD: f64 = 0.5;
const TIME_JUMP: f64 = 0.5;
const SWIPE_LEAD: f64 = 0.1;
const SWIPE_DURATION: f64 = 1.6;
const SWIPE_START: (f32, f32) = (-0.2, 1.4);
const SWIPE_END: (f32, f32) = (-0.1, 1.5);
const GLOW_RATIO: f64 = 1.2;
const GLOW_MIN: f64 = 1.2;
const FADE_IN: f64 = 0.33;
const FADE_OUT: f64 = 0.5;
const SCALE_DURATION: f64 = 0.166;
const WOBBLE_DURATION: f64 = 1.0;
const LETTER_WAVE_CREST: f32 = 0.4;
const LONG_WORD: f64 = 1.5;
const LINE_SYNC_DELAY: f64 = 0.05;
const LONG_GROUP_CHARS: usize = 20;
const USER_SCROLL_RESUME: f64 = 25.0;
const DECO_ENTRY: f64 = 0.35;

pub enum Roman<'a> {
    Timed(&'a [Part], &'a str),
    Text(&'a str),
}

fn hash<T: std::hash::Hash>(v: T) -> u64 {
    use std::hash::Hasher;
    let mut h = std::collections::hash_map::DefaultHasher::new();
    v.hash(&mut h);
    h.finish()
}

#[derive(Clone, Copy)]
struct ScrollAnim {
    start: f64,
    delta: f32,
    active: usize,
}

#[derive(Clone, Copy)]
struct Shift {
    start: f64,
    delta: f32,
}

#[derive(Clone, Copy, Default)]
struct Trans {
    from: f32,
    to: f32,
    start: f64,
    dur: f64,
    delay: f64,
    init: bool,
}

impl Trans {
    fn value(&self, now: f64) -> f32 {
        if !self.init {
            return self.to;
        }
        let p = ((now - self.start - self.delay) / self.dur.max(1e-3)).clamp(0.0, 1.0) as f32;
        lerp(self.from, self.to, EASE.at(p))
    }

    fn set(&mut self, to: f32, now: f64, dur: f64, delay: f64) {
        if !self.init {
            *self = Trans { from: to, to, start: now, dur, delay, init: true };
        } else if (to - self.to).abs() > 1e-4 {
            let from = self.value(now);
            *self = Trans { from, to, start: now, dur, delay, init: true };
        }
    }

    fn moving(&self, now: f64) -> bool {
        self.init && (self.from - self.to).abs() > 1e-4 && now < self.start + self.delay + self.dur
    }
}

#[derive(Clone, Copy, Default)]
struct LineState {
    opacity: Trans,
    blur: Trans,
    selected: bool,
    exit: Option<f64>,
    exit_t: f64,
}

#[derive(Default)]
pub struct View {
    layout: Option<Layout>,
    states: Vec<LineState>,
    scroll_top: f32,
    scroll_set: bool,
    scrolls: Vec<ScrollAnim>,
    shifts: Vec<Vec<Shift>>,
    last_scroll: Vec<usize>,
    page: Option<(usize, i32)>,
    last_t: f64,
    last_wall: f64,
    user_until: f64,
    was_user: bool,
    glow: Glow,
    credits_focused: bool,
    shown_pass: u64,
}

pub struct Frame<'a> {
    pub lyrics: &'a Lyrics,
    pub key: u64,
    pub roman: Vec<Option<Roman<'a>>>,
    pub trans: Vec<Option<&'a str>>,
    pub t: f64,
    pub playing: bool,
    pub now: f64,
    pub rect: Rect,
    pub style: &'a LyricStyle,
    pub accent: Color32,
    pub centered: bool,
    pub scale: f32,
}

pub struct Out {
    pub seek: Option<f64>,
    pub animating: bool,
    pub user_scrolling: bool,
}

impl View {
    pub fn reset(&mut self) {
        *self = Self { glow: std::mem::take(&mut self.glow), ..Default::default() };
    }

    pub fn release_hidden(&mut self, pass: u64) {
        if self.shown_pass != 0 && self.shown_pass + 1 < pass {
            self.glow = Glow::default();
            self.shown_pass = 0;
        }
    }

    pub fn resume(&mut self) {
        self.user_until = 0.0;
    }

    pub fn text_glow(&mut self, ctx: &egui::Context, g: Arc<Galley>, at: Pos2, blur: f32) -> Option<(TextureId, Rect, Rect)> {
        let atlas = ctx.fonts(|fv| fv.font_image_size());
        let ppp = ctx.pixels_per_point();
        let key = (hash(("text-glow", at.x.round() as i32, at.y.round() as i32, g.job.text.as_str())), usize::MAX);
        self.glow.blurred_line(ctx, key, blur / 2.0, &[], &[(g, at, 1.0)], &[], 1.0, atlas, ppp)
    }

    pub fn show(&mut self, ui: &mut egui::Ui, f: Frame) -> Out {
        let rect = f.rect;
        let ctx = ui.ctx().clone();
        self.shown_pass = ctx.cumulative_pass_nr();
        let cfg = Cfg::new(f.style, f.accent, rect.width(), ctx.content_rect().width(), f.centered, f.scale);
        let layout_key = hash((f.key, rect.width().round() as i32, cfg.size.to_bits(), cfg.padding.to_bits(), cfg.max_scale().to_bits(), cfg.centered, &cfg.font, cfg.weight as u8));
        if self.layout.as_ref().is_none_or(|l| l.key != layout_key) {
            self.relayout(ui, &cfg, &f, layout_key, rect);
        }
        let layout = self.layout.as_ref().unwrap();
        let richsync = layout.richsync;
        let lines_n = layout.blocks.iter().filter(|b| b.line.is_some()).count();

        let t = f.t + if richsync { RICHSYNC_OFFSET } else { LINE_OFFSET };
        let jumped = (t - self.last_t - (f.now - self.last_wall)).abs() > TIME_JUMP || self.last_wall == 0.0;
        self.last_t = t;
        self.last_wall = f.now;

        let early = if f.playing { 2.0 } else { 0.0 };
        let mut animating = false;
        for i in 0..lines_n {
            let b = &layout.blocks[i];
            let next = layout.blocks.get(i + 1).filter(|n| n.line.is_some()).map_or(f64::INFINITY, |n| n.start);
            let end = next.max(b.start + b.dur + 0.05);
            let st = &mut self.states[i];
            let selected = t + early >= b.start && t < end;
            if selected && !st.selected {
                st.selected = true;
                st.exit = None;
            } else if !selected && st.selected {
                st.selected = false;
                let stale = end + FADE_OUT + 0.05;
                st.exit = if (f.playing || jumped) && t <= stale { Some(f.now) } else { None };
                st.exit_t = t;
            } else if !selected && jumped {
                st.exit = None;
            }
            if st.exit.is_some_and(|e| f.now - e > FADE_OUT.max(SCALE_DURATION)) {
                st.exit = None;
            }
            animating |= st.exit.is_some();
        }

        let view_h = rect.height();
        let scroll_time = t + SCROLL_OFFSET;
        let user = f.now < self.user_until;
        let mut active: Vec<usize> = Vec::new();
        let mut new_lyric = jumped || (self.was_user && !user);
        self.was_user = user;
        for i in 0..lines_n {
            let b = &layout.blocks[i];
            let next = layout.blocks.get(i + 1).filter(|n| n.line.is_some()).map_or(f64::INFINITY, |n| n.start);
            if scroll_time >= b.start - EARLY_SCROLL && (scroll_time < next || scroll_time < b.start + b.dur) {
                active.push(i);
                if !self.last_scroll.contains(&i) && scroll_time >= b.start {
                    new_lyric = true;
                }
            }
        }
        let active_real = !active.is_empty();
        let last_sung = (0..lines_n).rev().find(|&i| !layout.blocks[i].instrumental);
        let credits = layout.blocks.iter().position(|b| b.credits.is_some());
        let credits_focused = credits.is_some() && last_sung.is_some_and(|i| scroll_time >= layout.blocks[i].start + layout.blocks[i].dur);
        if credits_focused != self.credits_focused {
            self.credits_focused = credits_focused;
            new_lyric = true;
        }
        let line_h = cfg.size * 1.333;
        let sung_y = |i: usize| -> Option<f32> {
            let b = &layout.blocks[i];
            if b.height <= view_h || scroll_time < b.start {
                return None;
            }
            let mut timed = b.words.iter().filter(|w| !w.line_synced && !w.bg).peekable();
            Some(if timed.peek().is_none() {
                b.height * ((scroll_time - b.start) / b.dur.max(0.001)).clamp(0.0, 1.0) as f32
            } else {
                timed.filter(|w| w.start <= scroll_time).map(|w| w.pos.y).fold(0.0, f32::max)
            })
        };
        let page = active.last().and_then(|&i| sung_y(i).map(|y| (i, (y / line_h) as i32)));
        if page != self.page {
            self.page = page;
            new_lyric |= page.is_some();
        }
        let pos = |i: usize| layout.pad_top + layout.blocks[i].top;
        let max_scroll = (layout.content_h - view_h).max(0.0);
        if !user && (new_lyric || !self.scroll_set) && lines_n > 0 {
            if active.is_empty() {
                active.push(0);
            }
            let offset = view_h * cfg.target_ratio;
            let last = *active.last().unwrap();
            let sung: Vec<usize> = active.iter().copied().enumerate().filter(|&(k, i)| scroll_time < layout.blocks[i].start + layout.blocks[i].dur - ENDING_THRESHOLD || k == active.len() - 1).map(|(_, i)| i).collect();
            let anchor = sung
                .iter()
                .rev()
                .find(|&&i| layout.blocks[i].start <= scroll_time && scroll_time < layout.blocks[i].start + layout.blocks[i].dur)
                .or_else(|| sung.iter().rev().find(|&&i| layout.blocks[i].start <= scroll_time))
                .copied()
                .unwrap_or(sung[0]);
            let (first, lastl) = (sung[0], *sung.last().unwrap());
            let centre = sung.iter().map(|&i| pos(i) + layout.blocks[i].height / 2.0).sum::<f32>() / sung.len() as f32;
            let h = |i: usize| layout.blocks[i].height;
            let mut top = centre - offset;
            top = top.max(pos(lastl) + h(lastl) - view_h);
            top = top.min(pos(lastl)).min(pos(first));
            top = top.max(pos(anchor) + h(anchor) - view_h);
            top = top.min(pos(anchor));
            if let Some(y) = sung_y(anchor) {
                top = top.max(pos(anchor) + y + line_h * 2.0 - view_h);
            }
            if credits_focused && let (Some(c), Some(ls)) = (credits, last_sung) {
                top = (pos(c) + h(c) / 2.0 - offset).min(pos(ls));
            }
            let top = top.clamp(0.0, max_scroll);
            if !self.scroll_set {
                self.scroll_top = top;
                self.scroll_set = true;
            } else if (top - self.scroll_top).abs() > 2.0 {
                self.scrolls.push(ScrollAnim { start: f.now, delta: top - self.scroll_top, active: last });
                self.scroll_top = top;
            }
            self.last_scroll = active.clone();
        }

        let hovered = ui.rect_contains_pointer(rect);
        let wheel = if hovered { ui.input(|i| i.smooth_scroll_delta.y) } else { 0.0 };
        if wheel != 0.0 {
            self.scroll_top = (self.scroll_top - wheel).clamp(0.0, max_scroll);
            self.user_until = f.now + USER_SCROLL_RESUME;
            self.scrolls.clear();
        }
        let user = f.now < self.user_until;

        self.scrolls.retain(|a| f.now - a.start < 2.0);
        animating |= !self.scrolls.is_empty();
        for s in &mut self.shifts {
            s.retain(|a| f.now - a.start < DECO_ENTRY);
            animating |= !s.is_empty();
        }

        let painter = ui.painter_at(rect);
        let first_shape = ctx.graphics(|g| g.get(painter.layer_id()).map_or(ShapeIdx(0), |l| l.next_idx()));
        let origin_y = rect.top() - self.scroll_top + layout.pad_top;
        let x0 = rect.left() + cfg.pad.0;
        let line_w = rect.width() - cfg.pad.0 - cfg.pad.1;
        let atlas = ctx.fonts(|fv| fv.font_image_size());
        let ppp = ctx.pixels_per_point();
        let mut seek = None;
        let active_line = active.last().copied();
        let first_active = active.first().copied().filter(|_| active_real);
        let mut glow_work: Vec<GlowJob> = Vec::new();
        let pointer = ui.input(|i| i.pointer.hover_pos()).filter(|p| rect.contains(*p));

        for (i, b) in layout.blocks.iter().enumerate() {
            let mut dy = 0.0;
            for a in &self.scrolls {
                let rel = i as isize - a.active as isize;
                let d = scroll_duration(rel, a.delta);
                let p = ((f.now - a.start) / d).clamp(0.0, 1.0) as f32;
                dy += a.delta * (1.0 - SCROLL_EASE.at(p));
            }
            if let Some(shifts) = self.shifts.get(i) {
                for s in shifts {
                    let p = ((f.now - s.start) / DECO_ENTRY).clamp(0.0, 1.0) as f32;
                    dy += s.delta * (1.0 - DECO_EASE.at(p));
                }
            }
            let top = origin_y + b.top + dy;
            if top > rect.bottom() + 40.0 || top + b.height < rect.top() - 40.0 {
                continue;
            }
            let block_rect = Rect::from_min_size(pos2(x0, top), vec2(line_w, b.height));
            let content = pos2(x0 + cfg.size * 0.25, top);

            if let Some((text, font)) = &b.credits {
                let a = if credits_focused { 0.85 } else { 0.2 };
                let a = theme::anim(&ctx, ("lyr-credits", f.key), a, 0.35);
                let o = transform_origin(block_rect, b.align, content.x);
                let g = ctx.fonts_mut(|fv| fv.layout(text.clone(), font.clone(), Color32::WHITE, line_w - cfg.size * 0.5));
                let xf = Xf { origin: o, scale: cfg.scale, content };
                paint_galley(&painter, &g, pos2(0.0, cfg.padding), &xf, theme::with_alpha(Color32::WHITE, a), atlas);
                continue;
            }
            let li = b.line.unwrap();
            let hovered = pointer.is_some_and(|p| block_rect.contains(p));
            let st = self.states[i];
            let sung_now = st.selected && t >= b.start;

            let (dim, opacity, blur) = if let Some(css) = &cfg.css {
                let on = active.contains(&i) && active_real || st.selected;
                let target = if hovered || on {
                    (1.0, 0.0)
                } else {
                    match first_active {
                        Some(a) if i < a && !user => css.past.map_or((1.0, 0.0), |p| (p[0], p[1])),
                        Some(a) if i > a => css.upcoming.map_or((1.0, 0.0), |p| (p[0], p[1])),
                        _ => (1.0, 0.0),
                    }
                };
                let line_ms = b.dur;
                let (op_t, blur_t) = if hovered {
                    ((0.3, 0.0), (0.3, 0.0))
                } else {
                    let o = css.opacity_transition.unwrap_or([0.0, 0.0]);
                    let fl = css.filter_transition.unwrap_or([0.0, 0.0]);
                    ((line_ms * o[0] as f64, o[1] as f64), (line_ms * fl[0] as f64, fl[1] as f64))
                };
                let s = &mut self.states[i];
                s.opacity.set(target.0, f.now, op_t.0, op_t.1);
                s.blur.set(target.1, f.now, blur_t.0, blur_t.1);
                animating |= s.opacity.moving(f.now) || s.blur.moving(f.now);
                (cfg.inactive_alpha, s.opacity.value(f.now), s.blur.value(f.now))
            } else {
                let rel = active_line.map_or(1, |a| i as isize - a as isize);
                let past = rel < 0;
                let dist = rel.unsigned_abs() as f32;
                let upcoming = (cfg.upcoming_alpha - dist.min(6.0) * cfg.falloff).max(cfg.min_alpha.min(cfg.upcoming_alpha));
                let reveal = theme::anim_bool(&ctx, "lyr-reveal", user, 0.3);
                let dim = if sung_now || rel == 0 {
                    cfg.inactive_alpha
                } else if past {
                    lerp(cfg.past_alpha.unwrap_or(upcoming), upcoming, reveal)
                } else {
                    upcoming
                };
                let dim = theme::anim(&ctx, ("lyr-dim", f.key, i), dim, 0.3);
                let blur_on = match cfg.blur_scope {
                    BlurScope::All => true,
                    BlurScope::Past => past,
                    BlurScope::Upcoming => !past || reveal > 0.0,
                };
                let target = if cfg.blur > 0.0 && blur_on && !sung_now && rel != 0 { (cfg.blur * (1.0 + 0.35 * (dist - 1.0).clamp(0.0, 3.0))).min(9.0) } else { 0.0 };
                let s = &mut self.states[i];
                s.blur.set(target, f.now, 0.35, 0.0);
                animating |= s.blur.moving(f.now);
                (dim, 1.0, s.blur.value(f.now))
            };

            let mut scale = cfg.scale;
            if st.selected && t >= b.start {
                scale = lerp(cfg.scale, cfg.active_scale, EASE.at(((t - b.start) / SCALE_DURATION) as f32));
            } else if let Some(e) = st.exit {
                scale = lerp(cfg.active_scale, cfg.scale, EASE.at(((f.now - e) / SCALE_DURATION) as f32));
            } else if let Some(h) = cfg.css.as_ref().and_then(|c| c.hover_scale) {
                let k = theme::anim_bool(&ctx, ("lyr-hover", f.key, i), hovered, 0.3);
                scale = lerp(cfg.scale, h, EASE.at(k));
            }
            if st.selected && t >= b.start && t < b.start + SCALE_DURATION {
                animating = true;
            }
            let origin = transform_origin(block_rect, b.align, content.x);
            let xf = Xf { origin, scale, content };

            let text_lit = if let Some(e) = st.exit {
                1.0 - EASE.at(((f.now - e) / FADE_OUT) as f32)
            } else if sung_now {
                EASE.at(((t - b.start) / FADE_IN) as f32)
            } else {
                0.0
            };
            let text_alpha = 0.6 * lerp(dim, 1.0, text_lit);
            if b.instrumental {
                animating |= paint_instrumental(&painter, &cfg, b, &xf, t, line_w - cfg.size * 0.5);
            } else if blur > 0.05 {
                let r = (blur * 4.0).round() / 4.0;
                let deco_words: Vec<(&Word, f32)> = b
                    .decos
                    .iter()
                    .flat_map(|d| match &d.body {
                        DecoBody::Timed { words, .. } => words.iter().map(|w| (w, dim)).collect::<Vec<_>>(),
                        DecoBody::Text(..) => vec![],
                    })
                    .collect();
                let texts: Vec<(Arc<Galley>, Pos2, f32)> = b
                    .decos
                    .iter()
                    .filter_map(|d| match &d.body {
                        DecoBody::Text(text, font) => {
                            let g = ctx.fonts_mut(|fv| fv.layout(text.clone(), font.clone(), Color32::WHITE, d.rect.width() + 1.0));
                            let pad = if d.pill { vec2(cfg.rem() * 0.75 + 1.0, cfg.rem() * 0.25 + 1.0) } else { Vec2::ZERO };
                            Some((g, d.rect.min + pad, text_alpha))
                        }
                        DecoBody::Timed { .. } => None,
                    })
                    .collect();
                let words: Vec<(&Word, f32)> = b.words.iter().map(|w| (w, if w.bg { dim * 0.78 } else { dim })).chain(deco_words).collect();
                let pills: Vec<(Rect, f32)> = b.decos.iter().filter(|d| d.pill).map(|d| (d.rect, cfg.rem())).collect();
                if let Some((tex, rr, uv)) = self.glow.blurred_line(&ctx, (f.key, i), r, &words, &texts, &pills, scale, atlas, ppp) {
                    let to = xf.rect(rr);
                    let snapped = (to.min.to_vec2() * ppp).round() / ppp;
                    painter.image(tex, to.translate(snapped - to.min.to_vec2()), uv, theme::with_alpha(Color32::WHITE, opacity));
                }
                animating |= st.exit.is_some();
            } else {
                let lit = LitState::new(&cfg, st, t, f.now, sung_now || st.selected);
                paint_words(&painter, &ctx, &cfg, &b.words, &b.groups, &xf, &lit, dim, opacity, atlas, &mut glow_work, (f.key, i, 0));
                for (k, d) in b.decos.iter().enumerate() {
                    let dxf = xf;
                    let a = opacity;
                    if d.pill {
                        let r = dxf.rect(d.rect);
                        let radius = (cfg.rem() * scale).min(255.0) as u8;
                        painter.rect(r, radius, theme::with_alpha(Color32::WHITE, 0.05 * a), egui::Stroke::new(1.0, theme::with_alpha(Color32::WHITE, 0.1 * a)), egui::StrokeKind::Inside);
                    }
                    match &d.body {
                        DecoBody::Timed { words, groups } => {
                            paint_words(&painter, &ctx, &cfg, words, groups, &dxf, &lit, dim, a, atlas, &mut glow_work, (f.key, i, k as u32 + 1));
                        }
                        DecoBody::Text(text, font) => {
                            let wrap = d.rect.width() + 1.0;
                            let g = ctx.fonts_mut(|fv| fv.layout(text.clone(), font.clone(), Color32::WHITE, wrap));
                            let pad = if d.pill { vec2(cfg.rem() * 0.75 + 1.0, cfg.rem() * 0.25 + 1.0) } else { Vec2::ZERO };
                            paint_galley(&painter, &g, d.rect.min + pad, &dxf, theme::with_alpha(Color32::WHITE, text_alpha * a), atlas);
                        }
                    }
                }
                animating |= lit.animating(b, t);
            }

            let hit = block_rect.intersect(rect);
            if hit.is_positive() {
                let resp = ui.interact(hit, ui.scope_id().with(("lyr-line", f.key, i)), egui::Sense::click());
                if resp.clicked() && f.lyrics.sync != Sync::None {
                    seek = Some(f.lyrics.lines[li].start as f64 / 1000.0);
                }
                if resp.hovered() {
                    ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
                }
            }
        }
        self.glow.finish(&ctx, &painter, glow_work, atlas, ppp);
        fade_edges(&ctx, painter.layer_id(), first_shape, rect, cfg.size);
        if f.playing {
            animating = true;
        }
        Out { seek, animating, user_scrolling: user }
    }

    fn relayout(&mut self, ui: &mut egui::Ui, cfg: &Cfg, f: &Frame, key: u64, rect: Rect) {
        let width = rect.width() - cfg.pad.0 - cfg.pad.1;
        let lyrics = f.lyrics;
        let mut blocks: Vec<Block> = ui.ctx().fonts_mut(|fonts| {
            lyrics
                .lines
                .iter()
                .enumerate()
                .map(|(i, l)| {
                    let agent = if l.instrumental { nearest_agent(lyrics, i) } else { l.agent.clone() };
                    let roman = f.roman.get(i).and_then(|r| r.as_ref()).map(|r| match r {
                        Roman::Timed(p, t) => Roman::Timed(p, t),
                        Roman::Text(t) => Roman::Text(t),
                    });
                    let trans = f.trans.get(i).copied().flatten();
                    build_block(fonts, cfg, l, i, width, roman, trans, agent.as_deref())
                })
                .collect()
        });
        if !lyrics.songwriters.is_empty() {
            let names = &lyrics.songwriters;
            let text = if names.len() > 1 { format!("Written by {} & {}", names[..names.len() - 1].join(", "), names[names.len() - 1]) } else { format!("Written by {}", names[0]) };
            let font = theme::regular((cfg.size * 0.4).max(12.0));
            let h = ui.ctx().fonts_mut(|fv| fv.layout(text.clone(), font.clone(), Color32::WHITE, width - cfg.size * 0.5).size().y);
            blocks.push(Block {
                line: None,
                start: 0.0,
                dur: 0.0,
                instrumental: false,
                align: if cfg.centered { Align::Center } else { Align::Left },
                top: 0.0,
                height: h + cfg.padding * 2.0,
                words: vec![],
                groups: vec![],
                decos: vec![],
                credits: Some((text, font)),
            });
        }
        let mut y = 0.0;
        for b in &mut blocks {
            b.top = y;
            y += b.height;
        }
        let view_h = rect.height();
        let first_h = blocks.first().map_or(0.0, |b| b.height);
        let pad_top = (view_h * cfg.target_ratio - first_h / 2.0).max(0.0);
        let last_centre = blocks.iter().rev().find(|b| b.line.is_some()).map(|b| b.top + b.height / 2.0);
        let last_h = blocks.iter().rev().find(|b| b.line.is_some()).map_or(0.0, |b| b.height);
        let content = y;
        let trailing = blocks.last().filter(|b| b.credits.is_some()).map_or(0.0, |b| b.height) + last_h / 2.0;
        let want = last_centre.map_or(view_h, |c| c + view_h * (1.0 - cfg.target_ratio));
        let pad_bottom = (want - content).max(view_h - content).max(view_h * (1.0 - cfg.target_ratio) - trailing).max(0.0);
        let richsync = lyrics.sync == Sync::Word;
        let same_song = self.layout.as_ref().is_some_and(|l| l.blocks.len() == blocks.len() && self.states.len() == blocks.len() && l.richsync == richsync && same_lines(l, &blocks));
        if same_song {
            let old = self.layout.as_ref().unwrap();
            let now = ui.input(|i| i.time);
            self.shifts.resize_with(blocks.len(), Vec::new);
            for (i, b) in blocks.iter().enumerate() {
                let delta = (old.pad_top + old.blocks[i].top) - (pad_top + b.top);
                if delta.abs() > 0.5 && (old.width - width).abs() < 1.0 {
                    self.shifts[i].push(Shift { start: now, delta });
                }
            }
        } else {
            let glow = std::mem::take(&mut self.glow);
            *self = Self { glow, ..Default::default() };
            self.states = vec![LineState::default(); blocks.len()];
            self.shifts = vec![Vec::new(); blocks.len()];
        }
        self.layout = Some(Layout { key, width, blocks, pad_top, content_h: pad_top + content + pad_bottom, richsync });
    }
}

fn same_lines(l: &Layout, blocks: &[Block]) -> bool {
    l.blocks.iter().zip(blocks).all(|(a, b)| a.line == b.line && a.start == b.start && a.words.len() == b.words.len())
}

fn nearest_agent(l: &Lyrics, i: usize) -> Option<String> {
    l.lines[i + 1..].iter().chain(l.lines[..i].iter().rev()).find(|x| !x.instrumental && x.agent.is_some()).and_then(|x| x.agent.clone())
}

pub struct GlowJob {
    key: (u64, usize, u32, u32),
    quads: Vec<(Rect, Rect, usize, usize, f32)>,
    word: Rect,
    look: WordLook,
    by_letter: bool,
    rtl: bool,
    radius: f32,
    pills: Vec<(Rect, f32)>,
    color: Color32,
    layer: ShapeIdx,
}

pub fn dev_off(what: &str) -> bool {
    static OFF: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    OFF.get_or_init(|| std::env::var("NUVX_LYR_OFF").unwrap_or_default()).split(',').any(|x| x == what)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bezier_matches_css_ease() {
        assert!((EASE.at(0.5) - 0.8024).abs() < 0.002);
        assert!((EASE.at(0.25) - 0.4094).abs() < 0.002);
        assert_eq!(EASE.at(0.0), 0.0);
        assert_eq!(EASE.at(1.0), 1.0);
    }

    #[test]
    fn parts_split_on_spaces_with_prorated_time() {
        let p = Part { start: 1000, dur: 1000, text: "ab cd".into(), bg: false };
        let t = split_part(&p);
        assert_eq!(t.len(), 3);
        assert!((t[0].start - 1.0).abs() < 1e-9 && (t[0].dur - 0.5).abs() < 1e-9);
        assert!(t[1].space);
        assert!((t[2].start - 1.5).abs() < 1e-9);
    }

    #[test]
    fn rtl_lines_run_from_the_right() {
        let mut fonts = egui::epaint::text::Fonts::new(Default::default(), egui::FontDefinitions::default());
        let mut fv = fonts.with_pixels_per_point(1.0);
        let font = FontId::proportional(20.0);
        let line = |text: &str| line_synced_parts(&Line { start: 0, dur: 1000, text: text.into(), ..Default::default() }).iter().flat_map(split_part).collect::<Vec<_>>();
        let x = |f: &Flow, word: &str| f.words.iter().find(|w| w.text == word).unwrap().pos.x;
        let f = flow(&mut fv, line("שלום עולם hello world"), &font, 1000.0, 27.0, Align::Right, true);
        assert!(x(&f, "שלום") > x(&f, "עולם"));
        assert!(x(&f, "עולם") > x(&f, "world"));
        assert!(x(&f, "world") > x(&f, "hello"));
        let f = flow(&mut fv, line("my song שלום עולם remix"), &font, 1000.0, 27.0, Align::Left, false);
        assert!(x(&f, "song") < x(&f, "עולם") && x(&f, "עולם") < x(&f, "שלום") && x(&f, "שלום") < x(&f, "remix"));
        let f = flow(&mut fv, line(&"word ".repeat(40)), &font, 300.0, 27.0, Align::Left, false);
        assert!(f.height > 27.0 && f.words.iter().all(|w| w.pos.x + w.w <= 300.0 + 0.5));
    }

    #[test]
    #[ignore]
    fn rtl_word_positions() {
        let ctx = shot_ctx();
        let font = crate::theme::semibold(26.0);
        for (a, b) in [("אבג", "דהו"), ("عمر", "دياب")] {
            let text = format!("{a} {b}");
            let mean = |g: &Galley, w: &str| {
                let xs: Vec<f32> = g.rows.iter().flat_map(|r| r.row.glyphs.iter()).filter(|gl| w.contains(gl.chr)).map(|gl| gl.pos.x).collect();
                xs.iter().sum::<f32>() / xs.len().max(1) as f32
            };
            let (before, after) = ctx.fonts_mut(|f| (f.layout_no_wrap(text.clone(), font.clone(), Color32::WHITE), f.layout_job(crate::bidi::job(&text, font.clone(), Color32::WHITE))));
            println!("{text}: before first word x {:.0}, second {:.0} | after first {:.0}, second {:.0}", mean(&before, a), mean(&before, b), mean(&after, a), mean(&after, b));
            assert!(mean(&after, a) > mean(&after, b), "{text}: the first word should be on the right");
        }
    }

    fn shot_ctx() -> egui::Context {
        let ctx = egui::Context::default();
        crate::theme::install_fonts(&ctx, "Inter");
        for _ in 0..2 {
            let _ = ctx.run_ui(Default::default(), |_| {});
        }
        ctx
    }

    fn blit(img: &mut image::RgbaImage, atlas: &ColorImage, at: Pos2, g: &Galley, rgb: [u8; 3], clip: (f32, f32)) {
        let (w, h) = (img.width() as i64, img.height() as i64);
        for row in &g.rows {
            for gl in &row.row.glyphs {
                let uv = gl.uv_rect;
                if uv.is_nothing() {
                    continue;
                }
                let o = at + row.pos.to_vec2() + gl.pos.to_vec2() + uv.offset;
                for (dy, ty) in (uv.min[1]..uv.max[1]).enumerate() {
                    for (dx, tx) in (uv.min[0]..uv.max[0]).enumerate() {
                        let a = atlas.pixels[ty as usize * atlas.size[0] + tx as usize].a() as f32 / 255.0;
                        let (x, y) = ((o.x + dx as f32).round() as i64, (o.y + dy as f32).round() as i64);
                        if a <= 0.0 || x < 0 || y < 0 || x >= w || y >= h || (y as f32) < clip.0 || (y as f32) >= clip.1 {
                            continue;
                        }
                        let p = img.get_pixel_mut(x as u32, y as u32);
                        for c in 0..3 {
                            p[c] = (p[c] as f32 * (1.0 - a) + rgb[c] as f32 * a) as u8;
                        }
                    }
                }
            }
        }
    }

    #[test]
    #[ignore]
    fn paging_before_after_png() {
        let ctx = shot_ctx();
        let lyric = "And I keep on running down the same old road that takes me back to you every single night, \
            through the rain and the lights of a city that never learned your name, and I still hear you calling out to me";
        let (width, view_h, font) = (470.0, 230.0, crate::theme::lyric_font_for("Inter", crate::preset::Weight::Bold, 30.0, lyric));
        let lh = 30.0 * 1.333;
        let toks = line_synced_parts(&Line { start: 0, dur: 1000, text: lyric.into(), ..Default::default() }).iter().flat_map(split_part).collect();
        let (words, galleys) = ctx.fonts_mut(|f| {
            let fl = flow(f, toks, &font, width, lh, Align::Left, false);
            let gs: Vec<Arc<Galley>> = fl.words.iter().map(|w| f.layout_no_wrap(w.text.clone(), font.clone(), Color32::WHITE)).collect();
            (fl.words, gs)
        });
        let labels = ctx.fonts_mut(|f| {
            ["Before (0.2.28): the 6th row is being sung, off the bottom", "After: the view pages down with it"].map(|t| f.layout_no_wrap(t.into(), crate::theme::semibold(16.0), Color32::WHITE))
        });
        let atlas = ctx.fonts(|f| f.image());
        let sung_row = 5.0;
        let y_row = sung_row * lh;
        let tops = [0.0f32, (y_row + lh * 2.0 - view_h).max(0.0)];
        let (col, pad) = (width + 70.0, 30.0);
        let (iw, ih) = ((col * 2.0) as u32, (view_h + 110.0) as u32);
        let mut img = image::RgbaImage::from_pixel(iw, ih, image::Rgba([24, 22, 30, 255]));
        for (k, top) in tops.iter().enumerate() {
            let x0 = pad + col * k as f32;
            let (v0, v1) = (60.0, 60.0 + view_h);
            blit(&mut img, &atlas, pos2(x0, 20.0), &labels[k], [200, 196, 210], (0.0, ih as f32));
            for x in (x0 - 10.0) as u32..(x0 + width + 10.0) as u32 {
                for y in [v0 as u32 - 1, v1 as u32] {
                    img.put_pixel(x, y, image::Rgba([90, 84, 104, 255]));
                }
            }
            for (w, g) in words.iter().zip(&galleys) {
                let row = (w.pos.y / lh).floor();
                let rgb = if row < sung_row { [242, 240, 245] } else if row == sung_row { [196, 160, 255] } else { [110, 106, 120] };
                blit(&mut img, &atlas, pos2(x0 + w.pos.x, v0 + w.pos.y - top), g, rgb, (v0, v1));
            }
        }
        let out = std::env::var("NUVX_SHOT").unwrap_or_else(|_| "paging-before-after.png".into());
        img.save(&out).unwrap();
        println!("wrote {out}");
    }

    #[test]
    #[ignore]
    fn rtl_before_after_png() {
        let ctx = egui::Context::default();
        crate::theme::install_fonts(&ctx, "Inter");
        for _ in 0..2 {
            let _ = ctx.run_ui(Default::default(), |_| {});
        }
        let titles = ["عمرو دياب - تملي معاك", "עומר אדם - תגידו לה", "Fairuz - نسم علينا الهوى (Live)"];
        let lyric = "يا ليل الصب متى غده أقيام الساعة موعده رقد السمار فأرقه أسف للبين يردده";
        let (col, width) = (560.0, 470.0);
        let title_font = crate::theme::semibold(26.0);
        let lyric_font = crate::theme::lyric_font_for("Inter", crate::preset::Weight::Bold, 30.0, lyric);
        let mut draw: Vec<(Pos2, Arc<Galley>)> = Vec::new();
        ctx.fonts_mut(|f| {
            for (i, label) in ["Before (0.2.28)", "After"].into_iter().enumerate() {
                draw.push((pos2(30.0 + col * i as f32, 20.0), f.layout_no_wrap(label.into(), crate::theme::bold(20.0), Color32::WHITE)));
            }
            for (k, t) in titles.iter().enumerate() {
                let y = 70.0 + k as f32 * 50.0;
                draw.push((pos2(30.0, y), f.layout_no_wrap(t.to_string(), title_font.clone(), Color32::WHITE)));
                draw.push((pos2(30.0 + col, y), f.layout_job(crate::bidi::job(t, title_font.clone(), Color32::WHITE))));
            }
            let y = 240.0;
            let (lh, space) = (30.0 * 1.333, text_size(f, " ", &lyric_font).x);
            let mut at = vec2(0.0, 0.0);
            for word in lyric.split(' ') {
                let g = f.layout_no_wrap(word.into(), lyric_font.clone(), Color32::WHITE);
                if at.x > 0.0 && at.x + g.size().x > width {
                    at = vec2(0.0, at.y + lh);
                }
                let w = g.size().x;
                draw.push((pos2(30.0, y) + at, g));
                at.x += w + space;
            }
            let toks = line_synced_parts(&Line { start: 0, dur: 1000, text: lyric.into(), ..Default::default() }).iter().flat_map(split_part).collect();
            let fl = flow(f, toks, &lyric_font, width, 30.0 * 1.333, Align::Right, true);
            for w in fl.words {
                draw.push((pos2(30.0 + col, y) + w.pos.to_vec2(), f.layout_no_wrap(w.text.clone(), lyric_font.clone(), Color32::WHITE)));
            }
        });
        let atlas = ctx.fonts(|f| f.image());
        let (w, h) = ((col * 2.0) as u32, 470u32);
        let mut img = image::RgbaImage::from_pixel(w, h, image::Rgba([24, 22, 30, 255]));
        for y in 0..h {
            img.put_pixel(col as u32 - 15, y, image::Rgba([70, 66, 80, 255]));
        }
        for (at, g) in &draw {
            blit(&mut img, &atlas, *at, g, [242, 240, 245], (0.0, h as f32));
        }
        let out = std::env::var("NUVX_SHOT").unwrap_or_else(|_| "rtl-before-after.png".into());
        img.save(&out).unwrap();
        println!("wrote {out}");
    }

    #[test]
    fn line_synced_words_are_staggered() {
        let l = Line { start: 2000, dur: 3000, text: "one two  three".into(), ..Default::default() };
        let p = line_synced_parts(&l);
        let words: Vec<_> = p.iter().filter(|p| !p.text.trim().is_empty()).collect();
        assert_eq!(words[1].start, 2050);
        assert_eq!(words[2].start, 2100);
    }

    #[test]
    fn letter_waves_crest_when_lit() {
        let w = letter_waves(4, 1.0, 0.9);
        assert!(w[0].0 < w[3].0);
        assert!(letter_waves(3, 0.0, 0.9).iter().all(|w| w.0 == 0.0 && (w.1 - 0.9).abs() < 1e-9));
    }

    #[test]
    fn scroll_durations_follow_the_extension() {
        assert!((scroll_duration(0, 10.0) - (0.750 + 2f64.ln() * 0.08 + 0.02)).abs() < 1e-9);
        assert!((scroll_duration(-3, 10.0) - 0.810).abs() < 1e-9);
        assert!((scroll_duration(2, 10.0) - (0.750 + 4f64.ln() * 0.08 + 0.06)).abs() < 1e-9);
    }

    #[test]
    fn gradient_is_soft_and_never_a_box() {
        let look = WordLook { opacity: 1.0, start: 0.5, end: 0.6, glow: 0.0, glow_alpha: 0.5 };
        let word = Rect::from_min_size(pos2(0.0, 0.0), vec2(100.0, 40.0));
        let (f, cuts) = lit_gradient(&word, 0, 1, word, &look, false, false);
        assert_eq!(f(10.0), 1.0);
        assert!(f(55.0) > 0.0 && f(55.0) < 1.0);
        assert_eq!(f(70.0), 0.0);
        assert_eq!(cuts.len(), 2);
    }

    #[test]
    fn a_break_finishes_before_the_next_line() {
        let (exit, len, step) = gap_times(10.0, 6.0);
        assert!((exit - 15.15).abs() < 1e-9);
        assert!((len - 0.35).abs() < 1e-9);
        assert!((step - 5.15 / 3.0).abs() < 1e-9);
        let (exit, len, _) = gap_times(10.0, 1.0);
        assert!((exit - 10.62).abs() < 1e-9);
        assert!((len - 0.35).abs() < 1e-9);
    }
}
