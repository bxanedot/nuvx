


use super::beat::{head_window_seconds, Grid};
use super::cache::{apply_overlay, overlays};
use super::{audio_analysis, resample, vocal, MIN_DECODED_FRACTION};
use crate::analyzer::beat;

#[derive(Clone, Default, serde::Serialize, serde::Deserialize)]
pub(super) struct Analysis {
    pub(super) bpm: f64,
    pub(super) beat_interval: f64,
    pub(super) beat_confidence: f64,
    pub(super) downbeats: Vec<f64>,
    pub(super) first_beat: f64,
    pub(super) duration: f64,


    pub(super) key: String,
    pub(super) key_confidence: f64,


    pub(super) content_end: f64,
    pub(super) outro_start: f64,
    pub(super) mix_out_time: f64,
    pub(super) mix_in_time: f64,
    pub(super) audible_start_time: f64,
    pub(super) mix_in_candidates: Vec<audio_analysis::MixCuePoint>,
    pub(super) mix_out_candidates: Vec<audio_analysis::MixCuePoint>,

    pub(super) phrase_boundaries: Vec<f64>,
    pub(super) section_boundaries: Vec<f64>,
    pub(super) segment_boundaries: Vec<f64>,
    pub(super) energy_curve: Vec<audio_analysis::EnergyPoint>,
    pub(super) low_energy_curve: Vec<audio_analysis::EnergyPoint>,
    pub(super) pace_curve: Vec<audio_analysis::EnergyPoint>,



    pub(super) vocal_activity_mask: Vec<f64>,
    pub(super) drum_activity_mask: Vec<f64>,
    pub(super) bass_activity_mask: Vec<f64>,

    pub(super) beats: Vec<f64>,


    pub(super) vocal_probability: f64,




    pub(super) bar_beat_seconds: f64,


    pub(super) analysis_source: String,



    #[serde(default)]
    pub(super) beat_backend: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Tier {
    Beatmatched,
    DjAssisted,
    Plain,
}

pub(super) fn analyze(
    path: &str,
    skip_vocals: bool,
    decode: &impl Fn(&str, f64, f64, bool) -> Option<(Vec<f32>, u32, f64)>,
    duration: f64,
) -> Analysis {
    let analysis_started = std::time::Instant::now();
    let mut analysis = Analysis {
        duration,
        ..Analysis::default()
    };
    let head_len = if duration > 0.0 {
        head_window_seconds().min(duration)
    } else {
        head_window_seconds()
    };











    let mut whole: Option<(Vec<f32>, u32)> = None;
    if duration > 0.0 {
        if let Some((mono, rate, _)) = decode(path, 0.0, duration, true) {
            let decoded_seconds = if rate > 0 {
                mono.len() as f64 / rate as f64
            } else {
                0.0
            };
            let duration_shortfall = (duration - decoded_seconds).max(0.0);
            let tolerated_shortfall = (duration * 0.005).max(0.3);
            if decoded_seconds < duration * MIN_DECODED_FRACTION
                || duration_shortfall > tolerated_shortfall
            {









                log::warn!(
                    "whole-track analysis refused for {path}: decoded \
                     {decoded_seconds:.1}s of a {duration:.1}s container"
                );
            } else {
                let mono_analysis = resample(&mono, rate as f64, audio_analysis::ANALYSIS_RATE);
                let audio = audio_analysis::analyze_audio(
                    &mono_analysis,
                    audio_analysis::ANALYSIS_RATE,
                    duration,
                );
                analysis.bpm = audio.bpm;
                analysis.beat_interval = audio.beat_interval;
                analysis.beat_confidence = audio.beat_confidence;
                analysis.downbeats = audio.downbeats;
                analysis.first_beat = audio.first_beat;
                analysis.key = audio.key;
                analysis.key_confidence = audio.key_confidence;
                analysis.content_end = audio.content_end_time;
                analysis.outro_start = audio.outro_start_time;
                analysis.mix_out_time = audio.mix_out_time;
                analysis.mix_in_time = audio.mix_in_time;
                analysis.audible_start_time = audio.audible_start_time;
                analysis.mix_in_candidates = audio.mix_in_candidates;
                analysis.mix_out_candidates = audio.mix_out_candidates;
                analysis.phrase_boundaries = audio.phrase_boundaries;
                analysis.section_boundaries = audio.section_boundaries;
                analysis.segment_boundaries = audio.segment_boundaries;
                analysis.energy_curve = audio.energy_curve;
                analysis.low_energy_curve = audio.low_energy_curve;
                analysis.pace_curve = audio.pace_curve;
                analysis.vocal_activity_mask = audio.vocal_activity_mask;
                analysis.drum_activity_mask = audio.drum_activity_mask;
                analysis.bass_activity_mask = audio.bass_activity_mask;
                analysis.beats = audio.beats;
                analysis.vocal_probability = audio.vocal_probability;
                analysis.analysis_source = "dsp".into();



                whole = Some((mono, rate));
            }
        }
    }

























    let overlay_has_grid = overlays::get(path).is_some_and(|o| {
        o.beats.as_ref().is_some_and(|b| b.len() >= 2)
            && o.downbeats.as_ref().is_some_and(|d| !d.is_empty())
    });
    let model_grid = if overlay_has_grid {
        log::info!("automix analysis {path}: beat model skipped — external rhythm grid seeded");
        None
    } else if let Some((mono, rate)) = whole.as_ref() {
        beat::track(mono, *rate as f64, 0.0)
    } else if let Some((mono, rate, offset)) = decode(path, 0.0, head_len, true) {
        beat::track(&mono, rate as f64, offset)
    } else {
        None
    };
    match model_grid {
        None if overlay_has_grid => {}
        Some(grid) => {
            let model_bars = grid.downbeats.len();
            let model_bpm = grid.bpm;
            let (grid, bar_beat, bar_source) = reconcile_grid(&analysis, grid);
            log::info!(
                "automix analysis {path}: bar grid from {} — model {:.1} bpm/{} bars, DSP {:.1} bpm/{} \
                 bars; final {:.1} bpm, {} bars, model confidence {:.2}, beats {} span {:.1}..{:.1} of {:.1}s",
                bar_source,
                model_bpm,
                model_bars,
                analysis.bpm,
                analysis.downbeats.len(),
                grid.bpm,
                grid.downbeats.len(),
                grid.beat_confidence,
                grid.beats.len(),
                grid.beats.first().copied().unwrap_or(0.0),
                grid.beats.last().copied().unwrap_or(0.0),
                duration,
            );
            fill_grid(&mut analysis, grid);
            analysis.bar_beat_seconds = bar_beat;
        }
        None => {
            log::info!(
                "automix analysis {path}: no beat-model grid — DSP tempo only \
                 ({:.1} bpm, confidence {:.2}). Transitions will be coarser; \
                 install the Automix beat model in Settings.",
                analysis.bpm,
                analysis.beat_confidence,
            );
        }
    }



    drop(whole);





    if !skip_vocals {
        let vocal_window = vocal::MAX_WINDOW_SECONDS;



        ingest_vocals(&mut analysis, path, 0.0, head_len.min(vocal_window), decode);





        let content_end = if analysis.content_end > 0.0 {
            analysis.content_end
        } else {
            duration
        };
        if content_end > head_len.min(vocal_window) + 2.0 {
            let tail_start = (content_end - vocal_window).max(0.0);
            let tail = (content_end - tail_start).min(vocal_window);
            if tail > 2.0 {
                ingest_vocals(&mut analysis, path, tail_start, tail, decode);
            }
        }
    }
    if let Some(overlay) = overlays::get(path) {
        apply_overlay(&mut analysis, &overlay);
    }





    let counted = fold_tempo(analysis.bpm);
    if (counted - analysis.bpm).abs() > 0.5 {
        log::info!(
            "automix analysis {path}: tempo {:.1} BPM counted as {counted:.1} \
             (octave fold for planning)",
            analysis.bpm
        );
        analysis.bpm = counted;
        analysis.beat_interval = 60.0 / counted;
    }



    log::info!(
        "automix analysis time {path}: {} ms",
        analysis_started.elapsed().as_millis()
    );
    analysis
}









pub(super) fn fold_tempo(bpm: f64) -> f64 {
    if !bpm.is_finite() || bpm <= 0.0 {
        return bpm;
    }
    let mut folded = bpm;
    while folded >= 175.0 {
        folded /= 2.0;
    }
    while folded < 70.0 {
        folded *= 2.0;
    }
    folded
}






pub(super) fn merge_open_unmix_window(analysis: &mut Analysis, mask: &[f32], offset: f64) {
    if mask.is_empty() || analysis.energy_curve.is_empty() {
        return;
    }
    let hop = vocal::HOP as f64 / vocal::SAMPLE_RATE;
    let last = offset + (mask.len() as f64 - 1.0) * hop;
    let mut cursor = 0usize;
    for (index, point) in analysis.energy_curve.iter().enumerate() {
        if point.time < offset - 0.5 || point.time > last + 0.5 {
            continue;
        }
        if index >= analysis.vocal_activity_mask.len() {
            break;
        }
        while cursor + 1 < mask.len()
            && (offset + (cursor as f64 + 1.0) * hop - point.time).abs()
                < (offset + cursor as f64 * hop - point.time).abs()
        {
            cursor += 1;
        }
        if cursor < mask.len() {
            let probability =
                (mask[cursor] as f64 * if point.energy > 0.25 { 1.0 } else { 0.3 }).clamp(0.0, 1.0);
            analysis.vocal_activity_mask[index] = probability;
        }
    }
}

pub(super) fn ingest_vocals(
    analysis: &mut Analysis,
    path: &str,
    start: f64,
    duration: f64,
    decode: &impl Fn(&str, f64, f64, bool) -> Option<(Vec<f32>, u32, f64)>,
) {
    let Some((mut stereo, rate, offset)) = decode(path, start, duration, false) else {
        return;
    };




    let wanted = ((duration.max(0.0) * rate as f64) as usize).saturating_mul(2);
    if stereo.len() > wanted {
        stereo.truncate(wanted);
    }
    let (left, right) = split_stereo(&stereo);
    let Some(mask) = vocal::track(&left, &right, rate as f64) else {
        return;
    };
    merge_open_unmix_window(analysis, &mask, offset);
}












pub(super) fn reconcile_grid(analysis: &Analysis, model: Grid) -> (Grid, f64, &'static str) {
    let dsp_bars = &analysis.downbeats;
    let usable_dsp = dsp_bars.len() >= 4
        && analysis.beat_interval > 0.0
        && dsp_bars.windows(2).all(|w| w[1] > w[0]);
    let model_bars = model.downbeats.len();
    let same_octave = analysis.bpm > 0.0 && (model.bpm / analysis.bpm).log2().fract().abs() < 0.15;


    let bar_ratio = model_bars as f64 / dsp_bars.len() as f64;
    let model_complete = usable_dsp && (0.75..=1.25).contains(&bar_ratio) && same_octave;
    if !usable_dsp || model_complete {
        let source = if usable_dsp {
            "model (complete)"
        } else {
            "model (no PLL grid)"
        };
        return (model, 0.0, source);
    }



    let beat = analysis.beat_interval.max(1e-3);
    let matches = |shift: f64| -> usize {
        model
            .downbeats
            .iter()
            .filter(|d| dsp_bars.iter().any(|bar| (bar + shift - *d).abs() <= 0.04))
            .count()
    };
    let mut best_shift = 0.0;
    let mut best = matches(0.0);
    for step in 1..4 {
        let shift = step as f64 * beat;
        let count = matches(shift);
        if count > best {
            best = count;
            best_shift = shift;
        }
    }
    let phase_ok = !model.downbeats.is_empty() && best * 2 >= model.downbeats.len();
    if !phase_ok {
        best_shift = 0.0;
    }
    let mut downbeats: Vec<f64> = dsp_bars
        .iter()
        .map(|bar| bar + best_shift)
        .filter(|bar| bar.is_finite() && *bar >= 0.0)
        .collect();
    downbeats.sort_by(|a, b| a.total_cmp(b));
    if downbeats.len() < 4 {
        return (model, 0.0, "model (PLL too short)");
    }


    let mut gaps: Vec<f64> = downbeats
        .windows(2)
        .map(|w| w[1] - w[0])
        .filter(|g| g.is_finite() && *g > 0.0)
        .collect();
    gaps.sort_by(|a, b| a.total_cmp(b));
    let bar_seconds = gaps.get(gaps.len() / 2).copied().unwrap_or(0.0);
    if bar_seconds <= 0.4 {
        return (model, 0.0, "model (PLL bars unusable)");
    }



    let mut grid = model;
    grid.downbeats = downbeats;
    let source = if best_shift.abs() < 1e-6 {
        "PLL (phase kept)"
    } else {
        "PLL (phase shifted to the model)"
    };
    (grid, bar_seconds / 4.0, source)
}

pub(super) fn fill_grid(analysis: &mut Analysis, grid: Grid) {
    analysis.bpm = grid.bpm;
    analysis.beat_interval = grid.beat_interval;
    analysis.beat_confidence = grid.beat_confidence;
    analysis.downbeats = grid.downbeats;
    analysis.first_beat = grid.first_beat;
    analysis.beats = grid.beats;
    analysis.beat_backend = grid.backend.as_str().to_string();


    analysis.phrase_boundaries = analysis.downbeats.iter().step_by(8).copied().collect();
    if analysis.analysis_source.is_empty() || analysis.analysis_source == "dsp" {
        analysis.analysis_source = "beat_this".into();
    }
}

pub(super) fn split_stereo(interleaved: &[f32]) -> (Vec<f32>, Vec<f32>) {
    let mut left = Vec::with_capacity(interleaved.len() / 2);
    let mut right = Vec::with_capacity(interleaved.len() / 2);
    for pair in interleaved.chunks_exact(2) {
        left.push(pair[0]);
        right.push(pair[1]);
    }
    (left, right)
}



pub(super) fn average_low_energy(
    curve: &[audio_analysis::EnergyPoint],
    from: f64,
    until: f64,
) -> Option<f64> {
    if until <= from {
        return None;
    }
    let mut index = curve.partition_point(|p| p.time < from);
    let mut sum = 0.0;
    let mut count = 0usize;
    while index < curve.len() && curve[index].time < until {
        let point = &curve[index];
        if point.time.is_finite() && point.energy.is_finite() && point.energy >= 0.0 {
            sum += point.energy;
            count += 1;
        }
        index += 1;
    }
    if count > 0 {
        Some(sum / count as f64)
    } else {
        None
    }
}

pub(super) fn low_energy_reference(curve: &[audio_analysis::EnergyPoint]) -> Option<f64> {
    let mut energies: Vec<f64> = curve
        .iter()
        .map(|p| p.energy)
        .filter(|e| e.is_finite() && *e >= 0.0)
        .collect();
    if energies.is_empty() {
        return None;
    }
    energies.sort_by(|a, b| a.total_cmp(b));
    let upper_decile = energies[((energies.len() - 1) as f64 * 0.9) as usize];
    let reference = upper_decile.max(energies.last().copied().unwrap_or(0.0) * 0.25);
    if reference > 1e-9 {
        Some(reference)
    } else {
        None
    }
}

pub(super) fn low_energy_resolution(curve: &[audio_analysis::EnergyPoint]) -> f64 {
    let mut gaps: Vec<f64> = curve
        .windows(2)
        .map(|w| w[1].time - w[0].time)
        .filter(|g| g.is_finite() && *g > 0.0)
        .collect();
    gaps.sort_by(|a, b| a.total_cmp(b));
    gaps.get(gaps.len() / 2).copied().unwrap_or(0.0)
}

pub(super) fn low_energy_change(
    curve: &[audio_analysis::EnergyPoint],
    reference: Option<f64>,
    at: f64,
    window_seconds: f64,
) -> Option<f64> {
    if curve.is_empty() || window_seconds <= 0.0 {
        return None;
    }
    let reference = reference?;
    let before = average_low_energy(curve, at - window_seconds, at)?;
    let after = average_low_energy(curve, at, at + window_seconds)?;
    Some((after / reference).clamp(0.0, 1.5) - (before / reference).clamp(0.0, 1.5))
}
