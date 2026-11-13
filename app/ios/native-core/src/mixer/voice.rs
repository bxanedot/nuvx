

use super::bridge::{BridgeStage, VoiceBridge};
use super::curves::db_to_gain;
use super::deck::{
    bound_mix_in_depth, clamp_start_seconds, glided_plan_rate, is_silent, known_duration,
    normalization_gain, silence_exceeds_floor, source_still_growing, user_stretcher,
    MAX_SILENCE_KEEP_SECS, MIN_SILENCE_SECS, MIN_VOLUME_TO_KEEP,
};
use super::types::{TrackInfo, TrackSource};
use super::DeckLoudness;
use crate::decode::resampler::StreamResampler;
use crate::decode::{SourceKind, SymphoniaDecoder};
use crate::sound::LoudnessMode;
use crate::spatial::SpatialRenderer;
use crate::time_stretch::TimeStretch;
use crate::transition_filter::TransitionFilter;

pub(super) const CHUNK_FRAMES: usize = 512;

pub(super) struct Voice {
    pub(super) _source_lease: crate::playback_regions::SourceLease,
    pub(super) head_trim_allowed: bool,
    pub(super) region_fade_in: usize,
    pub(super) regions: crate::PlaybackRegions,
    pub(super) skipped_regions: Vec<bool>,
    pub(super) decoder: SymphoniaDecoder,
    pub(super) resampler: StreamResampler,
    pub(super) user_stretch: Option<TimeStretch>,
    pub(super) spatial: SpatialRenderer,
    pub(super) filter: TransitionFilter,
    pub(super) gain: f32,


    pub(super) applied_gain: f32,


    pub(super) base_position: f64,
    pub(super) info: TrackInfo,
    pub(super) finished: bool,
    pub(super) decode_error: Option<String>,

    pub(super) emitted_dev_frames: u64,


    pub(super) device_rate: u32,


    pub(super) pending_dev: Vec<f32>,
    pub(super) pending_dev_cursor: usize,
    pub(super) skip_silence: bool,




    pub(super) silent_dev_frames: u64,





    pub(super) effective_speed: f32,


    pub(super) stretch: Option<TimeStretch>,



    pub(super) stretch_draining: bool,

    pub(super) plan_rate: f64,


    pub(super) base_plan_rate: f64,

    pub(super) post_glide_seconds: f64,


    pub(super) glide_total_frames: u64,
    pub(super) glide_done_frames: u64,




    pub(super) known_duration: Option<f64>,



    pub(super) loudness_db: Option<f64>,
    pub(super) replay_gain: crate::metadata::ReplayGain,
    pub(super) normalization_mode: LoudnessMode,
    pub(super) measured_loudness: Option<crate::LoudnessMeasurement>,
    pub(super) smoothed_normalization: f32,



    pub(super) match_db: f32,


    pub(super) smoothed_match: f32,


    pub(super) match_releasing: bool,

    pub(super) bit_perfect: bool,



    pub(super) bridge: Option<VoiceBridge>,
}

impl Voice {





    pub(super) fn open(
        request: &TrackSource,
        spatial_enabled: bool,
        head_yaw: f32,
        device_rate: u32,
        playback_speed: f32,
        skip_silence: bool,
        cue: bool,
    ) -> Result<Voice, String> {
        let source_lease = crate::playback_regions::SourceLease::new(&request.source);
        let kind = SourceKind::parse(&request.source);
        let mut decoder =
            SymphoniaDecoder::open(&kind, &request.headers).map_err(|e| e.to_string())?;
        let src_rate = decoder.sample_rate();
        crate::diagnostics::source(&request.source, src_rate);





        let mut spatial = SpatialRenderer::new(device_rate);
        spatial.set_enabled(spatial_enabled && decoder.channels() >= 2);
        spatial.set_head_yaw(head_yaw);
        let decoder_duration = decoder.duration_seconds();
        let mut duration = known_duration(decoder_duration, request.duration_seconds);




        if source_still_growing(&request.source) && request.duration_seconds > duration + 1.0 {
            duration = request.duration_seconds;
        }
        let regions = crate::playback_regions::get(&request.source).normalized(duration);
        let start = if cue {
            let cleared = clamp_start_seconds(request.start_seconds, duration);
            bound_mix_in_depth(cleared, &request.plan, duration)
        } else {
            request.start_seconds.max(0.0)
        };
        let start = if request.start_seconds <= 0.0 || cue {
            regions.next_allowed(start)
        } else {
            start
        };
        if start > 0.0 {
            decoder.seek_seconds(start).map_err(|e| e.to_string())?;
        }
        log::info!(
            "voice opened: title={:?} requested_start={:.3}s clamped_start={:.3}s actual_start={:.3}s \
             duration={:.1}s (container={:?} declared={:.1}s) source_rate={} device_rate={}",
            request.title,
            request.start_seconds,
            start,
            decoder.position_seconds(),
            duration,
            decoder_duration,
            request.duration_seconds,
            src_rate,
            device_rate,
        );
        let plan_rate = if request.plan.playback_rate > 0.05 {
            request.plan.playback_rate
        } else {
            1.0
        };







        let effective = (playback_speed as f64).clamp(0.5, 2.0) as f32;
        Ok(Voice {
            _source_lease: source_lease,
            head_trim_allowed: request.start_seconds <= 0.0 || cue,
            region_fade_in: 0,
            skipped_regions: vec![false; regions.excluded.len()],
            regions,
            resampler: StreamResampler::stereo(src_rate, device_rate),
            user_stretch: user_stretcher(device_rate, effective),
            spatial,
            filter: TransitionFilter::new(2, device_rate),
            gain: 1.0,
            applied_gain: 1.0,
            base_position: 0.0,
            info: TrackInfo {
                title: request.title.clone(),
                artist: request.artist.clone(),
                source: request.source.clone(),
                duration_seconds: duration,
                codec: decoder.codec().to_string(),
                sample_rate: src_rate,
                bit_depth: decoder.bit_depth(),
                channels: decoder.channels() as u32,
                kbps: request.claimed_kbps,
            },
            decoder,
            finished: false,
            decode_error: None,
            emitted_dev_frames: 0,
            device_rate,
            pending_dev: Vec::new(),
            pending_dev_cursor: 0,
            skip_silence,
            silent_dev_frames: 0,
            effective_speed: effective,
            stretch: None,
            stretch_draining: false,
            plan_rate,
            base_plan_rate: plan_rate,
            post_glide_seconds: request.plan.post_glide_seconds.max(0.0),
            glide_total_frames: 0,
            glide_done_frames: 0,
            known_duration: (duration > 0.0).then_some(duration),
            loudness_db: request.loudness_db,
            replay_gain: if let SourceKind::Path(path) = &kind {
                crate::metadata::read_replay_gain(path)
            } else {
                crate::metadata::ReplayGain::default()
            },
            normalization_mode: LoudnessMode::Off,
            measured_loudness: None,
            smoothed_normalization: 1.0,
            match_db: 0.0,
            smoothed_match: 1.0,
            match_releasing: false,
            bit_perfect: false,
            bridge: None,
        })
    }



    pub(super) fn deck_loudness(&self, normalization: bool) -> DeckLoudness {
        DeckLoudness {
            lufs: self.measured_loudness.as_ref().map(|m| m.track_lufs),
            relative_db: self.loudness_db,
            applied_normalization_db: normalization_gain(self, normalization)
                .1
                .map(|db| db as f64),
            true_peak_dbtp: self.measured_loudness.as_ref().and_then(|m| m.true_peak_dbtp),
        }
    }









    pub(super) fn arm_transition_match(&mut self, outgoing: Option<&Voice>, normalization: bool) {


        self.smoothed_normalization = normalization_gain(self, normalization).0;
        let db = outgoing.and_then(|outgoing| {
            DeckLoudness::match_gain_db(
                self.deck_loudness(normalization),
                outgoing.deck_loudness(normalization),
            )
        });
        match db.filter(|db| db.abs() >= 0.05) {
            Some(db) => {
                if (db as f32 - self.match_db).abs() >= 0.05 || self.match_releasing {
                    log::info!(
                        "transition loudness match: {db:+.1} dB (applied before the first frame)"
                    );
                }
                self.match_db = db as f32;
                self.smoothed_match = db_to_gain(db) as f32;
                self.match_releasing = false;
            }
            None => {
                if self.match_db.abs() >= 0.05 {
                    log::info!("transition loudness match: cleared before the first frame");
                }
                self.match_db = 0.0;
                self.smoothed_match = 1.0;
                self.match_releasing = false;
            }
        }
    }






    pub(super) fn release_transition_match(&mut self) {
        if self.match_db.abs() < 1e-4 {
            return;
        }
        self.match_db = 0.0;
        self.match_releasing = true;
    }

    pub(super) fn mix_into(
        &mut self,
        samples: &[f32],
        destination: &mut [f32],
        fade_gain: f32,
        normalization: bool,
    ) {
        let target = normalization_gain(self, normalization).0;
        let alpha = 1.0 - (-1.0 / (self.device_rate as f32 * 0.050)).exp();



        let match_seconds = if self.match_releasing { 1.5 } else { 0.4 };
        let alpha_match = 1.0 - (-1.0 / (self.device_rate as f32 * match_seconds)).exp();
        let target_match = db_to_gain(self.match_db as f64) as f32;




        let start_gain = self.applied_gain;
        self.applied_gain = fade_gain;
        let frames = (samples.len() / 2).max(1) as f32;
        for (index, (source, mix)) in samples
            .chunks_exact(2)
            .zip(destination.chunks_exact_mut(2))
            .enumerate()
        {
            let progress = index as f32 / (frames - 1.0).max(1.0);
            let fade = start_gain + (fade_gain - start_gain) * progress;
            self.smoothed_normalization += (target - self.smoothed_normalization) * alpha;
            if (target - self.smoothed_normalization).abs() < 1e-6 {
                self.smoothed_normalization = target;
            }
            self.smoothed_match += (target_match - self.smoothed_match) * alpha_match;
            let gain = fade * self.smoothed_normalization * self.smoothed_match;
            mix[0] += source[0] * gain;
            mix[1] += source[1] * gain;
        }
    }

    pub(super) fn set_playback_speed(&mut self, speed: f32, device_rate: u32) {
        let effective = (speed as f64).clamp(0.5, 2.0) as f32;
        if (effective - self.effective_speed).abs() < 0.001 {
            return;
        }
        self.effective_speed = effective;
        if let Some(stretch) = &mut self.user_stretch {
            stretch.set_rate(effective as f64);
        } else {
            self.user_stretch = user_stretcher(device_rate, effective);
        }
    }









    pub(super) fn set_plan_rate(&mut self, rate: f64) {
        let rate = if rate.is_finite() && rate > 0.0 {
            rate
        } else {
            1.0
        };
        self.plan_rate = rate;
        if (rate - 1.0).abs() <= 1e-4 {

            self.stretch_draining = self.stretch.is_some();
        } else {
            self.stretch_draining = false;
            if let Some(stretch) = self.stretch.as_mut() {
                stretch.set_rate(rate);
            } else {
                self.stretch = Some(TimeStretch::with_sample_rate(rate, self.device_rate));
            }
        }
    }




    pub(super) fn drain_stretch(&mut self) -> Vec<f32> {
        match self.stretch.as_mut() {
            Some(stretch) => {
                stretch.flush();
                let tail = stretch.take();
                self.stretch = None;
                log::info!("handoff stretch retired: {}", self.info.title);
                tail
            }
            None => Vec::new(),
        }
    }















    pub(super) fn release_plan_stretch(&mut self, speed: f32, device_rate: u32) {
        self.glide_total_frames = 0;
        self.glide_done_frames = 0;
        if self.stretch.is_none()
            && (self.plan_rate - 1.0).abs() < f64::EPSILON
            && !self.stretch_draining
        {
            return;
        }


        self.plan_rate = 1.0;
        self.stretch_draining = self.stretch.is_some();
        self.set_playback_speed(speed, device_rate);
    }




    pub(super) fn begin_tempo_glide(&mut self, device_rate: u32) {
        if self.post_glide_seconds <= 0.05 || (self.base_plan_rate - 1.0).abs() < 1e-4 {
            return;
        }
        let frames = (self.post_glide_seconds * device_rate.max(1) as f64)
            .round()
            .max(1.0) as u64;
        self.glide_total_frames = frames;
        self.glide_done_frames = 0;
        log::info!(
            "handoff tempo glide: {} duration={:.2}s",
            self.info.title,
            self.post_glide_seconds
        );
        self.set_plan_rate(self.base_plan_rate);
    }




    pub(super) fn drive_tempo_glide(&mut self, device_rate: u32, speed: f32) {
        if self.glide_total_frames == 0 {
            return;
        }
        let p = (self.glide_done_frames as f64 / self.glide_total_frames as f64).clamp(0.0, 1.0);
        if p >= 1.0 {
            self.release_plan_stretch(speed, device_rate);
            return;
        }
        let target = glided_plan_rate(self.base_plan_rate, p);
        if (target - self.plan_rate).abs() >= 1e-5 {
            self.set_plan_rate(target);
        }
    }

    pub(super) fn note_glide_frames(&mut self, frames: u64) {
        if self.glide_total_frames > 0 {
            self.glide_done_frames = self.glide_done_frames.saturating_add(frames);
        }
    }


    pub(super) fn retarget_device(
        &mut self,
        device_rate: u32,
        spatial_enabled: bool,
        head_yaw: f32,
    ) {
        let src = self.info.sample_rate.max(1);
        self.device_rate = device_rate;
        let enabled = self.spatial.enabled();
        self.resampler = StreamResampler::stereo(src, device_rate);
        self.user_stretch = user_stretcher(device_rate, self.effective_speed);
        self.stretch = None;
        self.stretch_draining = false;
        let mut spatial = SpatialRenderer::new(device_rate);
        spatial.set_enabled(enabled && spatial_enabled && self.info.channels >= 2);
        spatial.set_head_yaw(head_yaw);
        self.spatial = spatial;
        self.filter = TransitionFilter::new(2, device_rate);
        self.pending_dev.clear();
        self.pending_dev_cursor = 0;
    }

    pub(super) fn reset_processing(&mut self) {
        self.region_fade_in = 0;
        self.resampler.reset();
        self.user_stretch = user_stretcher(self.device_rate, self.effective_speed);
        self.stretch = None;
        self.stretch_draining = false;
        self.plan_rate = 1.0;
        self.glide_total_frames = 0;
        self.glide_done_frames = 0;
        self.spatial.flush();
        self.filter.flush();
        self.pending_dev.clear();
        self.pending_dev_cursor = 0;
        self.silent_dev_frames = 0;
        self.finished = false;
    }

    pub(super) fn position_seconds(&self) -> f64 {
        let decoder = self.base_position + self.decoder.position_seconds();





        (decoder
            - self.stretch_latency_seconds()
            - self.resampler.delay_frames() / self.device_rate as f64
            - self
                .user_stretch
                .as_ref()
                .map(|s| s.latency_frames() / self.device_rate as f64)
                .unwrap_or(0.0))
        .max(0.0)
    }


    pub(super) fn stretch_latency_seconds(&self) -> f64 {
        self.stretch
            .as_ref()
            .map(|stretch| stretch.latency_frames() / self.device_rate.max(1) as f64)
            .unwrap_or(0.0)
    }











    pub(super) fn emitted_position_seconds(&self) -> f64 {
        let pending_frames = (self
            .pending_dev
            .len()
            .saturating_sub(self.pending_dev_cursor)
            / 2) as f64;
        let pending_seconds = pending_frames / self.device_rate.max(1) as f64
            * self.effective_speed as f64
            * self.plan_rate as f64;
        (self.position_seconds() - pending_seconds).max(0.0)
    }








    pub(super) fn remaining_seconds(&self) -> Option<f64> {
        let total = self.regions.effective_end(self.known_duration?);
        Some((total - self.decoder.position_seconds()).max(0.0))
    }




    pub(super) fn audible_remaining_seconds(&self) -> Option<f64> {
        let total = self.regions.effective_end(self.known_duration?);
        Some((total - self.emitted_position_seconds()).max(0.0))
    }



    pub(super) fn buffer_preview(&mut self, frames: usize, device_rate: u32) {
        let emitted = self.emitted_dev_frames;
        let preview = self.pull(frames, device_rate);
        let rest = self.pending_dev.split_off(self.pending_dev_cursor);
        self.pending_dev = preview;
        self.pending_dev.extend_from_slice(&rest);
        self.pending_dev_cursor = 0;
        self.emitted_dev_frames = emitted;
    }










    pub(super) fn prime_for_fade(&mut self, frames: usize, device_rate: u32) {
        if frames == 0 || self.finished || !self.pending_dev.is_empty() {
            return;
        }


        let planned_rate = self.base_plan_rate;
        if (planned_rate - 1.0).abs() > 1e-4 {
            self.set_plan_rate(planned_rate);
        }
        let prefetched = self.pull(frames, device_rate);
        if prefetched.is_empty() {
            return;
        }
        let rest = self.pending_dev.split_off(self.pending_dev_cursor);
        self.pending_dev = prefetched;
        self.pending_dev.extend_from_slice(&rest);
        self.pending_dev_cursor = 0;
        self.emitted_dev_frames = 0;


        if (planned_rate - 1.0).abs() > 1e-4 && (self.plan_rate - planned_rate).abs() > 1e-9 {
            self.set_plan_rate(planned_rate);
        }
    }












    pub(super) fn pull(&mut self, frames: usize, device_rate: u32) -> Vec<f32> {
        let mut out = Vec::with_capacity(frames * 2);
        self.pull_into(frames, device_rate, &mut out);
        out
    }


    pub(super) fn pull_into(&mut self, frames: usize, device_rate: u32, out: &mut Vec<f32>) {
        out.clear();
        if out.capacity() < frames * 2 {
            out.reserve(frames * 2);
        }
        if let Some(bridge) = self.bridge.as_mut() {
            if bridge.stage == BridgeStage::Replaying {
                bridge.replay_into(frames, device_rate, out);
                self.emitted_dev_frames += (out.len() / 2) as u64;
                if bridge.stage == BridgeStage::Done && out.len() < frames * 2 {
                    self.pull_decoded_into((frames * 2 - out.len()) / 2, device_rate, out);
                }
                return;
            }
        }
        self.pull_decoded_into(frames, device_rate, out);
        if let Some(mut bridge) = self.bridge.take() {
            let held = bridge.after_pull(out, &mut self.pending_dev, &mut self.pending_dev_cursor);
            if held > 0 {
                self.emitted_dev_frames = self.emitted_dev_frames.saturating_sub(held as u64);
            }
            self.bridge = Some(bridge);
        }
    }




    pub(super) fn set_bridge(&mut self, delay_frames: u64, unit_frames: u64, repeats: u32) {
        if unit_frames == 0 || self.bit_perfect {
            return;
        }
        self.bridge = Some(VoiceBridge::new(delay_frames, unit_frames, repeats));
    }

    fn pull_decoded_into(&mut self, frames: usize, device_rate: u32, out: &mut Vec<f32>) {
        if self.bit_perfect
            && device_rate == self.info.sample_rate
            && self.regions.excluded.is_empty()
            && self.regions.audible_start_seconds == 0.0
            && self.regions.audible_end_seconds.is_none()
        {
            self.pull_bitperfect_into(frames, out);
            return;
        }
        let want = out.len() + (frames * 2).max(2);
        if out.capacity() < want {
            out.reserve(want - out.len());
        }

        let serve_pending = |out: &mut Vec<f32>, voice: &mut Voice| {
            let remaining = want - out.len();
            if remaining == 0 || voice.pending_dev_cursor >= voice.pending_dev.len() {
                return;
            }
            let take = (voice.pending_dev.len() - voice.pending_dev_cursor).min(remaining);
            out.extend_from_slice(
                &voice.pending_dev[voice.pending_dev_cursor..voice.pending_dev_cursor + take],
            );
            voice.pending_dev_cursor += take;
            voice.emitted_dev_frames += (take / 2) as u64;
        };
        serve_pending(out, self);
        if out.len() >= want {


            return;
        }




        if self.stretch_draining {
            self.stretch_draining = false;
            let tail = self.drain_stretch();
            if !tail.is_empty() {
                let remaining = want - out.len();
                let take = tail.len().min(remaining);
                out.extend_from_slice(&tail[..take]);
                self.emitted_dev_frames += (take / 2) as u64;
                if take < tail.len() {
                    self.pending_dev = tail;
                    self.pending_dev_cursor = take;
                }
                if out.len() >= want {
                    return;
                }
            }
        }

        while out.len() < want && !self.finished {
            let at = self.decoder.position_seconds();
            if self.head_trim_allowed && at < self.regions.audible_start_seconds {



                self.head_trim_allowed = false;
                if self
                    .decoder
                    .seek_seconds(self.regions.next_allowed(at))
                    .is_ok()
                {
                    self.reset_processing();
                    continue;
                }
            }
            let end = self
                .regions
                .effective_end(self.known_duration.unwrap_or(0.0));
            let reached_end = end > 0.0 && at >= end;
            if let Some((index, segment)) =
                self.regions.excluded.iter().enumerate().find(|(i, s)| {
                    !self.skipped_regions[*i]
                        && at >= s.start_seconds - 0.0001
                        && at < s.end_seconds
                })
            {
                let target = segment.end_seconds;
                self.skipped_regions[index] = true;
                match self.decoder.seek_seconds(target) {
                    Ok(()) => {
                        self.reset_processing();
                        self.region_fade_in = (self.info.sample_rate as usize / 200).max(1);
                        log::info!("non-music segment skipped {:.3}..{:.3}", at, target);
                    }
                    Err(error) => {
                        log::warn!("segment seek failed: {error}");
                    }
                }
                continue;
            }
            let mut until = if end > 0.0 { end } else { f64::INFINITY };
            for (i, s) in self.regions.excluded.iter().enumerate() {
                if !self.skipped_regions[i] && s.start_seconds > at {
                    until = until.min(s.start_seconds);
                }
            }
            let frames = (((until - at) * self.info.sample_rate as f64).ceil() as usize)
                .max(1)
                .min(crate::diagnostics::chunk());
            let mut src = if reached_end {
                Vec::new()
            } else {
                self.decoder.read_stereo(frames).unwrap_or_else(|e| {
                    self.decode_error = Some(format!("decode failed: {e}"));
                    Vec::new()
                })
            };


            let ramp_frames = (self.info.sample_rate as usize / 200).max(1);
            let soften_cut = self
                .regions
                .excluded
                .iter()
                .any(|s| s.start_seconds == until)
                || self.regions.audible_end_seconds == Some(until);
            if self.region_fade_in > 0 || soften_cut {
                for (i, frame) in src.chunks_exact_mut(2).enumerate() {
                    let incoming_gain = if self.region_fade_in > 0 {
                        let gain = 1.0 - self.region_fade_in as f32 / ramp_frames as f32;
                        self.region_fade_in -= 1;
                        gain
                    } else {
                        1.0
                    };
                    let to_cut = until - (at + i as f64 / self.info.sample_rate as f64);
                    let outgoing_gain = if soften_cut {
                        (to_cut / 0.005).clamp(0.0, 1.0) as f32
                    } else {
                        1.0
                    };
                    frame[0] *= incoming_gain * outgoing_gain;
                    frame[1] *= incoming_gain * outgoing_gain;
                }
            }
            if src.is_empty() {
                self.finished = true;
                self.pending_dev = self.resampler.flush();
                self.pending_dev_cursor = 0;
                if let Some(stretch) = &mut self.user_stretch {
                    let mut tail = stretch.process_and_take(&self.pending_dev);
                    stretch.flush();
                    tail.extend(stretch.take());
                    self.pending_dev = tail;
                }





                if self.stretch.is_some() {
                    let input = core::mem::take(&mut self.pending_dev);
                    let mut tail = self
                        .stretch
                        .as_mut()
                        .expect("checked above")
                        .process_and_take(&input);
                    let mut rest = self.drain_stretch();
                    tail.append(&mut rest);
                    self.pending_dev = tail;
                }
                if self.spatial.enabled() || self.filter.active() {
                    self.pending_dev
                        .extend(vec![0.0; self.device_rate as usize]);
                }


                self.spatial.process(&mut self.pending_dev);
                self.filter.process(&mut self.pending_dev);
                serve_pending(out, self);
                return;
            }
            crate::diagnostics::capture_voice(0, &self.info.source, &src);
            self.pending_dev = self.resampler.process(&src);
            self.pending_dev_cursor = 0;
            if let Some(stretch) = &mut self.user_stretch {
                self.pending_dev = stretch.process_and_take(&self.pending_dev);
            }





            if self.stretch.is_some() {
                let input = core::mem::take(&mut self.pending_dev);
                self.pending_dev = self
                    .stretch
                    .as_mut()
                    .expect("checked above")
                    .process_and_take(&input);
            }




            let silent = self.skip_silence && is_silent(&self.pending_dev);
            self.spatial.process(&mut self.pending_dev);
            self.filter.process(&mut self.pending_dev);
            crate::diagnostics::capture_voice(1, &self.info.source, &self.pending_dev);
            if silent {
                self.silent_dev_frames += (self.pending_dev.len() / 2) as u64;





                if silence_exceeds_floor(self.silent_dev_frames, device_rate) {
                    let silent_s = self.silent_dev_frames as f64 / device_rate as f64;
                    if silent_s <= MIN_SILENCE_SECS + MAX_SILENCE_KEEP_SECS {

                        let mut kept: Vec<f32> = Vec::with_capacity(self.pending_dev.len() / 5 + 2);
                        for frame in self.pending_dev.chunks_exact(2).step_by(5) {
                            kept.push(frame[0] * MIN_VOLUME_TO_KEEP);
                            kept.push(frame[1] * MIN_VOLUME_TO_KEEP);
                        }
                        self.pending_dev = kept;
                        self.pending_dev_cursor = 0;
                    } else {
                        self.pending_dev.clear();
                        self.pending_dev_cursor = 0;
                        continue;
                    }
                }
            } else {
                self.silent_dev_frames = 0;
            }
            serve_pending(out, self);
        }
    }




    fn pull_bitperfect_into(&mut self, _frames: usize, out: &mut Vec<f32>) {
        if self.finished {
            return;
        }
        match self.decoder.read_stereo(_frames) {
            Ok(samples) => {
                if samples.is_empty() {
                    self.finished = true;
                } else {
                    self.emitted_dev_frames += (samples.len() / 2) as u64;
                }
                out.extend_from_slice(&samples)
            }
            Err(error) => {
                log::warn!("bit-perfect source read failed: {error}");
                self.finished = true;
            }
        }
    }
}
