














mod bridge;
mod commands;
mod curves;
mod deck;
mod fx;
mod render;
mod state;
mod swap_alignment;
mod transition;
mod types;
mod voice;

#[cfg(test)]
mod tests;

pub(crate) use curves::MAX_DUCK_DEPTH;
pub use deck::{
    loudness_gain, DeckLoudness, MAX_LOUDNESS_GAIN_DB, MAX_TRANSITION_BOOST_DB, MAX_TRANSITION_MATCH_DB,
    MIN_LOUDNESS_GAIN_DB,
};
pub use state::NerdSnapshot;
pub use types::{
    Command, EngineEvents, QueuedTrack, TrackInfo, TrackSource, TransitionPlan, TransitionStyle,
};

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crossbeam_channel::Receiver;
use rtrb::Producer;

use crate::eq::EqualizerProcessor;
use crate::sound::{Clarity, ClarityTuning, LoudnessMode, Protector, SoundMode};

use commands::handle_command;
use render::render_available;
use state::{MixerState, Phase, SwapProbe};
use voice::Voice;

const AUTOMIX_SAFETY_PREFIX: &str = "safety:";

fn automix_plan_winner(plan: &TransitionPlan) -> Option<&'static str> {
    if plan.reason.starts_with("skip-mix:") {
        None
    } else if plan.reason.starts_with(AUTOMIX_SAFETY_PREFIX) {
        Some("safety")
    } else if plan.fade_seconds > 0.0 || !plan.reason.is_empty() {
        Some("analyzed")
    } else {
        None
    }
}

pub(crate) struct AutomixWinnerRecord<'a> {
    pub(crate) winner: &'static str,
    pub(crate) style: &'static str,
    pub(crate) reason: &'a str,
    pub(crate) snap_error_seconds: Option<f64>,
}

pub(crate) fn automix_winner_record(
    plan: &TransitionPlan,
    snap_error_seconds: Option<f64>,
) -> Option<AutomixWinnerRecord<'_>> {
    Some(AutomixWinnerRecord {
        winner: automix_plan_winner(plan)?,
        style: plan.style.telemetry_name(),
        reason: plan
            .reason
            .strip_prefix(AUTOMIX_SAFETY_PREFIX)
            .unwrap_or(&plan.reason)
            .trim(),
        snap_error_seconds,
    })
}

fn accepts_analyzed_plan(phase: Phase, swap: bool) -> bool {
    phase == Phase::Arming && !swap
}


pub fn technique_window_seconds(style: TransitionStyle, beat_seconds: f64) -> Option<f64> {
    fx::window_seconds(style, beat_seconds)
}


pub fn technique_window_seconds_for_phrase(
    style: TransitionStyle,
    beat_seconds: f64,
    phrase_bars: u32,
) -> Option<f64> {
    fx::window_seconds_for_phrase(style, beat_seconds, phrase_bars)
}

const BAIL_MS: u64 = 120;
const ARM_LEAD_MS: u64 = 4_000;
const ARM_TIMEOUT_MS: u64 = 12_000;


const MIN_INCOMING_CLEARANCE_S: f64 = 5.0;








const MAX_CUE_SECONDS: f64 = 8.0;
const MAX_CUE_FRACTION: f64 = 0.06;




const SWAP_CROSSFADE_MS: f64 = 550.0;




const NORMAL_PREROLL_SECONDS: f64 = 0.5;


fn prime_voice(voice: &mut Voice, device_rate: u32) {
    let frames = (NORMAL_PREROLL_SECONDS * device_rate as f64)
        .round()
        .max(1.0) as usize;
    voice.prime_for_fade(frames, device_rate);
}


#[allow(clippy::too_many_arguments)]
pub fn run_mixer(
    commands: Receiver<Command>,
    mut ring: Producer<f32>,
    buffered_frames: Arc<AtomicU64>,
    position_ms: Arc<AtomicU64>,
    duration_ms: Arc<AtomicU64>,
    device_rate: u32,
    events: Arc<dyn EngineEvents>,
    shutdown: Arc<AtomicBool>,
    flush_ring: Arc<AtomicBool>,
    bail_flush: Arc<AtomicBool>,
    nerd: Arc<Mutex<NerdSnapshot>>,
) {
    let mut state = MixerState {
        current: None,
        incoming: None,
        pending_next: None,
        warned_no_duration: false,
        transition: None,
        retiring: Vec::new(),
        render_scratch: Vec::new(),
        voice_output_scratch: Vec::new(),
        effect_dry_scratch: Vec::new(),
        retiring_scratch: Vec::new(),
        playing: false,
        bit_perfect: false,
        volume: 1.0,
        sleep_gain: 1.0,
        hold_end: false,
        crossfade_window_s: 0.0,
        spatial_enabled: false,
        head_yaw: 0.0,
        device_rate,
        buffered_frames,
        position_ms,
        duration_ms,
        events,
        state: crate::PlaybackState::Stopped,
        flush_ring,
        bail_flush,
        playback_speed: 1.0,
        skip_silence: false,


        eq: EqualizerProcessor::new(device_rate, 2),
        sound_mode: SoundMode::Transparent,
        clarity_tuning: ClarityTuning::default(),
        clarity: Clarity::new(device_rate),
        protector: Protector::new(device_rate),
        pipeline_tail_frames: 0,
        output_generation: 0,
        queue_target_ms: 250,
        underrun_counter: None,
        observed_underruns: 0,
        nerd,
        last_diagnostic_signature: None,
        last_diagnostic_publish: None,

        loudness_enabled: false,
        loudness_mode: LoudnessMode::Off,
        swap_probe: SwapProbe::default(),
    };

    loop {
        if shutdown.load(Ordering::Relaxed) {
            return;
        }


        while let Ok(cmd) = commands.try_recv() {
            if shutdown.load(Ordering::Acquire) {
                return;
            }
            handle_command(&mut state, cmd, &mut ring);
        }

        if let Some(counter) = &state.underrun_counter {
            let count = counter.load(Ordering::Relaxed);
            if count > state.observed_underruns {
                state.queue_target_ms = (state.queue_target_ms + 60).min(1000);
                crate::diagnostics::technical_event(format!(
                    "output underruns={} queue_target_ms={}",
                    count, state.queue_target_ms
                ));
                state.observed_underruns = count;
            }
        }
        if state.playing {
            render_available(&mut state, &mut ring);
            if state.flush_ring.load(Ordering::Acquire)
                || state.bail_flush.load(Ordering::Acquire)
                || state.buffered_frames.load(Ordering::Relaxed)
                    >= state.device_rate as u64 * state.queue_target_ms / 1000
            {
                std::thread::sleep(Duration::from_millis(4));
            }
        } else {

            std::thread::sleep(Duration::from_millis(20));
        }
    }
}
