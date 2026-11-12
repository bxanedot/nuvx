use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use crossbeam_channel::{Receiver, Sender, unbounded};
use rodio::Player;

mod control;
mod effects;
mod engine;
mod fading;
mod mix;
mod output;
mod seek;
mod status;
pub use control::{AudioCmd, AudioEvent, AudioHandle};
use effects::{Eq, Meter, Spatial};
use engine::run;
use fading::{Fading, HANDOFF_WAIT, QUICK_FADE_MS};
use mix::{mix_ms, norm_gain, slider_gain};
use output::{Output, DEVICE_CHECK};
use seek::{SeekJob, build_seek};
pub use output::{output_devices, OutputDevice};
pub use status::AudioStatus;
#[cfg(test)]
use effects::{AUTOMIX_FADE, FX_MAX_FRAMES, MixFx, Ramp, Tempo};
use crate::stream::Shared;

pub use crate::stream::TrackDecoder;

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

#[cfg(test)]
mod tests;
