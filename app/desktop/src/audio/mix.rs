use super::AudioStatus;
use super::fading::QUICK_FADE_MS;
use crate::stream::Shared;
use std::sync::atomic::Ordering;
use std::time::Duration;
const AUTOMIX_BARS: f32 = 8.0;
const AUTOMIX_BARS_LONG: f32 = 16.0;
const AUTOMIX_MAX_BARS: f32 = 32.0;
const AUTOMIX_MAX_MS: u32 = 45_000;
const AUTOMIX_MIN_MS: u32 = 4_000;

pub(super) fn mix_ms(status: &AudioStatus, length: Option<Duration>) -> u32 {
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

pub(super) fn slider_gain(status: &AudioStatus, v: f32) -> f32 {
    if status.log_volume.load(Ordering::Relaxed) { v.powi(3) } else { v }
}

pub const NORM_DEFAULT_LUFS: i32 = -14;

/// Gain in dB for a track measured `loudness_db` above YouTube's reference (-14 LUFS), same formula as Android.
pub fn norm_gain_db(target_lufs: i32, loudness_db: f32) -> f32 {
    ((target_lufs - NORM_DEFAULT_LUFS) as f32 - loudness_db).clamp(-15.0, 3.0)
}

pub(super) fn norm_gain(status: &AudioStatus, shared: Option<&Shared>) -> f32 {
    match shared.and_then(|s| s.loudness_db) {
        Some(db) if status.normalize.load(Ordering::Relaxed) => {
            10f32.powf(norm_gain_db(status.norm_target.load(Ordering::Relaxed), db) / 20.0)
        }
        _ => 1.0,
    }
}
