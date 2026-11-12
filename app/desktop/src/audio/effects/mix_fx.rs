#[derive(Clone, Copy)]
pub enum Ramp {
    In { at: u32, len: u32 },
    Out { from: f32, at: u32, len: u32 },
    MixIn { at: u32, len: u32 },
    MixOut { from: f32, at: u32, len: u32 },
}

fn smoothstep(x: f32) -> f32 {
    let x = x.clamp(0.0, 1.0);
    x * x * (3.0 - 2.0 * x)
}

impl Ramp {
    pub(in crate::audio) fn gain(self) -> f32 {
        match self {
            Ramp::In { at, len } => at as f32 / len as f32,
            Ramp::Out { from, at, len } => from * (1.0 - at as f32 / len as f32),
            Ramp::MixIn { at, len } => mix_handoff(at as f32 / len as f32),
            Ramp::MixOut { from, at, len } => from * (1.0 - mix_handoff(at as f32 / len as f32)),
        }
    }
}

fn mix_handoff(x: f32) -> f32 {
    smoothstep((x - 0.3) / 0.7)
}

pub const AUTOMIX_FADE: u32 = 1 << 31;
pub const FX_MAX_FRAMES: usize = 48_000;

pub struct MixFx {
    pub(in crate::audio) ring: Vec<f32>,
    pub(in crate::audio) channels: usize,
    pub(in crate::audio) frame: usize,
    pub(in crate::audio) channel: usize,
    pub(in crate::audio) rate: f32,
    pub(in crate::audio) low: [f32; 8],
    pub(in crate::audio) body: [f32; 8],
    pub(in crate::audio) low_k: f32,
    pub(in crate::audio) body_k: f32,
    pub(in crate::audio) beat: f32,
    pub(in crate::audio) rhythmic: bool,
    pub(in crate::audio) bass: f32,
    pub(in crate::audio) treble: f32,
    pub(in crate::audio) wet: f32,
    pub(in crate::audio) flange: f32,
    pub(in crate::audio) tail: Option<(f32, bool)>,
    pub(in crate::audio) ticks: u64,
}

impl MixFx {
    pub(in crate::audio) fn new(rate: u32, channels: u16) -> Option<Self> {
        if channels > 8 {
            return None;
        }
        let frames = ((rate as usize * 3) / 4).clamp(2, FX_MAX_FRAMES);
        Some(Self {
            ring: vec![0.0; frames * channels as usize],
            channels: channels as usize,
            frame: 0,
            channel: 0,
            rate: rate as f32,
            low: [0.0; 8],
            body: [0.0; 8],
            low_k: 1.0 - (-std::f32::consts::TAU * 180.0 / rate as f32).exp(),
            body_k: 1.0 - (-std::f32::consts::TAU * 4000.0 / rate as f32).exp(),
            beat: 0.5 * rate as f32,
            rhythmic: false,
            bass: 1.0,
            treble: 1.0,
            wet: 0.0,
            flange: 0.0,
            tail: None,
            ticks: 0,
        })
    }

    pub(in crate::audio) fn begin(&mut self, bpm: f32) {
        self.rhythmic = (70.0..=180.0).contains(&bpm);
        self.beat = self.rate * if self.rhythmic { 60.0 / bpm } else { 0.5 };
        while self.beat > (self.ring.len() / self.channels - 1) as f32 {
            self.beat *= 0.5;
        }
        self.beat = self.beat.max(1.0);
        self.ticks = 0;
    }

    fn tap(&self, delay: f32) -> f32 {
        let frames = self.ring.len() / self.channels;
        let delay = delay.clamp(1.0, (frames - 1) as f32);
        let whole = delay as usize;
        let frac = delay - whole as f32;
        let a = (self.frame + frames - whole) % frames;
        let b = (a + frames - 1) % frames;
        self.ring[a * self.channels + self.channel] * (1.0 - frac) + self.ring[b * self.channels + self.channel] * frac
    }

    pub(in crate::audio) fn process(&mut self, input: f32, ramp: Option<Ramp>) -> f32 {
        let c = self.channel;
        self.low[c] += self.low_k * (input - self.low[c]);
        self.body[c] += self.body_k * (input - self.body[c]);
        let blend = match ramp {
            Some(Ramp::MixIn { at, len }) => Some((at as f32 / len as f32, false)),
            Some(Ramp::MixOut { at, len, .. }) => Some((at as f32 / len as f32, true)),
            Some(Ramp::Out { .. }) => self.tail,
            _ => None,
        };
        self.tail = blend;
        let mut result = input;
        let mut history = input;
        if let Some((x, outgoing)) = blend {
            if c == 0 && self.ticks % 32 == 0 {
                let handoff = mix_handoff(x);
                self.bass = if outgoing { 1.0 - 0.85 * smoothstep((x - 0.35) / 0.3) } else { 0.15 + 0.85 * smoothstep((x - 0.58) / 0.25) };
                self.treble = if outgoing { 1.0 - 0.5 * handoff } else { 0.7 + 0.3 * handoff };
                self.wet = if outgoing { 0.4 * smoothstep((x - 0.3) / 0.35) } else { 0.0 };
                let phase = self.ticks as f32 / (self.beat * 2.0) * std::f32::consts::TAU;
                self.flange = self.rate * (0.0006 + 0.0026 * (0.5 + 0.5 * phase.sin()));
            }
            result = input + (self.bass - 1.0) * self.low[c] + (self.treble - 1.0) * (input - self.body[c]);
            if outgoing {
                let flanged = 0.5 * (input + self.tap(self.flange));
                let tail = if self.rhythmic {
                    0.45 * self.tap(self.beat * 0.5) + 0.55 * flanged
                } else {
                    let reverb = (self.tap(self.rate * 0.071) + self.tap(self.rate * 0.113) + self.tap(self.rate * 0.173)) / 3.0;
                    0.4 * reverb + 0.6 * flanged
                };
                history = input * 0.7 + tail * 0.3;
                result = result * (1.0 - self.wet) + tail * self.wet;
            }
        }
        self.ring[self.frame * self.channels + c] = history;
        self.channel += 1;
        if self.channel == self.channels {
            self.channel = 0;
            self.frame = (self.frame + 1) % (self.ring.len() / self.channels);
            self.ticks += 1;
        }
        result
    }

    pub(in crate::audio) fn reset(&mut self) {
        self.ring.fill(0.0);
        self.low.fill(0.0);
        self.body.fill(0.0);
        self.frame = 0;
        self.channel = 0;
        self.tail = None;
        self.ticks = 0;
    }
}
