

use super::curves::fall_gain;
use super::deck::normalization_gain;
use super::state::{MixerState, Phase};
use super::voice::CHUNK_FRAMES;
use super::BAIL_MS;
use crate::sound::SoundMode;
use rtrb::Producer;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

fn diagnostic_stage_signature(state: &MixerState) -> u32 {
    let primary = if state.transition.as_ref().is_some_and(|t| t.handed_off) {
        state.incoming.as_ref().or(state.current.as_ref())
    } else {
        state.current.as_ref()
    };
    let mut signature = 0u32;
    let mut set = |bit: u32, active: bool| {
        if active {
            signature |= 1 << bit;
        }
    };
    if let Some(voice) = primary {
        set(0, !voice.resampler.passthrough());
        set(1, voice.info.channels != 2);
        set(2, voice.user_stretch.is_some());
        set(3, voice.stretch.is_some());
        set(4, voice.spatial.enabled());
        set(5, voice.skip_silence);
    }
    set(6, state.transition.is_some());
    set(7, state.volume != 1.0 || state.sleep_gain != 1.0);
    set(8, state.clarity.active());
    set(9, state.clarity.active() && state.eq.active());
    set(
        10,
        state
            .current
            .iter()
            .chain(state.incoming.iter())
            .any(|voice| voice.spatial.enabled()),
    );
    set(
        11,
        state
            .current
            .iter()
            .chain(state.incoming.iter())
            .any(|voice| voice.stretch.is_some()),
    );
    set(
        12,
        state
            .current
            .iter()
            .chain(state.incoming.iter())
            .any(|voice| voice.user_stretch.is_some()),
    );
    set(
        13,
        state
            .current
            .iter()
            .chain(state.incoming.iter())
            .any(|voice| voice.filter.active()),
    );
    let (_, loudness_gain_db) = primary.map_or((1.0, None), |voice| {
        normalization_gain(voice, state.loudness_enabled)
    });
    set(14, loudness_gain_db.is_some());
    set(
        15,
        loudness_gain_db.is_none()
            && state
                .current
                .as_ref()
                .is_some_and(|voice| voice.smoothed_normalization != 1.0),
    );
    signature
}


pub(super) fn render_available(state: &mut MixerState, ring: &mut Producer<f32>) {
    if state.underrun_counter.is_some()
        && (state.flush_ring.load(Ordering::Acquire) || state.bail_flush.load(Ordering::Acquire))
    {
        return;
    }
    if state.bit_perfect {
        render_bitperfect_available(state, ring);
        return;
    }
    for voice in state.current.iter_mut().chain(state.incoming.iter_mut()) {
        voice.normalization_mode = state.loudness_mode;
    }
    let chunk_samples = CHUNK_FRAMES * 2;
    let mut scratch = std::mem::take(&mut state.render_scratch);
    let mut voice_scratch = std::mem::take(&mut state.voice_output_scratch);
    let mut dry_scratch = std::mem::take(&mut state.effect_dry_scratch);
    let mut retiring_storage = std::mem::take(&mut state.retiring_scratch);
    loop {















        state.consider_arm();
        state.consider_start_fade();
        if state.buffered_frames.load(Ordering::Relaxed)
            >= state.device_rate as u64 * state.queue_target_ms / 1000
        {
            break;
        }
        let free = ring.slots();
        if free < chunk_samples {
            break;
        }
        scratch.resize(chunk_samples, 0.0);
        scratch.fill(0.0);
        let mut audible = false;



        let mut produced = 0usize;


        let transition_done = state.drive_fade();
        if transition_done {
            state.finish_transition();
        }



        let current_finished = state
            .current
            .as_ref()
            .is_some_and(|c| c.finished && c.pending_dev_cursor >= c.pending_dev.len());
        if current_finished && state.transition.is_none() {
            if state.incoming.is_some() {
                state.promote_incoming();
            } else if state.pending_next.is_some() {
                state.promote_pending();
            }
        }


        std::mem::swap(&mut state.retiring, &mut retiring_storage);
        while let Some(mut ret) = retiring_storage.pop() {
            let elapsed = ret.started_at.elapsed().as_millis() as f64 / BAIL_MS as f64;
            if elapsed < 1.0 {
                ret.voice
                    .pull_into(CHUNK_FRAMES, state.device_rate, &mut voice_scratch);
                if !voice_scratch.is_empty() {
                    audible = true;
                    produced = produced.max(voice_scratch.len());
                    ret.voice.mix_into(
                        &voice_scratch,
                        &mut scratch,
                        ret.from_gain * fall_gain(elapsed),
                        state.loudness_enabled,
                    );
                }
                state.retiring.push(ret);
            }
        }





        let swap_fade = state.transition.as_ref().is_some_and(|t| t.swap);
        if let Some(voice) = &mut state.current {
            if state.playing {
                let rate = state.device_rate;
                let speed = state.playback_speed;
                voice.drive_tempo_glide(rate, speed);
                voice.pull_into(CHUNK_FRAMES, rate, &mut voice_scratch);
                let frames = &voice_scratch;
                voice.note_glide_frames((frames.len() / 2) as u64);
                let effect_active = state.transition.as_ref().is_some_and(|t| {
                    t.phase == Phase::Fading
                        && t.fx.is_some()
                        && state
                            .incoming
                            .as_ref()
                            .is_some_and(|voice| voice.emitted_dev_frames >= t.effect_start_frame)
                });
                let effect = if effect_active {
                    state
                        .transition
                        .as_mut()
                        .filter(|t| t.phase == Phase::Fading)
                        .and_then(|t| {
                            let gain = *t.effect_input_gain.get_or_insert(voice.gain);
                            t.fx.as_mut().map(|fx| (fx, gain))
                        })
                } else {
                    None
                };
                if let Some((effect, input_gain)) = effect {


                    dry_scratch.resize(frames.len(), 0.0);
                    dry_scratch.fill(0.0);
                    voice.mix_into(
                        &frames,
                        &mut dry_scratch,
                        input_gain,
                        state.loudness_enabled,
                    );
                    effect.render(&dry_scratch, &mut scratch, CHUNK_FRAMES);
                    audible = true;
                    produced = produced.max(chunk_samples);
                } else if !frames.is_empty() {
                    audible = true;
                    produced = produced.max(frames.len());
                    if swap_fade {
                        state.swap_probe.observe_current(&frames);
                    }
                    voice.mix_into(&frames, &mut scratch, voice.gain, state.loudness_enabled);
                }
            }
        }
        for voice in state.current.iter_mut().chain(state.incoming.iter_mut()) {
            if let Some(error) = voice.decode_error.take() {
                state.events.source_error(voice.info.source.clone(), error);
            }
        }
        if state.playing {
            state.publish_audible_position();
        }



        let incoming_renders = state
            .transition
            .as_ref()
            .is_some_and(|t| t.phase == Phase::Fading);
        if let Some(voice) = &mut state.incoming {
            if state.playing && incoming_renders {
                voice.pull_into(CHUNK_FRAMES, state.device_rate, &mut voice_scratch);
                if !voice_scratch.is_empty() {
                    audible = true;
                    produced = produced.max(voice_scratch.len());
                    if swap_fade {
                        state.swap_probe.observe_incoming(&voice_scratch);
                    }
                    voice.mix_into(
                        &voice_scratch,
                        &mut scratch,
                        voice.gain,
                        state.loudness_enabled,
                    );
                }
            }
        }

        let draining_pipeline = produced == 0 && state.pipeline_tail_frames > 0;
        if produced > 0 {


            state.pipeline_tail_frames = state.protector.delay_frames()
                + state.clarity.delay_frames() as usize
                + if state.sound_mode == SoundMode::Enhanced {
                    state.device_rate as usize * 3
                } else {
                    0
                };
        } else if produced == 0 && state.pipeline_tail_frames > 0 {
            let frames = state.pipeline_tail_frames.min(CHUNK_FRAMES);
            produced = frames * 2;
            state.pipeline_tail_frames -= frames;
            audible = true;
        }
        if !audible {




            let any_pending = state.current.as_ref().is_some_and(|c| !c.finished)
                || state.incoming.is_some()
                || state.pending_next.is_some()
                || state.transition.is_some();
            let ring_drained = state.buffered_frames.load(Ordering::Relaxed) == 0;
            if !any_pending && ring_drained && state.retiring.is_empty() {
                if state.playing {
                    log::info!("queue drained; natural end");
                    state.playing = false;
                    state.set_state(crate::PlaybackState::Stopped);
                    state.events.track_ended(
                        crate::TrackEndReason::Natural,
                        state
                            .current
                            .as_ref()
                            .map(|v| v.info.source.clone())
                            .unwrap_or_default(),
                    );
                }
                break;
            }
        }



        let mut pushed = 0;
        if produced > 0 {
            if state.sound_mode == SoundMode::Enhanced {
                state.eq.process(&mut scratch[..produced]);
            }
            state.clarity.process(&mut scratch[..produced]);
            state.protector.process(
                &mut scratch[..produced],
                state.sound_mode == SoundMode::Enhanced,
            );
            let signature = diagnostic_stage_signature(state);
            let now = Instant::now();
            let publish_diagnostics = state.last_diagnostic_signature != Some(signature)
                || state
                    .last_diagnostic_publish
                    .is_none_or(|last| now.duration_since(last) >= Duration::from_millis(250));
            if publish_diagnostics {
                state.last_diagnostic_signature = Some(signature);
                state.last_diagnostic_publish = Some(now);
            }
            if publish_diagnostics {
                if let Ok(mut nerd) = state.nerd.lock() {
                    let mut stages = Vec::new();
                    let primary = if state.transition.as_ref().is_some_and(|t| t.handed_off) {
                        state.incoming.as_ref().or(state.current.as_ref())
                    } else {
                        state.current.as_ref()
                    };
                    if let Some(v) = primary {
                        if !v.resampler.passthrough() {
                            stages.push("Rate conversion (libsoxr HQ)".into());
                        }
                        nerd.converter_delay_frames = v.resampler.delay_frames();
                        nerd.decoder_implementation = v.decoder.implementation().into();
                        nerd.decoded_layout = v.decoder.layout();
                        nerd.loudness_origin =
                            if v.replay_gain.gain_db(v.normalization_mode).is_some() {
                                "ReplayGain metadata"
                            } else if v.measured_loudness.is_some() {
                                "EBU R128 / BS.1770 LUFS"
                            } else if v.loudness_db.is_some() {
                                "YouTube relative loudness (not LUFS)"
                            } else {
                                "No measurement"
                            }
                            .into();
                        if v.info.channels != 2 {
                            stages.push("Channel mapping".into());
                        }
                        if v.user_stretch.is_some() {
                            stages.push("Pitch-preserving speed".into());
                        }
                        if v.stretch.is_some() {
                            stages.push("Automix tempo matching".into());
                        }
                        if v.spatial.enabled() {
                            stages.push("Spatial / head tracking".into());
                        }
                        if v.skip_silence {
                            stages.push("Silence trimming".into());
                        }
                    }
                    if state.transition.is_some() {
                        stages.push("Transition".into());
                    }
                    if state.volume != 1.0 || state.sleep_gain != 1.0 {
                        stages.push("Application volume".into());
                    }
                    if state.clarity.active() {
                        stages.push("Enhanced clarity (4× exciter)".into());
                        if state.eq.active() {
                            stages.push("Manual EQ / tone".into());
                        }
                        stages.push("True-peak protection".into());
                    }
                    for v in state.current.iter().chain(state.incoming.iter()) {
                        for (active, name) in [
                            (v.spatial.enabled(), "Spatial / head tracking"),
                            (v.stretch.is_some(), "Automix tempo matching"),
                            (v.user_stretch.is_some(), "Pitch-preserving speed"),
                            (v.filter.active(), "Transition filters"),
                        ] {
                            if active && !stages.iter().any(|s| s == name) {
                                stages.push(name.into());
                            }
                        }
                    }
                    if let Some(v) = primary {
                        nerd.loudness_gain_db = normalization_gain(v, state.loudness_enabled).1;
                    }
                    if nerd.loudness_gain_db.is_some() {
                        stages.push("Normalization".into());
                    } else if state
                        .current
                        .as_ref()
                        .is_some_and(|v| v.smoothed_normalization != 1.0)
                    {
                        stages.push("Normalization settling".into());
                    }
                    nerd.active_stages = stages;
                    nerd.protection_reduction_db = state.protector.reduction_db;
                    nerd.protection_interventions = state.protector.interventions
                        + state
                            .current
                            .as_ref()
                            .map(|v| v.decoder.sanitized_samples())
                            .unwrap_or(0);
                    nerd.queued_target_ms = state.queue_target_ms;
                    nerd.build_revision = env!("NUVX_CORE_REVISION").into();
                }
            }

            if draining_pipeline
                && state.pipeline_tail_frames < state.device_rate as usize * 3
                && scratch[..produced].iter().all(|s| s.abs() < 1e-7)
            {
                state.pipeline_tail_frames = 0;
            }
            crate::diagnostics::capture(2, &scratch[..produced]);
            state
                .buffered_frames
                .fetch_add(produced as u64 / 2, Ordering::Release);
            for &sample in &scratch[..produced] {
                if ring.push(sample).is_ok() {
                    pushed += 1;
                } else {
                    break;
                }
            }
        }
        if pushed < produced {
            let missing = ((produced - pushed) / 2) as u64;
            let _ = state
                .buffered_frames
                .fetch_update(Ordering::AcqRel, Ordering::Relaxed, |v| {
                    Some(v.saturating_sub(missing))
                });
        }

        break;
    }
    state.render_scratch = scratch;
    state.voice_output_scratch = voice_scratch;
    state.effect_dry_scratch = dry_scratch;
    state.retiring_scratch = retiring_storage;
}




pub(super) fn render_bitperfect_available(state: &mut MixerState, ring: &mut Producer<f32>) {
    if state.underrun_counter.is_some()
        && (state.flush_ring.load(Ordering::Acquire) || state.bail_flush.load(Ordering::Acquire))
    {
        return;
    }
    let chunk_samples = CHUNK_FRAMES * 2;
    let mut voice_scratch = std::mem::take(&mut state.voice_output_scratch);
    while ring.slots() >= chunk_samples {
        if state.buffered_frames.load(Ordering::Relaxed)
            >= state.device_rate as u64 * state.queue_target_ms / 1000
        {
            break;
        }
        if !state.playing {
            break;
        }
        let frame_count = match &mut state.current {
            Some(voice) if voice.bit_perfect && voice.info.sample_rate == state.device_rate => {
                voice.pull_into(CHUNK_FRAMES, state.device_rate, &mut voice_scratch);
                voice_scratch.len()
            }
            Some(_) => {


                state.voice_output_scratch = voice_scratch;
                state.bit_perfect = false;
                return render_available(state, ring);
            }
            None => {
                voice_scratch.clear();
                0
            }
        };
        if let Some(voice) = &mut state.current {
            if let Some(error) = voice.decode_error.take() {
                state.events.source_error(voice.info.source.clone(), error);
            }
        }
        if frame_count == 0 {
            let exhausted = state.current.as_ref().is_none_or(|voice| voice.finished);
            if exhausted && state.buffered_frames.load(Ordering::Relaxed) == 0 {
                state.playing = false;
                state.set_state(crate::PlaybackState::Stopped);
                state.events.track_ended(
                    crate::TrackEndReason::Natural,
                    state
                        .current
                        .as_ref()
                        .map(|v| v.info.source.clone())
                        .unwrap_or_default(),
                );
            }
            break;
        }
        state.publish_audible_position();
        let signature = 1 << 31;
        let now = Instant::now();
        let publish_diagnostics = state.last_diagnostic_signature != Some(signature)
            || state
                .last_diagnostic_publish
                .is_none_or(|last| now.duration_since(last) >= Duration::from_millis(250));
        if publish_diagnostics {
            state.last_diagnostic_signature = Some(signature);
            state.last_diagnostic_publish = Some(now);
        }
        if publish_diagnostics {
            if let Ok(mut nerd) = state.nerd.lock() {
                nerd.active_stages.clear();
                nerd.converter_delay_frames = 0.0;
                nerd.protection_reduction_db = 0.0;
                nerd.protection_interventions = 0;
                nerd.loudness_gain_db = None;
                nerd.loudness_origin = "Bypassed for qualified integer output".into();
                nerd.queued_target_ms = state.queue_target_ms;
                nerd.build_revision = env!("NUVX_CORE_REVISION").into();
                if let Some(v) = state.current.as_ref() {
                    nerd.decoder_implementation = v.decoder.implementation().into();
                    nerd.decoded_layout = v.decoder.layout();
                }
            }
        }
        let frames_len = frame_count as u64;
        state
            .buffered_frames
            .fetch_add(frames_len / 2, Ordering::Release);
        let mut pushed = 0u64;
        for &sample in &voice_scratch[..frame_count] {
            if ring.push(sample).is_ok() {
                pushed += 1;
            } else {
                break;
            }
        }
        if pushed < frames_len {
            let _ = state
                .buffered_frames
                .fetch_update(Ordering::AcqRel, Ordering::Relaxed, |v| {
                    Some(v.saturating_sub((frames_len - pushed) / 2))
                });
        }
        break;
    }
    state.voice_output_scratch = voice_scratch;
}
