use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, OnceLock};
use std::time::{Duration, Instant};

use crossbeam_channel::{Receiver, Sender, unbounded};
use rodio::cpal::traits::{DeviceTrait, HostTrait};
use rodio::mixer::{Mixer, MixerSource};
use rodio::{DeviceSinkBuilder, MixerDeviceSink, Player};

use crate::stream::{self, Shared};

pub use crate::stream::TrackDecoder;

pub enum AudioCmd {
    Start { generation: u64, decoder: TrackDecoder, shared: Arc<Shared>, at: Duration },
    Replace { generation: u64, seek: u64, decoder: TrackDecoder, at: Duration, seekable: bool },
    Pause,
    Resume,
    Stop,
    Handoff,
    Seek(Duration),
    SeekLive { pos: Duration, at: std::time::Instant },
    Volume(f32),
    DeviceLost,
    SetDevice(Option<String>),
}

pub enum AudioEvent {
    Finished { generation: u64 },
    Ending { generation: u64 },
    Interrupted { generation: u64, at: Duration, reason: String },
    Error(String),
}

static EPOCH: LazyLock<Instant> = LazyLock::new(Instant::now);

fn now_ms() -> u64 {
    EPOCH.elapsed().as_millis() as u64
}

#[derive(Default)]
pub struct AudioStatus {
    pub position_ms: AtomicU64,
    sampled_at: AtomicU64,
    pub duration_ms: AtomicU64,
    pub playing: AtomicBool,
    pub wanted_gen: AtomicU64,
    volume: AtomicU32,
    pub normalize: AtomicBool,
    spatial: AtomicBool,
    eq: std::sync::Mutex<crate::eq::Tuning>,
    log_volume: AtomicBool,
    level: AtomicU32,
    latency_ms: AtomicU32,
    crossfade_ms: AtomicU32,
    automix: AtomicBool,
    bpm: AtomicU32,
    mix_generation: AtomicU64,
    mix_duration_ms: AtomicU32,
    mix_active: AtomicBool,
    outgoing_position_ms: AtomicU64,
    outgoing_sampled_at: AtomicU64,
    outgoing_playing: AtomicBool,
}

impl AudioStatus {
    pub fn position(&self) -> Duration {
        Duration::from_millis(self.position_ms.load(Ordering::Relaxed))
    }

    pub fn position_smooth(&self) -> Duration {
        let pos = self.position_ms.load(Ordering::Relaxed);
        if !self.playing.load(Ordering::Relaxed) {
            return Duration::from_millis(pos);
        }
        let age = now_ms().saturating_sub(self.sampled_at.load(Ordering::Relaxed)).min(250);
        Duration::from_millis(pos + age)
    }

    pub fn outgoing_position(&self) -> Duration {
        let pos = self.outgoing_position_ms.load(Ordering::Relaxed);
        let age = if self.outgoing_playing() { now_ms().saturating_sub(self.outgoing_sampled_at.load(Ordering::Relaxed)).min(250) } else { 0 };
        Duration::from_millis(pos + age)
    }

    pub fn outgoing_playing(&self) -> bool {
        self.outgoing_playing.load(Ordering::Relaxed)
    }

    fn sample_outgoing(&self, pos: Duration, playing: bool) {
        self.outgoing_position_ms.store(pos.as_millis() as u64, Ordering::Relaxed);
        self.outgoing_sampled_at.store(now_ms(), Ordering::Relaxed);
        self.outgoing_playing.store(playing, Ordering::Relaxed);
    }

    pub fn duration(&self) -> Option<Duration> {
        let d = self.duration_ms.load(Ordering::Relaxed);
        (d > 0).then(|| Duration::from_millis(d))
    }

    pub fn mix_secs(&self, length: Option<Duration>) -> f32 {
        if !self.automix.load(Ordering::Relaxed) {
            return 0.0;
        }
        mix_ms(self, length) as f32 / 1000.0
    }

    pub fn mix_duration(&self, generation: u64) -> Option<f32> {
        (self.mix_generation.load(Ordering::Acquire) == generation)
            .then(|| self.mix_duration_ms.load(Ordering::Relaxed) as f32 / 1000.0)
    }

    pub fn mixing(&self) -> bool {
        self.mix_active.load(Ordering::Relaxed)
    }

    pub fn latency(&self) -> Duration {
        Duration::from_millis(self.latency_ms.load(Ordering::Relaxed) as u64)
    }

    pub fn level(&self) -> f32 {
        f32::from_bits(self.level.load(Ordering::Relaxed))
    }

    fn set_position(&self, ms: u64) {
        self.position_ms.store(ms, Ordering::Relaxed);
        self.sampled_at.store(now_ms(), Ordering::Relaxed);
    }
}

#[derive(Clone, Copy)]
enum Ramp {
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

const AUTOMIX_FADE: u32 = 1 << 31;
const FX_MAX_FRAMES: usize = 48_000;

struct MixFx {
    ring: Vec<f32>,
    channels: usize,
    frame: usize,
    channel: usize,
    rate: f32,
    low: [f32; 8],
    body: [f32; 8],
    low_k: f32,
    body_k: f32,
    beat: f32,
    rhythmic: bool,
    bass: f32,
    treble: f32,
    wet: f32,
    flange: f32,
    tail: Option<(f32, bool)>,
    ticks: u64,
}

impl MixFx {
    fn new(rate: u32, channels: u16) -> Option<Self> {
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

    fn begin(&mut self, bpm: f32) {
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

    fn process(&mut self, input: f32, ramp: Option<Ramp>) -> f32 {
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

    fn reset(&mut self) {
        self.ring.fill(0.0);
        self.low.fill(0.0);
        self.body.fill(0.0);
        self.frame = 0;
        self.channel = 0;
        self.tail = None;
        self.ticks = 0;
    }
}

struct Spatial<S> {
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

    fn new(inner: S, status: Arc<AudioStatus>) -> Self {
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

struct Eq<S> {
    inner: S,
    status: Arc<AudioStatus>,
    eq: crate::eq::Processor,
    tuning: crate::eq::Tuning,
    frame: Vec<f32>,
    out: usize,
}

impl<S: rodio::Source> Eq<S> {
    fn new(inner: S, status: Arc<AudioStatus>) -> Self {
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

struct Meter<S> {
    inner: S,
    status: Arc<AudioStatus>,
    fade_out: Arc<AtomicU32>,
    seen_out: u32,
    ramp: Option<Ramp>,
    mix: bool,
    acc: f32,
    n: u32,
    smooth: f32,
    tempo: Tempo,
    analysis: Arc<OnceLock<Option<f32>>>,
    analysis_acc: f32,
    fx: Option<MixFx>,
}

struct Tempo {
    prev: f32,
    hist: [f32; 300],
    count: usize,
    done: bool,
    hop_ms: f32,
}

impl Tempo {
    fn new(hop_ms: f32) -> Self {
        Self { prev: 0.0, hist: [0.0; 300], count: 0, done: false, hop_ms }
    }

    fn push(&mut self, e: f32) -> Option<f32> {
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
    fn new(inner: S, status: Arc<AudioStatus>, fade_in_ms: u32, fade_out: Arc<AtomicU32>, mix: bool, analysis: Arc<OnceLock<Option<f32>>>) -> Self {
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

#[derive(Clone)]
pub struct AudioHandle {
    tx: Sender<AudioCmd>,
    pub status: Arc<AudioStatus>,
}

impl AudioHandle {
    pub fn send(&self, cmd: AudioCmd) {
        let _ = self.tx.send(cmd);
    }

    pub fn volume(&self) -> f32 {
        f32::from_bits(self.status.volume.load(Ordering::Relaxed))
    }

    pub fn set_normalize(&self, on: bool) {
        self.status.normalize.store(on, Ordering::Relaxed);
        self.send(AudioCmd::Volume(self.volume()));
    }

    pub fn set_log_volume(&self, on: bool) {
        if self.status.log_volume.swap(on, Ordering::Relaxed) != on {
            let v = self.volume();
            self.status.volume.store(if on { v.cbrt() } else { v.powi(3) }.to_bits(), Ordering::Relaxed);
        }
        self.send(AudioCmd::Volume(self.volume()));
    }

    pub fn set_spatial(&self, on: bool) {
        self.status.spatial.store(on, Ordering::Relaxed);
    }

    pub fn set_eq(&self, tuning: crate::eq::Tuning) {
        if let Ok(mut t) = self.status.eq.lock() {
            *t = tuning;
        }
    }

    pub fn set_crossfade(&self, secs: u32) {
        self.status.crossfade_ms.store(secs * 1000, Ordering::Relaxed);
    }

    pub fn set_automix(&self, on: bool) {
        self.status.automix.store(on, Ordering::Relaxed);
    }

    pub fn set_volume(&self, v: f32) {
        self.status.volume.store(v.to_bits(), Ordering::Relaxed);
        self.send(AudioCmd::Volume(v));
    }
}

struct SeekJob {
    shared: Arc<Shared>,
    pos: Duration,
    live: Option<(Duration, std::time::Instant)>,
    generation: u64,
    seek: u64,
}

fn build_seek(j: SeekJob, tx: &Sender<AudioCmd>, events: &Sender<AudioEvent>) {
    let SeekJob { shared, pos, live, generation, seek } = j;
    let seekable = !pos.is_zero();
    let res = stream::decoder(shared.clone(), seekable).map_err(|e| e.to_string()).and_then(|mut d| {
        let pos = live.map_or(pos, |(p, at)| p + at.elapsed());
        if seekable {
            rodio::Source::try_seek(&mut d, pos).map_err(|e| e.to_string())?;
        }
        Ok((d, pos))
    });
    match res {
        Ok((decoder, pos)) => {
            let _ = tx.send(AudioCmd::Replace { generation, seek, decoder, at: pos, seekable });
        }
        Err(e) => match shared.error() {
            Some(reason) => {
                let _ = events.send(AudioEvent::Interrupted { generation, at: pos, reason });
            }
            None => {
                let _ = events.send(AudioEvent::Error(format!("Seek failed: {e}")));
            }
        },
    }
}

struct Fading {
    player: Player,
    offset: Duration,
    shared: Option<Arc<Shared>>,
    out: Arc<AtomicU32>,
    waiting: Option<(Instant, Option<Duration>)>,
    natural: bool,
}

impl Fading {
    fn start(&mut self, crossfade: u32, automix: bool) -> u32 {
        let left = self.waiting.take().and_then(|(at, left)| Some(left?.saturating_sub(at.elapsed())));
        let len = crossfade.min(left.map_or(u32::MAX, |l| l.as_millis() as u32)).max(QUICK_FADE_MS);
        self.out.store(len | if automix { AUTOMIX_FADE } else { 0 }, Ordering::Relaxed);
        len
    }
}

impl Drop for Fading {
    fn drop(&mut self) {
        if let Some(s) = &self.shared {
            s.cancel();
        }
    }
}

const QUICK_FADE_MS: u32 = 40;

const HANDOFF_WAIT: Duration = Duration::from_secs(3);

const AUTOMIX_BARS: f32 = 8.0;
const AUTOMIX_BARS_LONG: f32 = 16.0;
const AUTOMIX_MAX_BARS: f32 = 32.0;
const AUTOMIX_MAX_MS: u32 = 45_000;
const AUTOMIX_MIN_MS: u32 = 4_000;

fn mix_ms(status: &AudioStatus, length: Option<Duration>) -> u32 {
    let crossfade = status.crossfade_ms.load(Ordering::Relaxed);
    if !status.automix.load(Ordering::Relaxed) {
        return crossfade;
    }
    let bpm = f32::from_bits(status.bpm.load(Ordering::Relaxed));
    if !(40.0..=240.0).contains(&bpm) {
        return crossfade;
    }
    let bars = if length.is_some_and(|d| d >= Duration::from_secs(240)) { AUTOMIX_BARS_LONG } else { AUTOMIX_BARS };
    let ms = (bars.min(AUTOMIX_MAX_BARS) * 4.0 * 60_000.0 / bpm) as u32;
    let ms = ms.clamp(AUTOMIX_MIN_MS, AUTOMIX_MAX_MS);
    length.map_or(ms, |d| ms.min((d.as_millis() as u32 / 3).max(QUICK_FADE_MS)))
}

fn slider_gain(status: &AudioStatus, v: f32) -> f32 {
    if status.log_volume.load(Ordering::Relaxed) { v.powi(3) } else { v }
}

fn norm_gain(status: &AudioStatus, shared: Option<&Shared>) -> f32 {
    match shared.and_then(|s| s.loudness_db) {
        Some(db) if db > 0.0 && status.normalize.load(Ordering::Relaxed) => 10f32.powf(-db / 20.0),
        _ => 1.0,
    }
}

struct Output {
    mixer: Mixer,
    slot: Arc<std::sync::Mutex<Option<MixerSource>>>,
    sink: Option<MixerDeviceSink>,
    device: Option<String>,
    preferred: Option<String>,
    lost: Arc<AtomicBool>,
    checked: Instant,
}

impl Output {
    fn new(tx: Sender<AudioCmd>, status: &AudioStatus, preferred: Option<String>) -> Self {
        let config = target(preferred.as_deref()).and_then(|d| d.default_output_config().ok());
        let channels = config.as_ref().and_then(|c| std::num::NonZero::new(c.channels())).unwrap_or(std::num::NonZero::new(2).unwrap());
        let rate = config.as_ref().and_then(|c| std::num::NonZero::new(c.sample_rate())).unwrap_or(std::num::NonZero::new(48_000).unwrap());
        let (mixer, source) = rodio::mixer::mixer(channels, rate);
        let lost = Arc::new(AtomicBool::new(false));
        let mut out = Self { mixer, slot: Arc::new(std::sync::Mutex::new(Some(source))), sink: None, device: None, preferred, lost, checked: Instant::now() };
        out.open(tx, status);
        out
    }

    fn open(&mut self, tx: Sender<AudioCmd>, status: &AudioStatus) -> bool {
        self.sink = None;
        self.device = None;
        self.checked = Instant::now();
        let Some(device) = target(self.preferred.as_deref()) else {
            return false;
        };
        let id = device.id().map(|i| i.to_string()).ok();
        let lost = self.lost.clone();
        lost.store(false, Ordering::Relaxed);
        let on_error = move |e: rodio::cpal::StreamError| {
            log::warn!("audio: output device error: {e}");
            if !lost.swap(true, Ordering::Relaxed) {
                let _ = tx.send(AudioCmd::DeviceLost);
            }
        };
        let sink = match DeviceSinkBuilder::from_device(device).and_then(|b| b.with_error_callback(on_error).open_sink_or_fallback()) {
            Ok(s) => s,
            Err(e) => {
                log::warn!("audio: couldn't open the output device: {e}");
                return false;
            }
        };
        let waited = Instant::now();
        let source = loop {
            if let Some(source) = self.slot.lock().unwrap().take() {
                break source;
            }
            if waited.elapsed() > Duration::from_secs(1) {
                log::warn!("audio: mixer still held by the old device");
                return false;
            }
            std::thread::sleep(Duration::from_millis(5));
        };
        let mut sink = sink;
        sink.log_on_drop(false);
        let channels = rodio::Source::channels(&source).get();
        sink.mixer().add(Bridge { inner: Some(source), slot: self.slot.clone(), channels, n: 0 });
        let rate = sink.config().sample_rate().get().max(1);
        let buffer_ms = match sink.config().buffer_size() {
            rodio::cpal::BufferSize::Fixed(n) => *n as u64 * 1000 / rate as u64,
            rodio::cpal::BufferSize::Default => 0,
        };
        status.latency_ms.store((buffer_ms + 10) as u32, Ordering::Relaxed);
        log::info!("audio: output device {:?} at {} Hz", id, rate);
        self.sink = Some(sink);
        self.device = id;
        true
    }

    fn default_changed(&mut self) -> bool {
        self.checked = Instant::now();
        let want = target(self.preferred.as_deref()).and_then(|d| d.id().ok()).map(|i| i.to_string());
        want.is_some() && want != self.device
    }
}

fn target(preferred: Option<&str>) -> Option<rodio::cpal::Device> {
    let host = rodio::cpal::default_host();
    if let Some(want) = preferred
        && let Some(d) = host.output_devices().ok().and_then(|mut all| all.find(|d| d.id().is_ok_and(|i| i.to_string() == want)))
    {
        return Some(d);
    }
    host.default_output_device()
}

#[derive(Clone, PartialEq)]
pub struct OutputDevice {
    pub id: String,
    pub name: String,
}

pub fn output_devices() -> Vec<OutputDevice> {
    let Ok(all) = rodio::cpal::default_host().output_devices() else { return Vec::new() };
    let mut out: Vec<OutputDevice> = all
        .filter_map(|d| {
            let id = d.id().ok()?.to_string();
            let desc = d.description().ok()?;
            let name = desc.extended().first().cloned().unwrap_or_else(|| desc.name().to_owned());
            Some(OutputDevice { id, name })
        })
        .collect();
    out.sort_by_key(|a| a.name.to_lowercase());
    out
}

struct Bridge {
    inner: Option<MixerSource>,
    slot: Arc<std::sync::Mutex<Option<MixerSource>>>,
    channels: u16,
    n: u64,
}

impl Iterator for Bridge {
    type Item = rodio::Sample;
    fn next(&mut self) -> Option<Self::Item> {
        self.n += 1;
        Some(self.inner.as_mut().and_then(Iterator::next).unwrap_or(0.0))
    }
}

impl rodio::Source for Bridge {
    fn current_span_len(&self) -> Option<usize> {
        None
    }
    fn channels(&self) -> rodio::ChannelCount {
        self.inner.as_ref().map_or(std::num::NonZero::new(self.channels.max(1)).unwrap(), |s| s.channels())
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        self.inner.as_ref().map_or(std::num::NonZero::new(48_000).unwrap(), |s| s.sample_rate())
    }
    fn total_duration(&self) -> Option<Duration> {
        None
    }
}

impl Drop for Bridge {
    fn drop(&mut self) {
        if let Some(mut source) = self.inner.take() {
            while !self.n.is_multiple_of(self.channels.max(1) as u64) {
                source.next();
                self.n += 1;
            }
            *self.slot.lock().unwrap() = Some(source);
        }
    }
}

const DEVICE_CHECK: Duration = Duration::from_secs(1);

pub fn spawn(volume: f32, log_volume: bool, device: Option<String>, events: Sender<AudioEvent>, wake: impl Fn() + Send + 'static) -> AudioHandle {
    let (tx, rx) = unbounded();
    let status = Arc::new(AudioStatus::default());
    status.volume.store(volume.to_bits(), Ordering::Relaxed);
    status.log_volume.store(log_volume, Ordering::Relaxed);
    let st = status.clone();
    std::thread::Builder::new()
        .name("audio".into())
        .spawn({
            let tx = tx.clone();
            move || run(rx, tx, st, volume, device, events, wake)
        })
        .expect("spawn audio thread");
    AudioHandle { tx, status }
}

fn run(
    rx: Receiver<AudioCmd>,
    tx: Sender<AudioCmd>,
    status: Arc<AudioStatus>,
    volume: f32,
    device: Option<String>,
    events: Sender<AudioEvent>,
    wake: impl Fn(),
) {
    let mut output = Output::new(tx.clone(), &status, device);
    if output.sink.is_none() {
        let _ = events.send(AudioEvent::Error("No audio output device".into()));
        wake();
    }
    let mut player = Player::connect_new(&output.mixer);
    player.set_volume(slider_gain(&status, volume));
    let mut fade_out = Arc::new(AtomicU32::new(0));
    let mut fading: Vec<Fading> = Vec::new();
    let mut ending_sent = false;
    let mut length: Option<Duration> = None;

    let mut current: u64 = 0;
    let mut shared: Option<Arc<Shared>> = None;
    let mut offset = Duration::ZERO;
    let mut seekable = false;
    let mut seek_seq: u64 = 0;
    let seek_job: Arc<std::sync::Mutex<Option<SeekJob>>> = Default::default();
    let seek_busy = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let mut dev_lost = std::env::var("NUVX_DEVICE_LOST").ok().and_then(|v| v.parse::<f32>().ok()).map(Duration::from_secs_f32);
    loop {
        let timeout = if (current != 0 || !fading.is_empty()) && !player.is_paused() { Duration::from_millis(100) } else { Duration::from_secs(3600) };
        match rx.recv_timeout(timeout) {
            Ok(AudioCmd::Start { generation, decoder, shared: s, at }) => {
                if generation != status.wanted_gen.load(Ordering::Relaxed) {
                    log::debug!("audio: dropping stale generation {generation}");
                    continue;
                }
                log::debug!("audio: start generation {generation}");
                if output.sink.is_none() || output.default_changed() {
                    output.open(tx.clone(), &status);
                }
                player.clear();
                let gain = norm_gain(&status, Some(&s));
                log::info!("audio: loudness {:?} dB, normalization gain {gain:.3}", s.loudness_db);
                player.set_volume(slider_gain(&status, f32::from_bits(status.volume.load(Ordering::Relaxed))) * gain);
                fading.retain(|f| !f.player.empty());
                let skip_len = status.crossfade_ms.load(Ordering::Relaxed);
                let natural_len = mix_ms(&status, length);
                let mut fade_in = 0;
                for f in fading.iter_mut().filter(|f| f.waiting.is_some()) {
                    fade_in = f.start(if f.natural { natural_len } else { skip_len }, status.automix.load(Ordering::Relaxed));
                    log::debug!("audio: crossfade into generation {generation} over {fade_in} ms");
                }
                fade_out = Arc::new(AtomicU32::new(0));
                let mix = fade_in > 0 && status.automix.load(Ordering::Relaxed);
                status.bpm.store(s.tempo.get().copied().flatten().unwrap_or(0.0).to_bits(), Ordering::Relaxed);
                status.mix_duration_ms.store(fade_in, Ordering::Relaxed);
                status.mix_active.store(mix, Ordering::Relaxed);
                status.mix_generation.store(generation, Ordering::Release);
                player.append(Meter::new(Eq::new(Spatial::new(decoder, status.clone()), status.clone()), status.clone(), fade_in, fade_out.clone(), mix, s.tempo.clone()));
                player.play();
                if s.duration_ms > 0 {
                    status.duration_ms.store(s.duration_ms, Ordering::Relaxed);
                }
                length = status.duration();
                if let Some(old) = shared.replace(s.clone())
                    && !Arc::ptr_eq(&old, &s)
                {
                    old.cancel();
                }
                current = generation;
                offset = at;
                seekable = !at.is_zero();
                ending_sent = false;
                status.playing.store(true, Ordering::Relaxed);
            }
            Ok(AudioCmd::Replace { generation, seek, decoder, at, seekable: s }) => {
                if generation != current || seek != seek_seq {
                    continue;
                }
                log::debug!("audio: seek to {at:?} via new decoder (seekable: {s})");
                seekable = s;
                let paused = player.is_paused();
                player.clear();
                fade_out = Arc::new(AtomicU32::new(0));
                let analysis = shared.as_ref().expect("a current generation has a stream").tempo.clone();
                player.append(Meter::new(Eq::new(Spatial::new(decoder, status.clone()), status.clone()), status.clone(), 0, fade_out.clone(), false, analysis));
                if !paused {
                    player.play();
                }
                offset = at;
            }
            Ok(AudioCmd::Pause) => {
                log::debug!("audio: pause at {:?}", offset + player.get_pos());
                player.pause();
                fading.iter().for_each(|f| f.player.pause());
                status.playing.store(false, Ordering::Relaxed);
                wake();
            }
            Ok(AudioCmd::Resume) => {
                log::debug!("audio: resume (generation {current})");
                if current != 0 {
                    if output.sink.is_none() || output.default_changed() {
                        output.open(tx.clone(), &status);
                    }
                    player.play();
                    fading.iter().for_each(|f| f.player.play());
                    status.playing.store(true, Ordering::Relaxed);
                    wake();
                }
            }
            Ok(AudioCmd::Handoff)
                if mix_ms(&status, length) > 0
                    && current != 0
                    && !player.is_paused()
                    && !player.empty()
                    && fading.iter().all(|f| f.player.empty()) =>
            {
                let left = length.map(|d| d.saturating_sub(offset + player.get_pos()));
                log::debug!("audio: handing off from generation {current}, {left:?} left");
                let old = std::mem::replace(&mut player, Player::connect_new(&output.mixer));
                fading.push(Fading { player: old, offset, shared: shared.take(), out: fade_out.clone(), waiting: Some((Instant::now(), left)), natural: ending_sent });
                current = 0;
                status.playing.store(false, Ordering::Relaxed);
                status.set_position(0);
            }
            Ok(cmd @ (AudioCmd::Stop | AudioCmd::Handoff)) => {
                if matches!(cmd, AudioCmd::Handoff) {
                    for f in fading.iter_mut() {
                        f.waiting = None;
                        f.out.store(QUICK_FADE_MS, Ordering::Relaxed);
                    }
                    log::debug!("audio: skipped mid-crossfade, cutting over");
                    if current != 0 && !player.is_paused() && !player.empty() {
                        fade_out.store(QUICK_FADE_MS, Ordering::Relaxed);
                        let old = std::mem::replace(&mut player, Player::connect_new(&output.mixer));
                        fading.push(Fading { player: old, offset, shared: shared.take(), out: fade_out.clone(), waiting: None, natural: false });
                    } else {
                        player.clear();
                    }
                } else {
                    player.clear();
                    fading.clear();
                }
                current = 0;
                status.mix_active.store(false, Ordering::Relaxed);
                status.mix_generation.store(0, Ordering::Release);
                if let Some(old) = shared.take() {
                    old.cancel();
                }
                status.playing.store(false, Ordering::Relaxed);
                status.set_position(0);
            }
            Ok(cmd @ (AudioCmd::Seek(_) | AudioCmd::SeekLive { .. })) => {
                let (pos, live) = match cmd {
                    AudioCmd::SeekLive { pos, at } => (pos + at.elapsed(), Some((pos, at))),
                    AudioCmd::Seek(pos) => (pos, None),
                    _ => unreachable!(),
                };
                seek_seq += 1;
                if current == 0 {
                    continue;
                }
                for f in &fading {
                    f.out.store(QUICK_FADE_MS, Ordering::Relaxed);
                }
                if seekable && player.try_seek(pos).is_ok() {
                    log::debug!("audio: seek to {pos:?} in place");
                    offset = Duration::ZERO;
                    status.set_position(pos.as_millis() as u64);
                    continue;
                }
                if let Some(s) = shared.clone() {
                    *seek_job.lock().unwrap() = Some(SeekJob { shared: s, pos, live, generation: current, seek: seek_seq });
                    if !seek_busy.swap(true, Ordering::AcqRel) {
                        let (job, busy, tx, events) = (seek_job.clone(), seek_busy.clone(), tx.clone(), events.clone());
                        std::thread::spawn(move || {
                            loop {
                                let next = job.lock().unwrap().take();
                                match next {
                                    Some(j) => build_seek(j, &tx, &events),
                                    None => {
                                        busy.store(false, Ordering::Release);
                                        if job.lock().unwrap().is_none() || busy.swap(true, Ordering::AcqRel) {
                                            break;
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
            }
            Ok(AudioCmd::DeviceLost) => {
                let was = output.device.clone();
                output.open(tx.clone(), &status);
                if output.device != was && !player.is_paused() && (current != 0 || !fading.is_empty()) {
                    log::info!("audio: output device gone, pausing");
                    player.pause();
                    fading.iter().for_each(|f| f.player.pause());
                    status.playing.store(false, Ordering::Relaxed);
                    wake();
                }
            }
            Ok(AudioCmd::SetDevice(id)) => {
                log::info!("audio: output device set to {id:?}");
                output.preferred = id;
                if output.default_changed() || output.sink.is_none() {
                    output.open(tx.clone(), &status);
                }
            }
            Ok(AudioCmd::Volume(v)) => {
                let v = slider_gain(&status, v);
                player.set_volume(v * norm_gain(&status, shared.as_deref()));
                for f in &fading {
                    f.player.set_volume(v * norm_gain(&status, f.shared.as_deref()));
                }
            }
            Err(crossbeam_channel::RecvTimeoutError::Timeout) => {}
            Err(crossbeam_channel::RecvTimeoutError::Disconnected) => return,
        }

        if current != 0 && dev_lost.is_some_and(|at| offset + player.get_pos() >= at) {
            dev_lost = None;
            log::info!("audio: dev hook: output device lost");
            output.sink = None;
            output.device = Some("dev-hook-gone".into());
            output.lost.store(true, Ordering::Relaxed);
            let _ = tx.send(AudioCmd::DeviceLost);
        }
        if (current != 0 || !fading.is_empty())
            && !player.is_paused()
            && output.checked.elapsed() >= DEVICE_CHECK
            && !output.lost.load(Ordering::Relaxed)
            && (output.sink.is_none() || output.default_changed())
        {
            output.open(tx.clone(), &status);
        }
        if !fading.is_empty() {
            if let Some(f) = fading.iter().find(|f| f.natural) {
                status.sample_outgoing(f.offset + f.player.get_pos(), !f.player.is_paused() && !f.player.empty());
            }
            for f in fading.iter_mut().filter(|f| !status.automix.load(Ordering::Relaxed) && f.waiting.is_some_and(|(at, _)| at.elapsed() > HANDOFF_WAIT)) {
                let len = if f.natural { mix_ms(&status, length) } else { status.crossfade_ms.load(Ordering::Relaxed) };
                log::debug!("audio: next song not ready, fading out over {} ms", f.start(len, status.automix.load(Ordering::Relaxed)));
            }
            fading.retain(|f| !f.player.empty());
            if fading.is_empty() {
                status.mix_active.store(false, Ordering::Relaxed);
                wake();
                log::debug!("audio: crossfade done");
            }
        }
        if current != 0 {
            let pos = offset + player.get_pos();
            status.set_position(pos.as_millis() as u64);
            let crossfade = Duration::from_millis(mix_ms(&status, length) as u64);
            if !ending_sent
                && !crossfade.is_zero()
                && let Some(d) = length
                && d > crossfade * 2
                && pos + crossfade >= d
                && shared.as_ref().is_some_and(|s| s.error().is_none())
            {
                ending_sent = true;
                status.sample_outgoing(pos, !player.is_paused());
                let _ = events.send(AudioEvent::Ending { generation: current });
                wake();
            }
            if player.empty() {
                let event = match shared.as_ref().and_then(|s| s.error()) {
                    Some(reason) => AudioEvent::Interrupted { generation: current, at: offset + player.get_pos(), reason },
                    None => AudioEvent::Finished { generation: current },
                };
                let _ = events.send(event);
                current = 0;
                shared = None;
                status.playing.store(false, Ordering::Relaxed);
                wake();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outgoing_clock_survives_incoming_position_reset() {
        let status = AudioStatus::default();
        status.sample_outgoing(Duration::from_secs(180), false);
        status.set_position(0);
        assert_eq!(status.outgoing_position(), Duration::from_secs(180));
        status.set_position(2500);
        assert_eq!(status.outgoing_position(), Duration::from_secs(180));
    }

    #[test]
    fn paused_outgoing_clock_does_not_extrapolate() {
        let status = AudioStatus::default();
        status.sample_outgoing(Duration::from_secs(42), false);
        status.outgoing_sampled_at.store(0, Ordering::Relaxed);
        assert!(!status.outgoing_playing());
        assert_eq!(status.outgoing_position(), Duration::from_secs(42));
    }

    #[test]
    fn crossfade_ramps_mirror_each_other_and_end_the_fading_song() {
        use std::num::NonZero;
        let ones = || rodio::buffer::SamplesBuffer::new(NonZero::new(1).unwrap(), NonZero::new(1000).unwrap(), vec![1.0; 1000]);
        let status = Arc::new(AudioStatus::default());
        let out = Arc::new(AtomicU32::new(0));
        let mut incoming = Meter::new(ones(), status.clone(), 100, Arc::new(AtomicU32::new(0)), false, Default::default());
        let mut outgoing = Meter::new(ones(), status, 0, out.clone(), false, Default::default());
        assert_eq!(outgoing.next(), Some(1.0));
        out.store(100, Ordering::Relaxed);
        let (a, b): (Vec<f32>, Vec<f32>) = (0..100).map(|_| (incoming.next().unwrap(), outgoing.next().unwrap())).unzip();
        assert_eq!(a[0], 0.0);
        assert_eq!(b[0], 1.0);
        for (x, y) in a.iter().zip(&b) {
            assert!((x + y - 1.0).abs() < 1e-4);
        }
        assert!((b[50] - 0.5).abs() < 1e-4);
        assert_eq!(incoming.next(), Some(1.0));
        assert_eq!(outgoing.next(), None);
    }

    #[test]
    fn automix_envelopes_hold_then_exchange_at_sixty_five_percent() {
        use std::num::NonZero;
        let ones = || rodio::buffer::SamplesBuffer::new(NonZero::new(1).unwrap(), NonZero::new(1000).unwrap(), vec![1.0; 1000]);
        let status = Arc::new(AudioStatus::default());
        let out = Arc::new(AtomicU32::new(0));
        let mut incoming = Meter::new(ones(), status.clone(), 100, Arc::new(AtomicU32::new(0)), true, Default::default());
        let mut outgoing = Meter::new(ones(), status, 0, out.clone(), false, Default::default());
        assert_eq!(outgoing.next(), Some(1.0));
        out.store(100 | AUTOMIX_FADE, Ordering::Relaxed);
        let (a, b): (Vec<f32>, Vec<f32>) = (0..100).map(|_| (incoming.next().unwrap(), outgoing.next().unwrap())).unzip();
        assert_eq!(a[25], 0.0);
        assert_eq!(b[25], 1.0);
        for (x, y) in a.iter().zip(&b) {
            assert!((x + y - 1.0).abs() < 1e-4);
        }
        assert!((a[65] - 0.5).abs() < 1e-4);
        assert_eq!(incoming.next(), Some(1.0));
        assert_eq!(outgoing.next(), None);
    }

    #[test]
    fn transition_fx_are_dry_outside_the_blend_and_keep_channels_separate() {
        let mut fx = MixFx::new(48_000, 2).unwrap();
        for i in 0..48_000 {
            let input = (i as f32 * 0.03).sin() * 0.5;
            assert_eq!(fx.process(input, None), input);
            assert_eq!(fx.process(0.0, None), 0.0);
        }
        fx.begin(120.0);
        let mut difference = 0.0;
        for frame in 0..48_000 {
            let input = (frame as f32 * 0.03).sin() * 0.5;
            let ramp = Some(Ramp::MixOut { from: 1.0, at: frame * 2, len: 96_000 });
            let left = fx.process(input, ramp);
            let right = fx.process(0.0, ramp);
            assert!(left.is_finite() && left.abs() < 1.0);
            assert_eq!(right, 0.0);
            difference += (left - input).abs();
        }
        assert!(difference > 10.0);
        assert_eq!(fx.process(0.25, None), 0.25);
        assert_eq!(fx.process(-0.25, None), -0.25);
    }

    #[test]
    fn automix_repeat_and_flanger_are_audible() {
        let mut fx = MixFx::new(48_000, 1).unwrap();
        fx.begin(120.0);
        let ramp = Some(Ramp::MixOut { from: 1.0, at: 60_000, len: 100_000 });
        let out: Vec<f32> = (0..30_000).map(|i| fx.process(if i == 0 { 1.0 } else { 0.0 }, ramp)).collect();
        let comb: f32 = out[20..200].iter().map(|x| x.abs()).sum();
        assert!(comb > 0.01, "no flanged comb after the impulse: {comb}");
        let echo: f32 = out[11_950..12_060].iter().map(|x| x.abs()).sum();
        assert!(echo > 0.01, "no half-beat repeat: {echo}");
    }

    #[test]
    fn enabled_effects_finish_without_changing_the_incoming_position() {
        use std::num::NonZero;
        let rate = 48_000;
        let source = |step: f32| rodio::buffer::SamplesBuffer::new(
            NonZero::new(1).unwrap(),
            NonZero::new(rate).unwrap(),
            (0..rate * 2).map(|i| (i as f32 * step).sin() * 0.3).collect::<Vec<_>>(),
        );
        let status = Arc::new(AudioStatus::default());
        status.automix.store(true, Ordering::Relaxed);
        let out = Arc::new(AtomicU32::new(0));
        let mut incoming = Meter::new(source(0.04), status.clone(), 1000, Default::default(), true, Default::default());
        let mut outgoing = Meter::new(source(0.03), status, 0, out.clone(), false, Arc::new(OnceLock::from(Some(120.0))));
        assert!(incoming.fx.is_some() && outgoing.fx.is_some());
        assert_eq!(outgoing.by_ref().take(12_000).count(), 12_000);
        out.store(1000 | AUTOMIX_FADE, Ordering::Relaxed);
        for _ in 0..rate {
            let mixed = incoming.next().unwrap() + outgoing.next().unwrap();
            assert!(mixed.is_finite() && mixed.abs() < 1.0);
        }
        assert_eq!(outgoing.next(), None);
        assert_eq!(incoming.next(), Some((rate as f32 * 0.04).sin() * 0.3));
        assert!(incoming.ramp.is_none());
    }

    #[test]
    fn a_quick_fade_retains_the_effect_instead_of_jumping_to_dry() {
        let mut fx = MixFx::new(48_000, 1).unwrap();
        fx.begin(120.0);
        let mut before = 0.0;
        for at in 0..36_000 {
            before = fx.process(0.4, Some(Ramp::MixOut { from: 1.0, at, len: 48_000 }));
        }
        let after = fx.process(0.4, Some(Ramp::Out { from: 0.3, at: 0, len: 1920 }));
        assert!((after - before).abs() < 0.01);
        assert!((after - 0.4).abs() > 0.05);
    }

    #[test]
    fn transition_buffers_are_bounded_and_do_not_grow() {
        for rate in [8_000, 44_100, 48_000, 96_000, 192_000] {
            let mut fx = MixFx::new(rate, 2).unwrap();
            let bytes = fx.ring.capacity() * std::mem::size_of::<f32>();
            assert!(bytes <= FX_MAX_FRAMES * 2 * 4);
            let address = fx.ring.as_ptr();
            fx.begin(70.0);
            assert!(fx.beat <= (fx.ring.len() / fx.channels - 1) as f32);
            for at in 0..rate * 2 {
                fx.process(0.1, Some(Ramp::MixOut { from: 1.0, at, len: rate * 2 }));
            }
            assert_eq!(fx.ring.as_ptr(), address);
            assert_eq!(fx.ring.capacity() * 4, bytes);
        }
        assert!(MixFx::new(48_000, 9).is_none());
    }

    #[test]
    fn unknown_tempo_uses_a_finite_tail_and_seek_clears_history() {
        let mut fx = MixFx::new(48_000, 1).unwrap();
        fx.begin(0.0);
        assert!(!fx.rhythmic);
        let mut tail = 0.0;
        for at in 0..48_000 {
            let input = if at == 24_000 { 0.5 } else { 0.0 };
            let sample = fx.process(input, Some(Ramp::MixOut { from: 1.0, at, len: 48_000 }));
            assert!(sample.is_finite() && sample.abs() <= 0.5);
            if at > 24_001 {
                tail += sample.abs();
            }
        }
        assert!(tail > 0.001);
        fx.reset();
        assert!(fx.ring.iter().all(|v| *v == 0.0));
        assert_eq!(fx.process(0.0, None), 0.0);
    }

    #[test]
    fn tempo_analysis_finishes_once_even_for_silence() {
        for silence in [false, true] {
            let mut tempo = Tempo::new(20.0);
            let mut result = None;
            for i in 0..300 {
                result = tempo.push(if !silence && i % 25 == 0 { 1.0 } else { 0.0 });
            }
            assert!(tempo.done);
            if silence {
                assert!(result.is_none());
            } else {
                assert!((result.unwrap() - 120.0).abs() < 1.0);
            }
            let history = tempo.hist;
            for _ in 0..1000 {
                assert!(tempo.push(0.8).is_none());
            }
            assert_eq!(tempo.hist, history);
            assert_eq!(tempo.count, 300);
        }
    }

    #[test]
    fn replacing_a_decoder_reuses_its_completed_analysis() {
        use std::num::NonZero;
        for result in [Some(120.0), None] {
            let analysis = Arc::new(OnceLock::from(result));
            let source = rodio::buffer::SamplesBuffer::new(NonZero::new(2).unwrap(), NonZero::new(48_000).unwrap(), vec![0.0; 4096]);
            let status = Arc::new(AudioStatus::default());
            status.automix.store(true, Ordering::Relaxed);
            let mut meter = Meter::new(source, status, 0, Default::default(), false, analysis.clone());
            assert!(meter.tempo.done);
            assert_eq!(meter.by_ref().count(), 4096);
            assert_eq!(meter.tempo.count, 0);
            assert_eq!(analysis.get(), Some(&result));
        }
    }

    #[test]
    fn mix_status_is_bound_to_the_started_generation() {
        let status = AudioStatus::default();
        status.mix_duration_ms.store(12_345, Ordering::Relaxed);
        status.mix_active.store(true, Ordering::Relaxed);
        status.mix_generation.store(7, Ordering::Release);
        assert_eq!(status.mix_duration(6), None);
        assert_eq!(status.mix_duration(7), Some(12.345));
        assert!(status.mixing());
        status.mix_active.store(false, Ordering::Relaxed);
        assert!(!status.mixing());
    }

    #[test]
    fn normalization_only_turns_loud_tracks_down() {
        let status = AudioStatus::default();
        let loud = Shared::new(1, 0, Some(7.29), crate::stream::Format::Aac, None, false);
        let quiet = Shared::new(1, 0, Some(-4.0), crate::stream::Format::Aac, None, false);
        let unknown = Shared::new(1, 0, None, crate::stream::Format::Aac, None, false);
        status.normalize.store(true, Ordering::Relaxed);
        assert!((norm_gain(&status, Some(&loud)) - 0.432).abs() < 0.001);
        assert_eq!(norm_gain(&status, Some(&quiet)), 1.0);
        assert_eq!(norm_gain(&status, Some(&unknown)), 1.0);
        assert_eq!(norm_gain(&status, None), 1.0);
        status.normalize.store(false, Ordering::Relaxed);
        assert_eq!(norm_gain(&status, Some(&loud)), 1.0);
    }

    #[test]
    fn spatial_widens_stereo_and_leaves_it_alone_when_off() {
        let status = Arc::new(AudioStatus::default());
        let frames = [0.5f32, -0.5, 0.25, 0.25].repeat(2000);
        let src = || rodio::buffer::SamplesBuffer::new(rodio::ChannelCount::new(2).unwrap(), rodio::SampleRate::new(44100).unwrap(), frames.clone());
        let off: Vec<f32> = Spatial::new(src(), status.clone()).collect();
        assert_eq!(off, frames);
        status.spatial.store(true, Ordering::Relaxed);
        let on: Vec<f32> = Spatial::new(src(), status).collect();
        assert_eq!(on.len(), frames.len());
        assert!((on[0] - 0.5 * 2.5 * 0.82).abs() < 1e-6 && (on[1] + 0.5 * 2.5 * 0.82).abs() < 1e-6);
        assert!((on[2] - on[3]).abs() < 1e-6);
    }

    #[test]
    fn log_volume_keeps_loudness_when_switched() {
        let (tx, _rx) = crossbeam_channel::unbounded();
        let audio = AudioHandle { tx, status: Arc::new(AudioStatus::default()) };
        audio.set_volume(0.125);
        audio.set_log_volume(true);
        assert!((audio.volume() - 0.5).abs() < 1e-6);
        assert!((slider_gain(&audio.status, audio.volume()) - 0.125).abs() < 1e-6);
        audio.set_log_volume(true);
        assert!((audio.volume() - 0.5).abs() < 1e-6);
        audio.set_log_volume(false);
        assert!((audio.volume() - 0.125).abs() < 1e-6);
    }
}
