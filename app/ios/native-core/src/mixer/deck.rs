


use super::types::TransitionPlan;
use super::voice::Voice;
use super::{MAX_CUE_FRACTION, MAX_CUE_SECONDS, MIN_INCOMING_CLEARANCE_S};
use crate::time_stretch::TimeStretch;


pub(super) const SILENCE_THRESHOLD: f32 = 1024.0 / 32768.0;

pub(super) const MIN_SILENCE_SECS: f64 = 1.0;


pub(super) const MAX_SILENCE_KEEP_SECS: f64 = 2.0;


pub(super) const MIN_VOLUME_TO_KEEP: f32 = 0.1;




pub const MIN_LOUDNESS_GAIN_DB: f64 = -15.0;
pub const MAX_LOUDNESS_GAIN_DB: f64 = 0.0;












#[derive(Clone, Copy, Debug)]
pub struct DeckLoudness {

    pub lufs: Option<f64>,



    pub relative_db: Option<f64>,



    pub applied_normalization_db: Option<f64>,

    pub true_peak_dbtp: Option<f64>,
}

impl DeckLoudness {
    pub(super) fn applied(self) -> f64 {
        self.applied_normalization_db
            .filter(|v| v.is_finite())
            .unwrap_or(0.0)
    }










    pub(super) fn shared_level(incoming: Self, outgoing: Self) -> Option<(f64, f64)> {
        let (ai, ao) = (incoming.applied(), outgoing.applied());
        if let (Some(li), Some(lo)) = (
            incoming.lufs.filter(|v| v.is_finite()),
            outgoing.lufs.filter(|v| v.is_finite()),
        ) {
            return Some((li + ai, lo + ao));
        }
        if let (Some(ri), Some(ro)) = (
            incoming.relative_db.filter(|v| v.is_finite()),
            outgoing.relative_db.filter(|v| v.is_finite()),
        ) {
            return Some((ri + ai, ro + ao));
        }
        None
    }














    pub fn match_gain_db(incoming: DeckLoudness, outgoing: DeckLoudness) -> Option<f64> {
        let (a, b) = DeckLoudness::shared_level(incoming, outgoing)?;
        let delta = b - a;
        if !delta.is_finite() {
            return None;
        }
        let headroom = incoming
            .true_peak_dbtp
            .filter(|p| p.is_finite())
            .map(|peak| (TRANSITION_PEAK_CEILING_DBTP - peak - incoming.applied()).max(0.0))
            .unwrap_or(MAX_TRANSITION_BOOST_DB);
        Some(delta.clamp(
            -MAX_TRANSITION_MATCH_DB,
            MAX_TRANSITION_BOOST_DB.min(headroom),
        ))
    }
}


pub const MAX_TRANSITION_MATCH_DB: f64 = 6.0;

pub const MAX_TRANSITION_BOOST_DB: f64 = 2.0;

const TRANSITION_PEAK_CEILING_DBTP: f64 = -1.0;






pub fn loudness_gain(loudness_db: Option<f64>, enabled: bool) -> (f32, Option<f32>) {
    match (enabled, loudness_db) {
        (true, Some(db)) if db.is_finite() => {
            let gain_db = (-db).clamp(MIN_LOUDNESS_GAIN_DB, MAX_LOUDNESS_GAIN_DB);
            (10f64.powf(gain_db / 20.0) as f32, Some(gain_db as f32))
        }
        _ => (1.0, None),
    }
}













pub(super) fn known_duration(container: Option<f64>, declared: f64) -> f64 {
    container
        .filter(|d| d.is_finite() && *d > 0.0)
        .or_else(|| Some(declared).filter(|d| d.is_finite() && *d > 0.0))
        .unwrap_or(0.0)
}


pub(super) fn source_still_growing(path: &str) -> bool {
    if path.is_empty() || path.contains("://") {
        return false;
    }
    let grow = format!("{path}.grow");
    let complete = format!("{path}.complete");
    std::path::Path::new(&grow).is_file() && !std::path::Path::new(&complete).exists()
}

pub(super) fn user_stretcher(device_rate: u32, speed: f32) -> Option<TimeStretch> {
    if (speed - 1.0).abs() < 1e-6 {
        None
    } else {
        let mut stretch = TimeStretch::with_sample_rate(speed as f64, device_rate);
        stretch.set_rate(speed as f64);
        Some(stretch)
    }
}

















pub(super) fn glided_plan_rate(base: f64, p: f64) -> f64 {
    if !base.is_finite() || base <= 0.0 || (base - 1.0).abs() < 1e-6 {
        return 1.0;
    }
    let p = p.clamp(0.0, 1.0);
    if p >= 1.0 {
        1.0
    } else {
        base.powf(1.0 - p)
    }
}

pub(super) fn is_silent(interleaved: &[f32]) -> bool {

    if interleaved.is_empty() {
        return false;
    }
    interleaved.iter().all(|s| s.abs() < SILENCE_THRESHOLD)
}

pub(super) fn silence_exceeds_floor(silent_dev_frames: u64, device_rate: u32) -> bool {
    silent_dev_frames as f64 / device_rate.max(1) as f64 > MIN_SILENCE_SECS
}







pub(super) fn normalization_gain(voice: &Voice, enabled: bool) -> (f32, Option<f32>) {
    if !enabled {
        return (1.0, None);
    }
    if let Some(db) = voice.replay_gain.gain_db(voice.normalization_mode) {
        return (10f32.powf(db as f32 / 20.0), Some(db as f32));
    }
    if let Some(db) = voice
        .measured_loudness
        .as_ref()
        .and_then(|m| m.gain_db(voice.normalization_mode))
    {
        return (10f32.powf(db as f32 / 20.0), Some(db as f32));
    }

    loudness_gain(voice.loudness_db, true)
}



pub(super) fn clamp_start_seconds(requested: f64, duration: f64) -> f64 {
    let requested = requested.max(0.0);
    if requested <= 0.0 {
        return 0.0;
    }
    if duration <= 0.0 {
        return requested;
    }
    if requested >= (duration - MIN_INCOMING_CLEARANCE_S).max(0.0) {
        log::warn!(
            "cue {requested:.1}s is within {MIN_INCOMING_CLEARANCE_S}s of duration {duration:.1}s; starting at 0"
        );
        return 0.0;
    }
    requested
}














pub(super) fn bound_mix_in_depth(requested: f64, plan: &TransitionPlan, duration: f64) -> f64 {
    if requested <= 0.0 || duration <= 0.0 || plan.cue_seconds <= 0.0 {
        return requested;
    }
    let ceiling = MAX_CUE_SECONDS.min(duration * MAX_CUE_FRACTION);
    if requested > ceiling {
        log::warn!(
            "planned mix-in at {requested:.1}s exceeds the {MAX_CUE_SECONDS}s / \
             {MAX_CUE_FRACTION} bound for a {duration:.1}s track; starting at 0"
        );
        return 0.0;
    }
    requested
}
