use egui::{Pos2, Rect, pos2};

use super::{Align, Cfg, EASE, EASE_IN_OUT, EASE_OUT, LETTER_WAVE_CREST, SWIPE_DURATION, SWIPE_END, SWIPE_LEAD, SWIPE_START, WOBBLE_DURATION};
pub(super) fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

pub(super) fn scroll_duration(rel: isize, delta: f32) -> f64 {
    let up = delta < 0.0;
    let above = (rel < 0 && !up) || (rel > 0 && up);
    let i = rel.unsigned_abs() as f64;
    if above { (750.0 + i.min(6.0) * 20.0) / 1000.0 } else { (750.0 + (i + 2.0).ln() * 80.0 + (i + 1.0) * 20.0) / 1000.0 }
}

pub(super) fn transform_origin(block: Rect, align: Align, content_left: f32) -> Pos2 {
    match align {
        Align::Left => pos2(block.left(), block.center().y),
        Align::Right => pos2(block.right(), block.center().y),
        Align::Center => pos2(block.center().x.max(content_left), block.center().y),
    }
}

pub(super) fn glow_radius(e: f64, dur: f64, from: f32) -> f32 {
    if e < 0.0 || e >= dur {
        return 0.0;
    }
    from * (1.0 - EASE.at((e / dur) as f32))
}

pub(super) fn letter_waves(n: usize, d: f64, float: f64) -> Vec<(f64, f64)> {
    let sd = d * SWIPE_DURATION;
    let lead = d * SWIPE_LEAD;
    let rise = float * LETTER_WAVE_CREST as f64;
    (0..n)
        .map(|i| {
            if sd <= 0.0 {
                return (0.0, float);
            }
            let (sf, st) = (SWIPE_START.0 as f64, SWIPE_START.1 as f64);
            let (ef, et) = (SWIPE_END.0 as f64, SWIPE_END.1 as f64);
            let begin = (sd * (i as f64 / n as f64 - ef) / (et - ef)).clamp(0.0, sd);
            let finish = (sd * ((i + 1) as f64 / n as f64 - sf) / (st - sf)).clamp(0.0, sd);
            let dur = (finish - begin).max(0.001);
            let touched = begin - lead;
            let lit = touched + dur;
            if lit - rise > touched { (touched, (lit - touched) / LETTER_WAVE_CREST as f64) } else { (lit - rise, float) }
        })
        .collect()
}

pub(super) fn letter_float(e: f64, wave: (f64, f64), long: bool, cfg: &Cfg) -> (f32, f32) {
    let p = (e - wave.0) / wave.1;
    if p < 0.0 {
        return (0.0, 1.0);
    }
    let p = p.min(1.0) as f32;
    let peak = if long { cfg.emphasis } else { 1.0 };
    if p < LETTER_WAVE_CREST {
        let k = EASE_IN_OUT.at(p / LETTER_WAVE_CREST);
        (cfg.wave_crest * k, lerp(1.0, peak, k))
    } else {
        let k = EASE_OUT.at((p - LETTER_WAVE_CREST) / (1.0 - LETTER_WAVE_CREST));
        (lerp(cfg.wave_crest, cfg.wave_settle, k), lerp(peak, 1.0, k))
    }
}

pub(super) fn wobble(e: f64) -> (f32, f32) {
    if !(0.0..WOBBLE_DURATION).contains(&e) {
        return (0.0, 1.0);
    }
    let p = EASE.at((e / WOBBLE_DURATION) as f32);
    let peak = (0.05, 1.025);
    if p < 0.125 {
        let k = p / 0.125;
        (peak.0 * k, lerp(1.0, peak.1, k))
    } else if p < 0.75 {
        let k = EASE_IN_OUT.at((p - 0.125) / 0.625);
        (lerp(peak.0, 0.0, k), lerp(peak.1, 1.0, k))
    } else {
        (0.0, 1.0)
    }
}
