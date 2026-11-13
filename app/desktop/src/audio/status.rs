use super::mix_ms;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::LazyLock;
use std::time::{Duration, Instant};
static EPOCH: LazyLock<Instant> = LazyLock::new(Instant::now);

fn now_ms() -> u64 {
    EPOCH.elapsed().as_millis() as u64
}

#[derive(Default)]
pub struct AudioStatus {
    pub position_ms: AtomicU64,
    held_position_ms: AtomicU64,
    position_held: AtomicBool,
    pub(super) sampled_at: AtomicU64,
    pub duration_ms: AtomicU64,
    pub playing: AtomicBool,
    pub wanted_gen: AtomicU64,
    pub(super) volume: AtomicU32,
    pub normalize: AtomicBool,
    pub(super) spatial: AtomicBool,
    pub(super) eq: std::sync::Mutex<crate::eq::Tuning>,
    pub(super) log_volume: AtomicBool,
    pub(super) level: AtomicU32,
    pub(super) latency_ms: AtomicU32,
    pub(super) crossfade_ms: AtomicU32,
    pub(super) automix: AtomicBool,
    pub(super) bpm: AtomicU32,
    pub(super) mix_generation: AtomicU64,
    pub(super) mix_duration_ms: AtomicU32,
    pub(super) mix_active: AtomicBool,
    pub(super) outgoing_position_ms: AtomicU64,
    pub(super) outgoing_sampled_at: AtomicU64,
    pub(super) outgoing_playing: AtomicBool,
}

impl AudioStatus {
    pub fn position(&self) -> Duration {
        let pos = if self.position_held.load(Ordering::Acquire) {
            self.held_position_ms.load(Ordering::Relaxed)
        } else {
            self.position_ms.load(Ordering::Relaxed)
        };
        Duration::from_millis(pos)
    }

    pub fn position_smooth(&self) -> Duration {
        if self.position_held.load(Ordering::Acquire) {
            return Duration::from_millis(self.held_position_ms.load(Ordering::Relaxed));
        }
        let pos = self.position_ms.load(Ordering::Relaxed);
        if !self.playing.load(Ordering::Relaxed) {
            return Duration::from_millis(pos);
        }
        let age = now_ms().saturating_sub(self.sampled_at.load(Ordering::Relaxed)).min(250);
        Duration::from_millis(pos + age)
    }

    pub(crate) fn hold_position(&self, at: Duration) {
        self.held_position_ms.store(at.as_millis() as u64, Ordering::Relaxed);
        self.position_held.store(true, Ordering::Release);
    }

    pub(super) fn release_position(&self) {
        self.position_held.store(false, Ordering::Release);
    }

    pub fn outgoing_position(&self) -> Duration {
        let pos = self.outgoing_position_ms.load(Ordering::Relaxed);
        let age = if self.outgoing_playing() { now_ms().saturating_sub(self.outgoing_sampled_at.load(Ordering::Relaxed)).min(250) } else { 0 };
        Duration::from_millis(pos + age)
    }

    pub fn outgoing_playing(&self) -> bool {
        self.outgoing_playing.load(Ordering::Relaxed)
    }

    pub(super) fn sample_outgoing(&self, pos: Duration, playing: bool) {
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

    pub(super) fn set_position(&self, ms: u64) {
        self.position_ms.store(ms, Ordering::Relaxed);
        self.sampled_at.store(now_ms(), Ordering::Relaxed);
    }
}
