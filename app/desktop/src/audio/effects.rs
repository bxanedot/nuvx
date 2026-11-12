use super::*;

#[derive(Clone, Copy)]
pub(super) enum Ramp {
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
    fn gain(self) -> f32 {
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

pub(super) const AUTOMIX_FADE: u32 = 1 << 31;
pub(super) const FX_MAX_FRAMES: usize = 48_000;

pub(super) struct MixFx {
    pub(super) ring: Vec<f32>,
    pub(super) channels: usize,
    pub(super) frame: usize,
    pub(super) channel: usize,
    pub(super) rate: f32,
    pub(super) low: [f32; 8],
    pub(super) body: [f32; 8],
    pub(super) low_k: f32,
    pub(super) body_k: f32,
    pub(super) beat: f32,
    pub(super) rhythmic: bool,
    pub(super) bass: f32,
    pub(super) treble: f32,
    pub(super) wet: f32,
    pub(super) flange: f32,
    pub(super) tail: Option<(f32, bool)>,
    pub(super) ticks: u64,
}

impl MixFx {
    pub(super) fn new(rate: u32, channels: u16) -> Option<Self> {
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

    pub(super) fn begin(&mut self, bpm: f32) {
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

    pub(super) fn process(&mut self, input: f32, ramp: Option<Ramp>) -> f32 {
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

    pub(super) fn reset(&mut self) {
        self.ring.fill(0.0);
        self.low.fill(0.0);
        self.body.fill(0.0);
        self.frame = 0;
        self.channel = 0;
        self.tail = None;
        self.ticks = 0;
    }
}

pub(super) struct Spatial<S> {
    inner: S,
    status: Arc<AudioStatus>,
    pending: Option<f32>,
    delay: [Vec<f32>; 2],
    at: usize,
    lowpass: [f32; 2],
}

impl<S: rodio::Source> Spatial<S> {
    const WIDTH: f32 = 2.5;
    const OUTPUT: f32 = 0.82;
    const CROSSFEED: f32 = 0.2;
    const LOWPASS: f32 = 0.3;
    const DELAY_MS: u32 = 15;

    pub(super) fn new(inner: S, status: Arc<AudioStatus>) -> Self {
        let n = (inner.sample_rate().get() * Self::DELAY_MS / 1000).max(1) as usize;
        Spatial { inner, status, pending: None, delay: [vec![0.0; n], vec![0.0; n]], at: 0, lowpass: [0.0; 2] }
    }

    fn widen(&mut self, l: f32, r: f32) -> (f32, f32) {
        let (mid, side) = ((l + r) * 0.5, (l - r) * 0.5 * Self::WIDTH);
        let i = self.at;
        self.lowpass[0] += Self::LOWPASS * (self.delay[1][i] - self.lowpass[0]);
        self.lowpass[1] += Self::LOWPASS * (self.delay[0][i] - self.lowpass[1]);
        self.delay[0][i] = l;
        self.delay[1][i] = r;
        self.at = (i + 1) % self.delay[0].len();
        ((mid + side + self.lowpass[0] * Self::CROSSFEED) * Self::OUTPUT, (mid - side + self.lowpass[1] * Self::CROSSFEED) * Self::OUTPUT)
    }
}

impl<S: rodio::Source> Iterator for Spatial<S> {
    type Item = f32;
    fn next(&mut self) -> Option<f32> {
        if let Some(r) = self.pending.take() {
            return Some(r);
        }
        let l = self.inner.next()?;
        if self.inner.channels().get() != 2 || !self.status.spatial.load(Ordering::Relaxed) {
            return Some(l);
        }
        let Some(r) = self.inner.next() else { return Some(l) };
        let (l, r) = self.widen(l, r);
        self.pending = Some(r);
        Some(l)
    }
}

impl<S: rodio::Source> rodio::Source for Spatial<S> {
    fn current_span_len(&self) -> Option<usize> {
        self.inner.current_span_len().map(|n| n + self.pending.is_some() as usize)
    }
    fn channels(&self) -> rodio::ChannelCount {
        self.inner.channels()
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        self.inner.sample_rate()
    }
    fn total_duration(&self) -> Option<Duration> {
        self.inner.total_duration()
    }
    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        self.inner.try_seek(pos)?;
        self.pending = None;
        self.delay.iter_mut().for_each(|d| d.fill(0.0));
        self.lowpass = [0.0; 2];
        Ok(())
    }
}

pub(super) struct Eq<S> {
    inner: S,
    status: Arc<AudioStatus>,
    eq: crate::eq::Processor,
    tuning: crate::eq::Tuning,
    frame: Vec<f32>,
    out: usize,
}

impl<S: rodio::Source> Eq<S> {
    pub(super) fn new(inner: S, status: Arc<AudioStatus>) -> Self {
        let tuning = status.eq.lock().map(|t| *t).unwrap_or(crate::eq::Tuning::OFF);
        let channels = inner.channels().get() as usize;
        let eq = crate::eq::Processor::new(inner.sample_rate().get(), channels, tuning);
        Eq { inner, status, eq, tuning, frame: Vec::with_capacity(channels), out: 0 }
    }
}

impl<S: rodio::Source> Iterator for Eq<S> {
    type Item = f32;
    fn next(&mut self) -> Option<f32> {
        if self.out == self.frame.len() {
            if let Ok(t) = self.status.eq.try_lock() {
                self.tuning = *t;
            }
            self.frame.clear();
            self.out = 0;
            let channels = self.inner.channels().get() as usize;
            self.frame.extend(self.inner.by_ref().take(channels));
            if self.frame.is_empty() {
                return None;
            }
            if self.frame.len() == channels && !self.eq.idle(&self.tuning) {
                self.eq.frame(&mut self.frame, &self.tuning);
            }
        }
        self.out += 1;
        Some(self.frame[self.out - 1])
    }
}

impl<S: rodio::Source> rodio::Source for Eq<S> {
    fn current_span_len(&self) -> Option<usize> {
        self.inner.current_span_len().map(|n| n + self.frame.len() - self.out)
    }
    fn channels(&self) -> rodio::ChannelCount {
        self.inner.channels()
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        self.inner.sample_rate()
    }
    fn total_duration(&self) -> Option<Duration> {
        self.inner.total_duration()
    }
    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        self.inner.try_seek(pos)?;
        self.frame.clear();
        self.out = 0;
        self.eq.reset();
        Ok(())
    }
}

pub(super) struct Meter<S> {
    pub(super) inner: S,
    pub(super) status: Arc<AudioStatus>,
    pub(super) fade_out: Arc<AtomicU32>,
    pub(super) seen_out: u32,
    pub(super) ramp: Option<Ramp>,
    pub(super) mix: bool,
    pub(super) acc: f32,
    pub(super) n: u32,
    pub(super) smooth: f32,
    pub(super) tempo: Tempo,
    pub(super) analysis: Arc<OnceLock<Option<f32>>>,
    pub(super) analysis_acc: f32,
    pub(super) fx: Option<MixFx>,
}

pub(super) struct Tempo {
    pub(super) prev: f32,
    pub(super) hist: [f32; 300],
    pub(super) count: usize,
    pub(super) done: bool,
    pub(super) hop_ms: f32,
}

impl Tempo {
    pub(super) fn new(hop_ms: f32) -> Self {
        Self { prev: 0.0, hist: [0.0; 300], count: 0, done: false, hop_ms }
    }

    pub(super) fn push(&mut self, e: f32) -> Option<f32> {
        if self.done {
            return None;
        }
        let flux = (e - self.prev).max(0.0);
        self.prev = e;
        self.hist[self.count] = flux;
        self.count += 1;
        if self.count < self.hist.len() {
            return None;
        }
        self.done = true;
        self.estimate()
    }

    fn estimate(&self) -> Option<f32> {
        let n = self.hist.len();
        let mean = self.hist.iter().sum::<f32>() / n as f32;
        let power = self.hist.iter().map(|e| (e - mean).powi(2)).sum::<f32>() / n as f32;
        if power <= 1e-12 {
            return None;
        }
        let lo = ((0.30 * 1000.0 / self.hop_ms) as usize).max(4);
        let hi = ((1.20 * 1000.0 / self.hop_ms) as usize).min(n / 2);
        let mut best = (0.0f32, 0usize);
        for lag in lo..=hi {
            let c: f32 = (lag..n).map(|i| (self.hist[i] - mean) * (self.hist[i - lag] - mean)).sum::<f32>() / (n - lag) as f32;
            let secs = lag as f32 * self.hop_ms / 1000.0;
            let prior = (-(secs / 0.5).ln().powi(2) / (2.0 * 0.4 * 0.4)).exp();
            let score = c / power * prior;
            if score > best.0 {
                best = (score, lag);
            }
        }
        if best.0 < 0.08 {
            return None;
        }
        let mut bpm = 60_000.0 / (best.1 as f32 * self.hop_ms);
        while bpm < 70.0 {
            bpm *= 2.0;
        }
        while bpm > 180.0 {
            bpm /= 2.0;
        }
        Some(bpm)
    }
}

impl<S: rodio::Source> Meter<S> {
    pub(super) fn new(inner: S, status: Arc<AudioStatus>, fade_in_ms: u32, fade_out: Arc<AtomicU32>, mix: bool, analysis: Arc<OnceLock<Option<f32>>>) -> Self {
        let hop_ms = 2048.0 / (inner.sample_rate().get() as f32 * inner.channels().get() as f32) * 1000.0;
        let mut tempo = Tempo::new(hop_ms);
        tempo.done = analysis.get().is_some();
        let mut fx = if status.automix.load(Ordering::Relaxed) { MixFx::new(inner.sample_rate().get(), inner.channels().get()) } else { None };
        if let Some(fx) = &mut fx {
            fx.begin(analysis.get().copied().flatten().unwrap_or(0.0));
        }
        let mut m = Self { inner, status, fade_out, seen_out: 0, ramp: None, mix, acc: 0.0, n: 0, smooth: 0.0, tempo, analysis, analysis_acc: 0.0, fx };
        if fade_in_ms > 0 {
            m.ramp = Some(if mix { Ramp::MixIn { at: 0, len: m.samples(fade_in_ms) } } else { Ramp::In { at: 0, len: m.samples(fade_in_ms) } });
        }
        m
    }

    fn samples(&self, ms: u32) -> u32 {
        let per_sec = self.inner.sample_rate().get() as u64 * self.inner.channels().get() as u64;
        (per_sec * ms as u64 / 1000).max(1) as u32
    }
}

impl<S: rodio::Source> Iterator for Meter<S> {
    type Item = rodio::Sample;
    fn next(&mut self) -> Option<Self::Item> {
        let out = self.fade_out.load(Ordering::Relaxed);
        if out != self.seen_out {
            self.seen_out = out;
            let from = self.ramp.map_or(1.0, Ramp::gain);
            let len = self.samples(out & !AUTOMIX_FADE);
            self.mix = out & AUTOMIX_FADE != 0;
            if self.mix && let Some(fx) = &mut self.fx {
                fx.begin(self.analysis.get().copied().flatten().unwrap_or(0.0));
            }
            self.ramp = Some(if self.mix { Ramp::MixOut { from, at: 0, len } } else { Ramp::Out { from, at: 0, len } });
        }
        if let Some(Ramp::Out { at, len, .. } | Ramp::MixOut { at, len, .. }) = self.ramp
            && at >= len
        {
            return None;
        }
        let mut s = self.inner.next()?;
        if !self.tempo.done {
            self.analysis_acc += s * s;
        }
        if let Some(fx) = &mut self.fx {
            s = fx.process(s, self.ramp);
        }
        if let Some(ramp) = &mut self.ramp {
            s *= ramp.gain();
            match ramp {
                Ramp::In { at, len } | Ramp::MixIn { at, len } => {
                    *at += 1;
                    if *at >= *len {
                        self.ramp = None;
                    }
                }
                Ramp::Out { at, .. } | Ramp::MixOut { at, .. } => *at += 1,
            }
        }
        self.acc += s * s;
        self.n += 1;
        if self.n == 2048 {
            let rms = (self.acc / 2048.0).sqrt();
            let k = if rms > self.smooth { 0.6 } else { 0.08 };
            self.smooth += (rms - self.smooth) * k;
            if self.seen_out == 0 {
                self.status.level.store(self.smooth.min(1.0).to_bits(), Ordering::Relaxed);
                if self.status.automix.load(Ordering::Relaxed) && !self.tempo.done {
                    let bpm = self.tempo.push((self.analysis_acc / 2048.0).sqrt());
                    if self.tempo.done {
                        let _ = self.analysis.set(bpm);
                        self.status.bpm.store(bpm.unwrap_or(0.0).to_bits(), Ordering::Relaxed);
                        log::debug!("audio: tempo analysis complete: {bpm:?}");
                    }
                }
            }
            self.acc = 0.0;
            self.analysis_acc = 0.0;
            self.n = 0;
        }
        Some(s)
    }
}

impl<S: rodio::Source> rodio::Source for Meter<S> {
    fn current_span_len(&self) -> Option<usize> {
        self.inner.current_span_len()
    }
    fn channels(&self) -> rodio::ChannelCount {
        self.inner.channels()
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        self.inner.sample_rate()
    }
    fn total_duration(&self) -> Option<Duration> {
        self.inner.total_duration()
    }
    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        self.inner.try_seek(pos)?;
        self.ramp = None;
        self.acc = 0.0;
        self.analysis_acc = 0.0;
        self.n = 0;
        if let Some(fx) = &mut self.fx {
            fx.reset();
        }
        Ok(())
    }
}
