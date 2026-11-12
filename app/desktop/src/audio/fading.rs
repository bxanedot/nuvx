use super::effects::AUTOMIX_FADE;
use crate::stream::Shared;
use rodio::Player;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
pub(super) struct Fading {
    pub(super) player: Player,
    pub(super) offset: Duration,
    pub(super) shared: Option<Arc<Shared>>,
    pub(super) out: Arc<AtomicU32>,
    pub(super) waiting: Option<(Instant, Option<Duration>)>,
    pub(super) natural: bool,
}

impl Fading {
    pub(super) fn start(&mut self, crossfade: u32, automix: bool) -> u32 {
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

pub(super) const QUICK_FADE_MS: u32 = 40;

pub(super) const HANDOFF_WAIT: Duration = Duration::from_secs(3);
