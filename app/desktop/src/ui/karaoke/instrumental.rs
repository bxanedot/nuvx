use egui::{Color32, pos2};

use super::{Align, Block, Cfg, EASE, Xf};
use crate::theme;
const GAP_DOTS: usize = 3;
const GAP_DOT_REST: f32 = 0.55;
const GAP_REST_SCALE: f32 = 0.76;
const GAP_BREATH: f32 = 0.20;
const GAP_PUFF: f32 = 0.35;
const GAP_ENTER: f64 = 0.620;
const GAP_EXIT: f64 = 0.350;
const GAP_SPLIT: f32 = 0.35;
const GAP_COLLAPSE: f64 = 0.500;

pub(super) fn gap_times(start: f64, dur: f64) -> (f64, f64, f64) {
    let until = start + dur.max(0.001);
    let exit_start = start + GAP_ENTER.max(until - start - GAP_COLLAPSE - GAP_EXIT);
    let exit_len = (until.min(exit_start + GAP_EXIT) - exit_start).max(0.001);
    let step = ((exit_start - start) / GAP_DOTS as f64).max(0.001);
    (exit_start, exit_len, step)
}

pub(super) fn paint_instrumental(painter: &egui::Painter, cfg: &Cfg, b: &Block, xf: &Xf, t: f64, content_w: f32) -> bool {
    let em = cfg.size;
    let dot = em * 13.0 / 34.0;
    let gap = em * 5.0 / 34.0;
    let total = dot * GAP_DOTS as f32 + gap * (GAP_DOTS - 1) as f32;
    let x0 = match b.align {
        Align::Left => 0.0,
        Align::Right => content_w - total,
        Align::Center => (content_w - total) * 0.5,
    };
    let cy = cfg.padding + em * 1.333 * 0.5;
    let mid = x0 + total * 0.5;
    let into = t - b.start;
    let (exit_start, exit_len, step) = gap_times(b.start, b.dur);
    let until = b.start + b.dur.max(0.001);
    let swell = EASE.at(((into / GAP_ENTER) as f32).clamp(0.0, 1.0));
    let e = ((t - exit_start) / exit_len).clamp(0.0, 1.0) as f32;
    let pull = e > GAP_SPLIT;
    let half = if pull { (e - GAP_SPLIT) / (1.0 - GAP_SPLIT) } else { e / GAP_SPLIT };
    let bend = half * half * (3.0 - 2.0 * half);
    let grow = if e <= 0.0 {
        GAP_REST_SCALE + (1.0 - GAP_REST_SCALE) * swell
    } else if !pull {
        1.0 + GAP_PUFF * bend
    } else {
        (1.0 + GAP_PUFF) * (1.0 - bend)
    };
    let alpha = if e <= 0.0 { swell } else if !pull { 1.0 } else { 1.0 - bend };
    if alpha <= 0.001 || grow <= 0.001 {
        return false;
    }
    let beat = if into < step * GAP_DOTS as f64 { ((into % step) / step) as f32 } else { 1.0 };
    let pulse = 1.0 - GAP_BREATH * 0.5 * (1.0 - (std::f32::consts::TAU * beat).cos());
    for i in 0..GAP_DOTS {
        let x = x0 + dot * 0.5 + i as f32 * (dot + gap);
        let cx = mid + (x - mid) * grow * pulse;
        let c = xf.point(pos2(cx, cy));
        let r = dot * 0.5 * grow * pulse * xf.scale;
        let k = ((t - b.start - i as f64 * step) / step).clamp(0.0, 1.0) as f32;
        let lit = k * k * (3.0 - 2.0 * k);
        painter.circle_filled(c, r, theme::with_alpha(Color32::WHITE, GAP_DOT_REST * alpha));
        if lit > 0.0 {
            painter.circle_filled(c, r, theme::with_alpha(cfg.active, lit * alpha));
        }
    }
    (into >= 0.0 && into <= GAP_ENTER) || t < b.start + step * GAP_DOTS as f64 || (t >= exit_start && t < until)
}
