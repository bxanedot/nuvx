


use super::types::{EngineEvents, TrackInfo, TrackSource, TransitionPlan};
use super::voice::Voice;
use super::{fx, loudness_gain, ARM_LEAD_MS, SWAP_CROSSFADE_MS};
use crate::eq::EqualizerProcessor;
use crate::sound::{Clarity, ClarityTuning, LoudnessMode, Protector, SoundMode};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

#[derive(PartialEq, Clone, Copy)]
#[allow(dead_code)]
pub(super) enum Phase {
    Idle,
    Arming,
    Fading,
}

pub(super) struct TransitionState {
    pub(super) phase: Phase,
    pub(super) fade_frames: u64,
    pub(super) handed_off: bool,
    pub(super) plan: TransitionPlan,
    pub(super) arm_deadline: Instant,




    pub(super) swap: bool,


    pub(super) fx: Option<fx::DeckFx>,



    pub(super) effect_start_frame: u64,




    pub(super) effect_input_gain: Option<f32>,
}

pub(super) struct RetiringVoice {
    pub(super) voice: Voice,
    pub(super) from_gain: f32,
    pub(super) started_at: Instant,
}

pub(super) struct MixerState {
    pub(super) current: Option<Voice>,
    pub(super) incoming: Option<Voice>,
    pub(super) pending_next: Option<TrackSource>,


    pub(super) warned_no_duration: bool,
    pub(super) transition: Option<TransitionState>,
    pub(super) retiring: Vec<RetiringVoice>,

    pub(super) render_scratch: Vec<f32>,
    pub(super) voice_output_scratch: Vec<f32>,
    pub(super) effect_dry_scratch: Vec<f32>,

    pub(super) retiring_scratch: Vec<RetiringVoice>,
    pub(super) playing: bool,
    pub(super) bit_perfect: bool,
    pub(super) volume: f32,
    pub(super) sleep_gain: f32,
    pub(super) hold_end: bool,
    pub(super) crossfade_window_s: f64,
    pub(super) spatial_enabled: bool,
    pub(super) head_yaw: f32,
    pub(super) device_rate: u32,



    pub(super) buffered_frames: Arc<AtomicU64>,
    pub(super) position_ms: Arc<AtomicU64>,
    pub(super) duration_ms: Arc<AtomicU64>,
    pub(super) events: Arc<dyn EngineEvents>,
    pub(super) state: crate::PlaybackState,


    pub(super) flush_ring: Arc<AtomicBool>,


    pub(super) bail_flush: Arc<AtomicBool>,
    pub(super) playback_speed: f32,
    pub(super) skip_silence: bool,
    pub(super) eq: EqualizerProcessor,
    pub(super) sound_mode: SoundMode,
    pub(super) clarity_tuning: ClarityTuning,
    pub(super) clarity: Clarity,
    pub(super) protector: Protector,
    pub(super) pipeline_tail_frames: usize,
    pub(super) output_generation: u64,
    pub(super) queue_target_ms: u64,
    pub(super) underrun_counter: Option<Arc<AtomicU64>>,
    pub(super) observed_underruns: u64,
    pub(super) nerd: Arc<Mutex<NerdSnapshot>>,
    pub(super) last_diagnostic_signature: Option<u32>,
    pub(super) last_diagnostic_publish: Option<Instant>,



    pub(super) loudness_enabled: bool,
    pub(super) loudness_mode: LoudnessMode,

    pub(super) swap_probe: SwapProbe,
}















#[derive(Default)]
pub(super) struct SwapProbe {

    pub(super) current: Vec<f32>,

    pub(super) len: usize,
    pub(super) sum_aa: f64,
    pub(super) sum_ab: f64,
    pub(super) sum_bb: f64,
}

impl SwapProbe {
    pub(super) fn reset(&mut self) {
        self.len = 0;
        self.sum_aa = 0.0;
        self.sum_ab = 0.0;
        self.sum_bb = 0.0;
    }


    pub(super) fn observe_current(&mut self, frames: &[f32]) {
        if self.current.len() < frames.len() {
            self.current.resize(frames.len(), 0.0);
        }
        self.len = frames.len();
        self.current[..self.len].copy_from_slice(frames);
        for sample in &self.current[..self.len] {
            self.sum_aa += (*sample as f64) * (*sample as f64);
        }
    }




    pub(super) fn observe_incoming(&mut self, frames: &[f32]) {
        let n = frames.len().min(self.len);
        for index in 0..n {
            let outgoing = self.current[index] as f64;
            let incoming = frames[index] as f64;
            self.sum_ab += outgoing * incoming;
            self.sum_bb += incoming * incoming;
        }
    }



    pub(super) fn correlation(&self) -> Option<f64> {
        if self.sum_aa <= 0.0 || self.sum_bb <= 0.0 {
            return None;
        }
        Some(self.sum_ab / (self.sum_aa.sqrt() * self.sum_bb.sqrt()))
    }
}

#[derive(Debug, Clone, Default)]
pub struct NerdSnapshot {
    pub codec: String,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub channels: u32,
    pub kbps: u32,



    pub loudness_gain_db: Option<f32>,




    pub transition_match_db: Option<f32>,





    pub swap_correlation: Option<f64>,
    pub swap_target: String,
    pub swap_accepted: Option<bool>,
    pub active_stages: Vec<String>,
    pub converter_delay_frames: f64,
    pub protection_reduction_db: f32,
    pub protection_interventions: u64,
    pub build_revision: String,
    pub decoder_implementation: String,
    pub decoded_layout: String,
    pub queued_target_ms: u64,
    pub loudness_origin: String,
}

impl MixerState {
    pub(super) fn diagnostic_settings(&self) -> serde_json::Value {
        serde_json::json!({"sound_mode":format!("{:?}",self.sound_mode),"clarity_preset":format!("{:?}",self.clarity_tuning.preset),"clarity_wet":self.clarity_tuning.wet,"clarity_trims_db":self.clarity_tuning.trims_db,"manual_eq":self.eq.diagnostic_settings(),"loudness_mode":format!("{:?}",self.loudness_mode),"playback_speed":self.playback_speed,"application_volume":self.volume,"bit_perfect":self.bit_perfect,"spatial_enabled":self.spatial_enabled,"head_yaw":self.head_yaw,"skip_silence":self.skip_silence,"crossfade_seconds":self.crossfade_window_s,"underruns":self.underrun_counter.as_ref().map(|c|c.load(Ordering::Relaxed))})
    }
    pub(super) fn set_state(&mut self, state: crate::PlaybackState) {
        if self.state != state {
            self.state = state;
            self.events.state_changed(state);
        }
    }

    pub(super) fn publish_nerd(&self, info: &TrackInfo, loudness_db: Option<f64>) {
        let (_, gain_db) = loudness_gain(loudness_db, self.loudness_enabled);
        if let Ok(mut nerd) = self.nerd.lock() {


            let correlation = nerd.swap_correlation;
            let transition_match = nerd.transition_match_db;
            let swap_target = nerd.swap_target.clone();
            let swap_accepted = nerd.swap_accepted;
            *nerd = NerdSnapshot {
                codec: info.codec.clone(),
                sample_rate: info.sample_rate,
                bit_depth: info.bit_depth,
                channels: info.channels,
                kbps: info.kbps,
                loudness_gain_db: gain_db,
                swap_correlation: correlation,
                transition_match_db: transition_match,
                swap_target,
                swap_accepted,
                ..NerdSnapshot::default()
            };
        }
    }








    pub(super) fn hard_cut(&mut self) {
        self.reject_pending_swap();
        self.eq.flush();
        self.clarity.reset();
        self.protector.reset();
        self.pipeline_tail_frames = 0;
        self.transition = None;
        self.incoming = None;
        self.pending_next = None;
        self.retiring.clear();
        self.current = None;
        if self.bit_perfect {
            self.flush_ring.store(true, Ordering::Release);
        } else {
            self.bail_flush.store(true, Ordering::Release);
        }
    }


    pub(super) fn promote_pending(&mut self) {
        if self.hold_end {
            return;
        }
        let Some(request) = self.pending_next.take() else {
            return;
        };
        match Voice::open(
            &request,
            self.spatial_enabled && self.sound_mode == SoundMode::Enhanced,
            self.head_yaw,
            self.device_rate,
            self.playback_speed,
            self.skip_silence && self.sound_mode == SoundMode::Enhanced,
            true,
        ) {
            Ok(mut voice) => {
                voice.normalization_mode = self.loudness_mode;
                voice.arm_transition_match(None, self.loudness_enabled);
                voice.gain = 1.0;
                voice.release_plan_stretch(self.playback_speed, self.device_rate);
                let info = voice.info.clone();
                let duration = info.duration_seconds;
                let (_, gain_db) = loudness_gain(voice.loudness_db, self.loudness_enabled);
                if let Ok(mut nerd) = self.nerd.lock() {
                    let correlation = nerd.swap_correlation;
                    *nerd = NerdSnapshot {
                        codec: info.codec.clone(),
                        sample_rate: info.sample_rate,
                        bit_depth: info.bit_depth,
                        channels: info.channels,
                        kbps: info.kbps,
                        loudness_gain_db: gain_db,
                        swap_correlation: correlation,
                        ..NerdSnapshot::default()
                    };
                }
                log::info!("promoted pending next: {}", info.title);
                self.current = Some(voice);
                self.duration_ms
                    .store((duration * 1000.0) as u64, Ordering::Relaxed);
                if duration > 0.0 {
                    self.events.duration_changed(duration);
                }
                self.publish_audible_position();
                self.events.handoff(info);
            }
            Err(e) => {
                log::warn!("promote pending failed for {}: {e}", request.title);
                self.events
                    .error(format!("could not prepare next track: {e}"));
            }
        }
    }

    pub(super) fn promote_incoming(&mut self) {
        if let Some(mut incoming) = self.incoming.take() {
            incoming.gain = 1.0;


            incoming.release_transition_match();
            incoming.filter.open();


            incoming.release_plan_stretch(self.playback_speed, self.device_rate);
            let info = incoming.info.clone();
            let duration = info.duration_seconds;
            self.current = Some(incoming);
            self.duration_ms
                .store((duration * 1000.0) as u64, Ordering::Relaxed);
            if duration > 0.0 {
                self.events.duration_changed(duration);
            }
            let db = self.current.as_ref().and_then(|v| v.loudness_db);
            self.publish_nerd(&info, db);
            self.publish_audible_position();
            self.events.handoff(info);
        }
        self.transition = None;
    }









    pub(super) fn swap_source(
        &mut self,
        request: TrackSource,
        crossfade_seconds: f64,
    ) -> Result<TrackInfo, String> {
        if self.current.is_none() {
            return Err("no track to swap".into());
        }



        if self.transition.is_some() {
            return Err("a transition is already running".into());
        }



        if self.pending_next.is_some() {
            if let Some(current) = &self.current {
                let plan = self
                    .pending_next
                    .as_ref()
                    .map(|request| request.plan.clone())
                    .unwrap_or_default();
                let fade_s = self.plan_seconds(&plan, current.known_duration.unwrap_or(0.0));
                let tail = self.audible_tail_s(&plan, current);
                let horizon = fade_s + ARM_LEAD_MS as f64 / 1000.0;
                if tail.is_finite() && tail <= horizon {
                    return Err("a transition is already running".into());
                }
            }
        }
        let anchor = self
            .current
            .as_ref()
            .map(|voice| voice.emitted_position_seconds())
            .unwrap_or(0.0);
        let mut request = request;

        let speed = self.playback_speed as f64;
        request.start_seconds =
            (anchor - super::swap_alignment::MAX_OFFSET_SECONDS * speed).max(0.0);
        let mut voice = Voice::open(
            &request,
            self.spatial_enabled && self.sound_mode == SoundMode::Enhanced,
            self.head_yaw,
            self.device_rate,
            self.playback_speed,
            self.skip_silence && self.sound_mode == SoundMode::Enhanced,
            false,
        )?;
        if let Ok(mut nerd) = self.nerd.lock() {
            nerd.swap_correlation = None;
            nerd.swap_target = voice.info.source.clone();
            nerd.swap_accepted = None;
        }
        if let Err(error) = super::swap_alignment::align(
            self.current.as_mut().unwrap(),
            &mut voice,
            self.device_rate,
        ) {
            if let Ok(mut nerd) = self.nerd.lock() {
                nerd.swap_accepted = Some(false);
            }
            return Err(error);
        }
        voice.gain = 0.0;
        voice.applied_gain = 0.0;
        let info = voice.info.clone();
        let seconds = if crossfade_seconds > 0.0 {
            crossfade_seconds
        } else {
            SWAP_CROSSFADE_MS / 1000.0
        };
        let fade_frames = (seconds * self.device_rate as f64).max(1.0) as u64;
        log::info!(
            "source swap: {} at {:.3}s, {:.0}ms crossfade",
            info.source,
            anchor,
            seconds * 1000.0
        );
        self.swap_probe.reset();
        if let Ok(mut nerd) = self.nerd.lock() {
            nerd.swap_correlation = None;
            nerd.swap_target = info.source.clone();
            nerd.swap_accepted = None;
        }
        self.incoming = Some(voice);
        self.transition = Some(TransitionState {
            phase: Phase::Fading,
            fade_frames,
            handed_off: true,
            plan: TransitionPlan::default(),
            arm_deadline: Instant::now(),
            swap: true,
            fx: None,
            effect_start_frame: 0,
            effect_input_gain: None,
        });


        self.publish_audible_position();
        Ok(info)
    }





















    pub(super) fn outgoing_end_s(&self, plan: &TransitionPlan, current: &Voice) -> f64 {
        let analyzed_end = (plan.transition_end_seconds.is_finite()
            && plan.transition_end_seconds > 0.0)
            .then_some(plan.transition_end_seconds);
        let decoded_end = current
            .known_duration
            .filter(|duration| duration.is_finite() && *duration > 0.0);
        if let Some(end) = analyzed_end {


            return decoded_end.map_or(end, |duration| {
                end.min(current.regions.effective_end(duration))
            });
        }
        if let Some(duration) = decoded_end {
            return current.regions.effective_end(duration);
        }
        if plan.outgoing_duration_seconds.is_finite() && plan.outgoing_duration_seconds > 0.0 {
            return plan.outgoing_duration_seconds;
        }
        current.position_seconds() + current.remaining_seconds().unwrap_or(f64::INFINITY)
    }



    pub(super) fn audible_tail_s(&self, plan: &TransitionPlan, current: &Voice) -> f64 {
        let end_s = self.outgoing_end_s(plan, current);
        if !end_s.is_finite() {
            return f64::INFINITY;
        }
        let buffered_s =
            self.buffered_frames.load(Ordering::Relaxed) as f64 / self.device_rate as f64;
        (end_s - current.position_seconds()).max(0.0) + buffered_s
    }


    pub(super) fn bail(&mut self) {

        if self.reject_pending_swap() {
            return;
        }
        let Some(t) = &self.transition else {
            return;
        };
        if !t.handed_off {

            self.incoming = None;
            self.transition = None;
            if let Some(current) = &mut self.current {
                current.gain = 1.0;
                current.filter.open();
            }
            return;
        }


        let incoming = self.incoming.take().map(|mut incoming| {
            incoming.filter.open();
            incoming.gain = 1.0;


            incoming.release_plan_stretch(self.playback_speed, self.device_rate);
            incoming.release_transition_match();
            incoming
        });
        if let Some(mut current) = self.current.take() {
            let from_gain = current.gain;
            current.filter.open();
            self.retiring.push(RetiringVoice {
                voice: current,
                from_gain,
                started_at: Instant::now(),
            });
        }





        self.current = incoming;
        self.transition = None;
    }

    pub(super) fn reject_pending_swap(&mut self) -> bool {
        if !self
            .transition
            .as_ref()
            .is_some_and(|transition| transition.swap)
        {
            return false;
        }
        if let Ok(mut nerd) = self.nerd.lock() {
            nerd.swap_accepted = Some(false);
        }
        self.incoming = None;
        self.transition = None;
        if let Some(current) = &mut self.current {
            current.gain = 1.0;
            current.filter.open();
        }
        true
    }

    pub(super) fn stop_all(&mut self) {
        self.hard_cut();
        self.playing = false;
        self.set_state(crate::PlaybackState::Stopped);
    }




    pub(super) fn publish_audible_position(&mut self) {
        let handed_off = self.transition.as_ref().is_some_and(|t| t.handed_off);
        let swap = self.transition.as_ref().is_some_and(|t| t.swap);
        let Some(voice) = (if handed_off {
            self.incoming.as_ref().or(self.current.as_ref())
        } else {
            self.current.as_ref()
        }) else {
            return;
        };
        let buffered_s =
            self.buffered_frames.load(Ordering::Relaxed) as f64 / self.device_rate.max(1) as f64;









        let audible = if handed_off && !swap {
            voice.emitted_position_seconds().max(0.0)
        } else {
            (voice.emitted_position_seconds()
                - buffered_s * voice.effective_speed as f64 * voice.plan_rate as f64)
                .max(0.0)
        };
        let processing_delay = if !self.bit_perfect {
            (self.clarity.delay_frames() as usize + self.protector.delay_frames()) as f64
                / self.device_rate.max(1) as f64
                * voice.effective_speed as f64
                * voice.plan_rate as f64
        } else {
            0.0
        };
        self.position_ms.store(
            ((audible - processing_delay).max(0.0) * 1000.0) as u64,
            Ordering::Relaxed,
        );
    }
}
