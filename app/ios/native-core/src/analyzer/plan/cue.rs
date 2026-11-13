

use super::analysis::Analysis;
use super::edges::{content_end_of, final_chord_attack};
use super::vocal_overlap::nearest_value;
use super::{
    audio_analysis, MAX_BPM, MAX_CUE_BEATS, MAX_CUE_FRACTION, MAX_CUE_SECONDS, MIN_BPM,
    MIX_IN_END_MARGIN_SECONDS,
};
use crate::mixer::TransitionPlan;


#[derive(Clone, Copy, Debug, Default)]
pub struct TransitionEdges {
    pub outgoing_discarded_seconds: f64,
    pub incoming_discarded_seconds: f64,
    pub cold_ending: bool,
    pub blend_start_seconds: f64,
    pub final_chord_seconds: Option<f64>,
    pub clipped_final_chord: bool,
}

pub fn last_transition_edges() -> TransitionEdges {
    transition_edges::get()
}

pub(super) mod transition_edges {
    use super::TransitionEdges;
    use std::sync::{Mutex, OnceLock};

    fn stored() -> &'static Mutex<TransitionEdges> {
        static EDGES: OnceLock<Mutex<TransitionEdges>> = OnceLock::new();
        EDGES.get_or_init(|| Mutex::new(TransitionEdges::default()))
    }

    pub(super) fn set(edges: TransitionEdges) {
        *stored().lock().unwrap_or_else(|p| p.into_inner()) = edges;
    }

    pub(super) fn get() -> TransitionEdges {
        *stored().lock().unwrap_or_else(|p| p.into_inner())
    }
}

pub(super) fn record_transition_edges(
    outgoing: &Analysis,
    incoming: &Analysis,
    plan: &TransitionPlan,
) {
    let end = content_end_of(outgoing);
    let start = plan.transition_end_seconds - plan.fade_seconds;
    let chord = final_chord_attack(outgoing);




    let ending_handover = (plan.transition_end_seconds - end).abs() <= 1.5;
    transition_edges::set(TransitionEdges {
        outgoing_discarded_seconds: (end - plan.transition_end_seconds).max(0.0),
        incoming_discarded_seconds: (plan.cue_seconds - incoming.audible_start_time.max(0.0))
            .max(0.0),
        cold_ending: chord.is_some(),
        blend_start_seconds: start,
        final_chord_seconds: chord,
        clipped_final_chord: ending_handover
            && chord.is_some_and(|attack| start + plan.outgoing_hold_seconds + 0.15 < attack),
    });
}








pub(super) fn incoming_entry(analysis: &Analysis) -> f64 {
    let duration = analysis.duration;
    let beat_seconds = beat_seconds_of(analysis);
    let grid = beat_seconds > 0.0
        && analysis
            .downbeats
            .iter()
            .any(|d| d.is_finite() && *d >= 0.0);
    if !grid {




        return 0.0;
    }



    let entry = analysis
        .mix_in_candidates
        .iter()
        .filter(|c| c.time.is_finite() && c.time >= 0.0)
        .map(|c| c.time)
        .chain([analysis.audible_start_time].into_iter())
        .fold(f64::INFINITY, f64::min);
    let entry = if entry.is_finite() { entry } else { 0.0 };




    let snapped = analysis
        .downbeats
        .iter()
        .copied()
        .filter(|d| d.is_finite() && *d >= entry - 1e-3 && *d <= entry + beat_seconds)
        .min_by(|a, b| a.total_cmp(b))
        .unwrap_or(entry);

    cap_cue(snapped, entry, beat_seconds, duration)
}


#[cfg(test)]
pub(super) fn incoming_cue(analysis: &Analysis) -> f64 {
    incoming_entry(analysis)
}




pub(super) fn beat_seconds_of(analysis: &Analysis) -> f64 {
    if analysis.bar_beat_seconds.is_finite() && analysis.bar_beat_seconds > 0.0 {
        return analysis.bar_beat_seconds;
    }
    if !(MIN_BPM..=MAX_BPM).contains(&analysis.bpm) {
        return 0.0;
    }
    let from_bpm = 60.0 / analysis.bpm;
    if analysis.beat_interval.is_finite() && analysis.beat_interval > 0.0 {



        if (analysis.beat_interval - from_bpm).abs() / from_bpm < 0.5 {
            return analysis.beat_interval;
        }
    }
    from_bpm
}




pub(super) fn cap_cue(cue: f64, entry: f64, beat_seconds: f64, duration: f64) -> f64 {
    let in_mix_in_window = |t: f64| {
        t.is_finite() && t >= 0.0 && (duration <= 0.0 || t < duration - MIX_IN_END_MARGIN_SECONDS)
    };
    let cue = if in_mix_in_window(cue) { cue } else { 0.0 };
    let mut cap = MAX_CUE_SECONDS.min(MAX_CUE_BEATS * beat_seconds);
    if duration > 0.0 {


        cap = cap.min(duration * MAX_CUE_FRACTION);
    }
    if cue <= cap {
        return cue;
    }


    if entry <= cap && in_mix_in_window(entry) {
        entry
    } else {
        0.0
    }
}




pub(super) fn incoming_handoff(analysis: &Analysis, max_overlap_s: f64) -> f64 {
    let entry = incoming_entry(analysis);
    let budget = max_overlap_s.max(0.0);
    let beat = beat_seconds_of(analysis);
    let mut ranked: Vec<&audio_analysis::MixCuePoint> = analysis
        .mix_in_candidates
        .iter()
        .filter(|c| c.kind != "pickup" && c.time.is_finite() && c.time > entry + 0.25)
        .collect();
    ranked.sort_by(|a, b| b.score.total_cmp(&a.score));
    if let Some(drop) = ranked.first() {
        let snapped = if beat > 0.0 {
            nearest_value(&analysis.downbeats, drop.time, 0.5_f64.max(beat * 2.0))
                .unwrap_or(drop.time)
        } else {
            drop.time
        };
        if snapped >= entry && snapped - entry <= budget + 0.05 {
            return snapped;
        }
    }
    if analysis.mix_in_time.is_finite()
        && analysis.mix_in_time > entry + 0.25
        && analysis.mix_in_time - entry <= budget + 0.05
    {
        return analysis.mix_in_time;
    }
    entry + budget
}



pub(super) fn cue_for(entry: f64, handoff: f64, overlap: f64, rate: f64) -> f64 {
    let rate = if rate.is_finite() && rate > 0.0 {
        rate
    } else {
        1.0
    };
    let raw = if overlap.is_finite() {
        handoff - overlap * rate
    } else {
        entry
    };
    raw.max(entry).max(0.0).min(entry + MAX_CUE_SECONDS)
}

pub(super) fn timed_near_or_before(
    values: &[f64],
    target: f64,
    tolerance: f64,
    minimum: f64,
) -> Option<f64> {
    values
        .iter()
        .copied()
        .filter(|v| v.is_finite() && *v >= minimum && *v <= target && target - *v <= tolerance)
        .max_by(|a, b| a.total_cmp(b))
}

pub(super) fn nearest_timed(
    values: &[f64],
    target: f64,
    tolerance: f64,
    minimum: f64,
) -> Option<f64> {
    values
        .iter()
        .copied()
        .filter(|v| v.is_finite() && *v >= minimum && (*v - target).abs() <= tolerance)
        .min_by(|a, b| (*a - target).abs().total_cmp(&(*b - target).abs()))
}



pub(super) fn aligned_transition_start(
    analysis: &Analysis,
    target: f64,
    end: f64,
    prefer_earlier: bool,
    minimum: f64,
) -> f64 {
    let interval = beat_seconds_of(analysis);
    let phrase_tolerance = 1.0_f64.max(interval * 4.0);
    let downbeat_tolerance = 0.75_f64.max(interval * 2.0);
    let phrase = if prefer_earlier {
        timed_near_or_before(
            &analysis.phrase_boundaries,
            target,
            phrase_tolerance,
            minimum,
        )
    } else {
        nearest_timed(
            &analysis.phrase_boundaries,
            target,
            phrase_tolerance,
            minimum,
        )
    };
    let downbeat = if prefer_earlier {
        timed_near_or_before(&analysis.downbeats, target, downbeat_tolerance, minimum)
    } else {
        nearest_timed(&analysis.downbeats, target, downbeat_tolerance, minimum)
    };
    let chosen = phrase.or(downbeat).unwrap_or(target);
    if end < minimum {
        return chosen.max(0.0).min(end.max(0.0));
    }
    chosen.clamp(minimum, end)
}










pub(super) fn realized_window(
    mix_end: f64,
    snapped_start: f64,
    desired: f64,
    minimum: f64,
) -> (f64, f64) {
    let mix_end = mix_end.max(0.0);
    let snapped = (mix_end - snapped_start).max(0.0);
    let minimum = minimum.min(mix_end);
    let fade = if snapped + 0.25 >= minimum {
        snapped.max(minimum)
    } else {
        desired.max(minimum)
    };
    let fade = fade.min(mix_end);
    ((mix_end - fade).max(0.0), fade)
}
