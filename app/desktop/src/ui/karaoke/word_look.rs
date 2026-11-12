use super::{Block, Cfg, LineState, Word, FADE_IN, FADE_OUT, GLOW_INVISIBLE, GLOW_MIN, GLOW_RATIO, SWIPE_DURATION, SWIPE_END, SWIPE_LEAD, SWIPE_START, EASE, glow_radius, lerp};
use crate::preset::Fill;
pub(super) struct LitState {
    pub(super) live: bool,
    pub(super) exit: Option<(f32, f64)>,
    pub(super) t: f64,
    pub(super) fill: Fill,
}

impl LitState {
    pub(super) fn new(cfg: &Cfg, st: LineState, t: f64, now: f64, live: bool) -> Self {
        Self { live: live && st.exit.is_none(), exit: st.exit.map(|e| ((now - e) as f32, st.exit_t)), t, fill: cfg.fill }
    }

    pub(super) fn animating(&self, b: &Block, t: f64) -> bool {
        if self.exit.is_some() {
            return true;
        }
        self.live && b.words.iter().any(|w| t >= w.start - 0.5 && t < w.start + w.dur.max(0.0) * SWIPE_DURATION + GLOW_MIN.max(w.dur * GLOW_RATIO) + 1.0)
    }
}

pub(super) struct WordLook {
    pub(super) opacity: f32,
    pub(super) start: f32,
    pub(super) end: f32,
    pub(super) glow: f32,
    pub(super) glow_alpha: f32,
}

pub(super) fn word_look(w: &Word, lit: &LitState, cfg: &Cfg) -> WordLook {
    let glow_alpha = if w.long { cfg.long_glow_alpha } else { cfg.glow_alpha };
    let none = WordLook { opacity: 0.0, start: SWIPE_START.0, end: SWIPE_END.0, glow: 0.0, glow_alpha };
    let full = |opacity: f32| WordLook { opacity, start: SWIPE_START.1, end: SWIPE_END.1, glow: 0.0, glow_alpha };
    if let Some((since, exit_t)) = lit.exit {
        if exit_t < w.start {
            return none;
        }
        return full(1.0 - EASE.at(since / FADE_OUT as f32));
    }
    if !lit.live {
        return none;
    }
    let e = lit.t - w.start;
    if e < 0.0 {
        return none;
    }
    let glow_rem = if glow_alpha < GLOW_INVISIBLE { 0.0 } else { cfg.glow_rem * cfg.rem() };
    match lit.fill {
        Fill::Line | Fill::Word => {
            let d = if lit.fill == Fill::Word { 0.18 } else { FADE_IN };
            let p = EASE.at((e / d) as f32);
            WordLook { glow: glow_radius(e, GLOW_MIN, glow_rem), ..full(p) }
        }
        Fill::Sweep if w.line_synced => {
            let p = EASE.at((e / FADE_IN) as f32);
            WordLook { glow: glow_radius(e, GLOW_MIN, glow_rem), ..full(p) }
        }
        Fill::Sweep => {
            let d = w.dur.max(0.0);
            let sd = d * SWIPE_DURATION;
            let p = if sd <= 0.0 { 1.0 } else { ((e + d * SWIPE_LEAD) / sd).clamp(0.0, 1.0) as f32 };
            WordLook {
                opacity: 1.0,
                start: lerp(SWIPE_START.0, SWIPE_START.1, p),
                end: lerp(SWIPE_END.0, SWIPE_END.1, p),
                glow: glow_radius(e, (d * GLOW_RATIO).max(GLOW_MIN), glow_rem),
                glow_alpha,
            }
        }
    }
}
