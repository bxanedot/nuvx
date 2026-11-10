use std::f32::consts::PI;

#[derive(Clone, Copy, PartialEq, Debug)]
enum Kind {
    Bell,
    LowShelf,
    HighShelf,
}

const SLOTS: [(Kind, f32); 10] = [
    (Kind::LowShelf, 60.0),
    (Kind::Bell, 150.0),
    (Kind::Bell, 400.0),
    (Kind::Bell, 1_000.0),
    (Kind::Bell, 2_500.0),
    (Kind::Bell, 6_000.0),
    (Kind::HighShelf, 14_000.0),
    (Kind::LowShelf, 250.0),
    (Kind::Bell, 1_000.0),
    (Kind::HighShelf, 4_000.0),
];
const N: usize = SLOTS.len();

pub const BANDS_HZ: [f32; 7] = [60.0, 150.0, 400.0, 1_000.0, 2_500.0, 6_000.0, 14_000.0];
pub const BAND_RANGE_DB: f32 = 12.0;
pub const TONE_STEPS: i32 = 5;
const TONE_DB_PER_STEP: f32 = 1.2;
const MANUAL_Q: f32 = 1.0;

pub const PRESETS: [(&str, [f32; 7]); 16] = [
    ("Flat", [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]),
    ("Acoustic", [3.0, 1.5, 0.0, 1.5, 2.5, 2.0, 1.0]),
    ("Bass boost", [6.0, 4.0, 1.5, 0.0, 0.0, 0.0, 0.0]),
    ("Bass cut", [-6.0, -4.0, -1.5, 0.0, 0.0, 0.0, 0.0]),
    ("Vocal", [-3.0, -1.5, 1.0, 3.5, 3.0, 1.0, -1.0]),
    ("Treble boost", [0.0, 0.0, 0.0, 0.0, 1.5, 3.5, 5.0]),
    ("Treble cut", [0.0, 0.0, 0.0, 0.0, -1.5, -3.5, -5.0]),
    ("Loudness", [6.0, 3.5, 0.0, -1.5, -1.0, 2.0, 5.0]),
    ("Spoken word", [-5.0, -2.5, 1.5, 4.0, 3.5, 1.5, -2.0]),
    ("Electronic", [5.0, 3.0, -1.0, 0.0, 1.0, 3.0, 4.0]),
    ("Rock", [4.0, 2.5, -1.0, -1.5, 1.0, 3.0, 3.5]),
    ("Hip-hop", [6.0, 4.0, 0.5, -1.0, 0.5, 2.0, 2.5]),
    ("Jazz", [3.0, 1.5, 0.0, 1.0, 1.5, 2.0, 2.5]),
    ("Classical", [3.0, 2.0, 0.0, 0.0, 1.0, 2.5, 3.0]),
    ("Small speakers", [5.0, 4.0, 2.0, 0.5, 0.0, -1.0, -2.0]),
    ("Late night", [3.0, 1.0, 0.0, 1.5, 1.0, -1.0, -3.0]),
];

pub fn preset_of(bands: &[f32; 7]) -> Option<usize> {
    PRESETS.iter().position(|(_, p)| p.iter().zip(bands).all(|(a, b)| (a - b).abs() < 0.05))
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Curve {
    gains: [f32; N],
    qs: [f32; N],
    preamp: f32,
}

impl Curve {
    pub const FLAT: Curve = Curve { gains: [0.0; N], qs: [0.707; N], preamp: 0.0 };

    fn of(gains: [f32; N], qs: [f32; N]) -> Curve {
        Curve { gains, qs, preamp: preamp_for(&gains, &qs) }
    }

    pub fn manual(bands: &[f32; 7]) -> Curve {
        let mut gains = [0.0; N];
        let mut qs = [0.707; N];
        for (i, b) in bands.iter().enumerate() {
            gains[i] = b.clamp(-BAND_RANGE_DB, BAND_RANGE_DB);
            qs[i] = MANUAL_Q;
        }
        Curve::of(gains, qs)
    }

    pub fn tone(x: i32, y: i32, focused: bool) -> Curve {
        let mut gains = [0.0; N];
        let mut qs = [0.707; N];
        let tilt = x.clamp(-TONE_STEPS, TONE_STEPS) as f32 * TONE_DB_PER_STEP;
        let contour = y.clamp(-TONE_STEPS, TONE_STEPS) as f32 * TONE_DB_PER_STEP;
        gains[7] = -tilt;
        gains[9] = tilt;
        gains[8] = contour;
        let (shelf_q, bell_q) = if focused { (0.9, 2.2) } else { (0.5, 0.7) };
        qs[7] = shelf_q;
        qs[9] = shelf_q;
        qs[8] = bell_q;
        Curve::of(gains, qs)
    }
}

fn preamp_for(gains: &[f32; N], qs: &[f32; N]) -> f32 {
    const POINTS: usize = 96;
    let peak = (0..POINTS)
        .map(|p| {
            let hz = 20.0 * 1_000f64.powf(p as f64 / (POINTS - 1) as f64);
            (0..N).map(|s| section_gain_db(SLOTS[s], gains[s], qs[s], hz as f32)).sum::<f32>()
        })
        .fold(0.0, f32::max);
    -peak * 0.5
}

fn section_gain_db((kind, f0): (Kind, f32), gain_db: f32, q: f32, hz: f32) -> f32 {
    if gain_db.abs() < 0.01 {
        return 0.0;
    }
    let a = 10f64.powf(gain_db as f64 / 40.0);
    let a2 = a * a;
    let x = (hz / f0) as f64;
    let x2 = x * x;
    let qq = (q * q) as f64;
    let m = match kind {
        Kind::Bell => {
            let flat = (1.0 - x2) * (1.0 - x2);
            ((flat + x2 * a2 / qq) / (flat + x2 / (a2 * qq))).sqrt()
        }
        Kind::LowShelf => {
            let c = x2 * a / qq;
            a * (((a - x2) * (a - x2) + c) / ((1.0 - a * x2) * (1.0 - a * x2) + c)).sqrt()
        }
        Kind::HighShelf => {
            let c = x2 * a / qq;
            a * (((1.0 - a * x2) * (1.0 - a * x2) + c) / ((a - x2) * (a - x2) + c)).sqrt()
        }
    };
    (20.0 * m.log10()) as f32
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Tuning {
    pub curve: Curve,
    pub balance: f32,
}

impl Tuning {
    pub const OFF: Tuning = Tuning { curve: Curve::FLAT, balance: 0.0 };
}

impl Default for Tuning {
    fn default() -> Tuning {
        Tuning::OFF
    }
}

#[derive(Clone, Copy, PartialEq, Debug, Default, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct Prefs {
    pub on: bool,
    pub manual: bool,
    pub tilt: i32,
    pub contour: i32,
    pub focused: bool,
    pub balance: f32,
    pub bands: [f32; 7],
}

impl Prefs {
    pub fn tuning(&self) -> Tuning {
        if !self.on {
            return Tuning::OFF;
        }
        let curve = if self.manual { Curve::manual(&self.bands) } else { Curve::tone(self.tilt, self.contour, self.focused) };
        Tuning { curve, balance: self.balance.clamp(-1.0, 1.0) }
    }
}

const GLIDE_FRAMES: usize = 64;
const GLIDE_RATE: f32 = 0.08;
const SETTLED_DB: f32 = 0.01;
const SETTLED_BALANCE: f32 = 0.0005;
const MIN_Q: f32 = 0.05;
const CEILING: f32 = 0.985;
const RELEASE_SECS: f32 = 0.1;

pub struct Processor {
    rate: f32,
    channels: usize,
    gain: [f32; N],
    q: [f32; N],
    preamp: f32,
    balance: f32,
    a1: [f32; N],
    a2: [f32; N],
    a3: [f32; N],
    m0: [f32; N],
    m1: [f32; N],
    m2: [f32; N],
    running: [bool; N],
    state: Vec<[f32; 2]>,
    frames: usize,
    active: Vec<usize>,
    trims: Vec<f32>,
    reduction: f32,
    release: f32,
}

impl Processor {
    pub fn new(rate: u32, channels: usize, start: Tuning) -> Processor {
        let mut p = Processor {
            rate: rate as f32,
            channels: channels.max(1),
            gain: start.curve.gains,
            q: start.curve.qs,
            preamp: start.curve.preamp,
            balance: start.balance,
            a1: [0.0; N],
            a2: [0.0; N],
            a3: [0.0; N],
            m0: [0.0; N],
            m1: [0.0; N],
            m2: [0.0; N],
            running: [false; N],
            state: vec![[0.0; 2]; N * channels.max(1)],
            frames: 0,
            active: Vec::with_capacity(N),
            trims: vec![1.0; channels.max(1)],
            reduction: 1.0,
            release: 1.0 - (-1.0 / (RELEASE_SECS * rate.max(1) as f32)).exp(),
        };
        p.prepare();
        p
    }

    pub fn idle(&self, t: &Tuning) -> bool {
        let flat = t.balance.abs() < SETTLED_BALANCE && t.curve.preamp.abs() < SETTLED_DB && t.curve.gains.iter().all(|g| g.abs() < SETTLED_DB);
        let settled = (self.balance - t.balance).abs() < SETTLED_BALANCE
            && (self.preamp - t.curve.preamp).abs() < SETTLED_DB
            && self.gain.iter().zip(&t.curve.gains).all(|(a, b)| (a - b).abs() < SETTLED_DB);
        flat && settled
    }

    pub fn frame(&mut self, frame: &mut [f32], t: &Tuning) {
        if self.frames % GLIDE_FRAMES == 0 {
            self.glide(t);
            self.prepare();
        }
        self.frames = self.frames.wrapping_add(1);
        let mut peak = 0f32;
        for (c, s) in frame.iter_mut().enumerate().take(self.channels) {
            let mut x = *s;
            for &slot in &self.active {
                let st = &mut self.state[c * N + slot];
                let (ic1, ic2) = (st[0], st[1]);
                let v3 = x - ic2;
                let v1 = self.a1[slot] * ic1 + self.a2[slot] * v3;
                let v2 = ic2 + self.a2[slot] * ic1 + self.a3[slot] * v3;
                st[0] = 2.0 * v1 - ic1;
                st[1] = 2.0 * v2 - ic2;
                x = self.m0[slot] * x + self.m1[slot] * v1 + self.m2[slot] * v2;
            }
            x *= self.trims[c];
            *s = x;
            peak = peak.max(x.abs());
        }
        let wanted = if peak > CEILING { CEILING / peak } else { 1.0 };
        self.reduction = if wanted < self.reduction { wanted } else { self.reduction + (wanted - self.reduction) * self.release };
        if self.reduction < 1.0 {
            frame.iter_mut().for_each(|s| *s *= self.reduction);
        }
    }

    pub fn reset(&mut self) {
        self.state.iter_mut().for_each(|s| *s = [0.0; 2]);
        self.reduction = 1.0;
    }

    fn glide(&mut self, t: &Tuning) {
        let lin = |c: f32, t: f32| c + (t - c) * GLIDE_RATE;
        for i in 0..N {
            self.gain[i] = lin(self.gain[i], t.curve.gains[i]);
            let (from, to) = (self.q[i].max(MIN_Q).ln(), t.curve.qs[i].max(MIN_Q).ln());
            self.q[i] = (from + (to - from) * GLIDE_RATE).exp();
        }
        self.preamp = lin(self.preamp, t.curve.preamp);
        self.balance = lin(self.balance, t.balance);
    }

    fn prepare(&mut self) {
        self.active.clear();
        for slot in 0..N {
            if self.gain[slot].abs() >= SETTLED_DB {
                self.coefficients(slot);
                self.active.push(slot);
                self.running[slot] = true;
            } else if self.running[slot] {
                (0..self.channels).for_each(|c| self.state[c * N + slot] = [0.0; 2]);
                self.running[slot] = false;
            }
        }
        let pre = 10f32.powf(self.preamp / 20.0);
        if self.channels == 2 {
            self.trims[0] = pre * (1.0 - self.balance).min(1.0);
            self.trims[1] = pre * (1.0 + self.balance).min(1.0);
        } else {
            self.trims.iter_mut().for_each(|t| *t = pre);
        }
    }

    fn coefficients(&mut self, slot: usize) {
        let (kind, f0) = SLOTS[slot];
        let a = 10f32.powf(self.gain[slot] / 40.0);
        let q = self.q[slot].max(MIN_Q);
        let base = (PI * f0.clamp(10.0, self.rate * 0.45) / self.rate).tan();
        let (g, k) = match kind {
            Kind::Bell => {
                let k = 1.0 / (q * a);
                (self.m0[slot], self.m1[slot], self.m2[slot]) = (1.0, k * (a * a - 1.0), 0.0);
                (base, k)
            }
            Kind::LowShelf => {
                let k = 1.0 / q;
                (self.m0[slot], self.m1[slot], self.m2[slot]) = (1.0, k * (a - 1.0), a * a - 1.0);
                (base / a.sqrt(), k)
            }
            Kind::HighShelf => {
                let k = 1.0 / q;
                (self.m0[slot], self.m1[slot], self.m2[slot]) = (a * a, k * (1.0 - a) * a, 1.0 - a * a);
                (base * a.sqrt(), k)
            }
        };
        let d = 1.0 / (1.0 + g * (g + k));
        self.a1[slot] = d;
        self.a2[slot] = g * d;
        self.a3[slot] = g * g * d;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn measure(t: Tuning, hz: f32) -> f32 {
        let rate = 48_000.0;
        let mut p = Processor::new(48_000, 1, t);
        let mut peak = 0f32;
        for i in 0..48_000 {
            let mut f = [0.25 * (2.0 * PI * hz * i as f32 / rate).sin()];
            p.frame(&mut f, &t);
            if i > 24_000 {
                peak = peak.max(f[0].abs());
            }
        }
        20.0 * (peak / 0.25).log10()
    }

    #[test]
    fn flat_is_idle_and_untouched() {
        let mut p = Processor::new(44_100, 2, Tuning::OFF);
        assert!(p.idle(&Tuning::OFF));
        let mut f = [0.3, -0.2];
        p.frame(&mut f, &Tuning::OFF);
        assert_eq!(f, [0.3, -0.2]);
    }

    #[test]
    fn bands_boost_where_they_sit_and_the_preamp_takes_half_back() {
        let mut bands = [0.0; 7];
        bands[3] = 6.0;
        let t = Tuning { curve: Curve::manual(&bands), balance: 0.0 };
        assert!((t.curve.preamp + 3.0).abs() < 0.2, "{}", t.curve.preamp);
        assert!((measure(t, 1_000.0) - 3.0).abs() < 0.5);
        assert!((measure(t, 60.0) + 3.0).abs() < 0.5);
    }

    #[test]
    fn tone_tilts_and_presets_are_recognised() {
        let t = Tuning { curve: Curve::tone(TONE_STEPS, 0, false), balance: 0.0 };
        assert!(measure(t, 10_000.0) > measure(t, 100.0) + 6.0);
        assert_eq!(preset_of(&PRESETS[4].1), Some(4));
        assert_eq!(preset_of(&[1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]), None);
    }

    #[test]
    fn balance_quietens_the_other_side_and_peaks_are_limited() {
        let t = Tuning { curve: Curve::FLAT, balance: 1.0 };
        let mut p = Processor::new(48_000, 2, t);
        let mut f = [0.5, 0.5];
        p.frame(&mut f, &t);
        assert!(f[0].abs() < 1e-6 && (f[1] - 0.5).abs() < 1e-6);
        let loud = Tuning { curve: Curve::manual(&[12.0; 7]), balance: 0.0 };
        let mut p = Processor::new(48_000, 1, loud);
        let worst = (0..48_000).fold(0f32, |m, i| {
            let mut f = [0.9 * (2.0 * PI * 60.0 * i as f32 / 48_000.0).sin()];
            p.frame(&mut f, &loud);
            m.max(f[0].abs())
        });
        assert!(worst <= CEILING + 1e-4, "{worst}");
    }
}
