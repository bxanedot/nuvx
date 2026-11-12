use super::{EASE, lerp};

#[derive(Clone, Copy)]
pub(super) struct ScrollAnim {
    pub(super) start: f64,
    pub(super) delta: f32,
    pub(super) active: usize,
}

#[derive(Clone, Copy)]
pub(super) struct Shift {
    pub(super) start: f64,
    pub(super) delta: f32,
}

#[derive(Clone, Copy, Default)]
pub(super) struct Trans {
    pub(super) from: f32,
    pub(super) to: f32,
    pub(super) start: f64,
    pub(super) dur: f64,
    pub(super) delay: f64,
    pub(super) init: bool,
}

impl Trans {
    pub(super) fn value(&self, now: f64) -> f32 {
        if !self.init {
            return self.to;
        }
        let p = ((now - self.start - self.delay) / self.dur.max(1e-3)).clamp(0.0, 1.0) as f32;
        lerp(self.from, self.to, EASE.at(p))
    }

    pub(super) fn set(&mut self, to: f32, now: f64, dur: f64, delay: f64) {
        if !self.init {
            *self = Trans { from: to, to, start: now, dur, delay, init: true };
        } else if (to - self.to).abs() > 1e-4 {
            let from = self.value(now);
            *self = Trans { from, to, start: now, dur, delay, init: true };
        }
    }

    pub(super) fn moving(&self, now: f64) -> bool {
        self.init && (self.from - self.to).abs() > 1e-4 && now < self.start + self.delay + self.dur
    }
}

#[derive(Clone, Copy, Default)]
pub(super) struct LineState {
    pub(super) opacity: Trans,
    pub(super) blur: Trans,
    pub(super) selected: bool,
    pub(super) exit: Option<f64>,
    pub(super) exit_t: f64,
}
