

use super::analysis::{fold_tempo, Analysis};
use super::cue::beat_seconds_of;
use super::edges::final_chord_attack;
use super::vocal_overlap::vocal_activity_between;
use super::MAX_BRIDGED_SECONDS;
use crate::mixer::{TransitionPlan, TransitionStyle};


#[derive(Clone, Copy, Debug)]
pub(super) struct BridgeUnit {
    pub(super) start: f64,
    pub(super) seconds: f64,
    pub(super) bars: usize,
}





pub(super) fn vocal_peak_between(analysis: &Analysis, start: f64, end: f64) -> Option<f64> {
    let mask = &analysis.vocal_activity_mask;
    let curve = &analysis.energy_curve;
    if mask.is_empty() || mask.len() != curve.len() || end <= start {
        return None;
    }
    let mut peak = 0.0f64;
    let mut seen = false;
    for (index, value) in mask.iter().enumerate() {
        let time = curve[index].time;
        if !time.is_finite() || time < start || time > end || !value.is_finite() {
            continue;
        }
        peak = peak.max(*value);
        seen = true;
    }
    seen.then_some(peak)
}




pub(super) const VOCAL_FREE_MEAN_MAX: f64 = 0.35;
pub(super) const VOCAL_FREE_PEAK_MAX: f64 = 0.55;







pub(super) fn bar_grid_usable(analysis: &Analysis) -> bool {
    let bars = &analysis.downbeats;
    if bars.len() < 5 {
        return false;
    }
    let mut gaps: Vec<f64> = bars
        .windows(2)
        .map(|w| w[1] - w[0])
        .filter(|g| g.is_finite() && *g > 0.0)
        .collect();
    if gaps.len() < 4 {
        return false;
    }
    gaps.sort_by(|a, b| a.total_cmp(b));
    (0.4..=8.0).contains(&gaps[gaps.len() / 2])
}









pub(super) fn bridge_unit(
    outgoing: &Analysis,
    plan: &TransitionPlan,
) -> Result<BridgeUnit, &'static str> {
    if !(plan.reason.starts_with("crossfade:")
        || plan.reason.starts_with("filter fade:")
        || plan.reason.starts_with("cold ending:"))
    {
        return Err("not a clean crossfade");
    }
    if beat_seconds_of(outgoing) <= 0.0 || !bar_grid_usable(outgoing) {
        return Err("no regular bar grid");
    }
    let start = plan.transition_end_seconds - plan.fade_seconds;
    let chord = final_chord_attack(outgoing);



    let point = if plan.outgoing_hold_seconds > 0.0 {
        chord
            .map(|c| c.min(plan.transition_end_seconds))
            .unwrap_or(plan.transition_end_seconds)
    } else {
        plan.transition_end_seconds
    };
    if point <= start + 0.5 || outgoing.downbeats.len() < 5 {
        return Err("the blend is shorter than one unit");
    }
    let beat = beat_seconds_of(outgoing);
    let fast = fold_tempo(outgoing.bpm) > 140.0;
    let orders: &[usize] = if fast { &[4, 2] } else { &[2] };
    let mut failure: &'static str = "no phrase-aligned unit after the blend start";
    for &bars in orders {
        let ideal = 4.0 * bars as f64 * beat;
        if plan.fade_seconds + ideal > MAX_BRIDGED_SECONDS {
            failure = "the unit would push the fade past 16s";
            continue;
        }

        for j in (0..outgoing.downbeats.len()).rev() {
            let end = outgoing.downbeats[j];
            if !end.is_finite() || end > point + 1e-6 || j < bars {
                continue;
            }
            let i = j - bars;

            if i % 2 != 0 {
                continue;
            }
            let begin = outgoing.downbeats[i];



            if !begin.is_finite() || begin < start - 0.010 {
                continue;
            }
            let mut grid_ok = true;
            for k in 0..=bars {
                let expected = begin + k as f64 * 4.0 * beat;
                if (outgoing.downbeats[i + k] - expected).abs() > 0.015 {
                    grid_ok = false;
                    break;
                }
            }
            if !grid_ok {
                failure = "the unit's downbeats drift more than 15 ms";
                continue;
            }
            if chord.is_some_and(|c| c > begin + 0.05 && c < end - 0.05) {
                failure = "a final chord sits in the unit";
                continue;
            }
            let measured = end - begin;
            if measured <= 0.5 {
                continue;
            }
            if plan.fade_seconds + measured > MAX_BRIDGED_SECONDS {
                failure = "the unit would push the fade past 16s";
                continue;
            }
            let Some(mean) = vocal_activity_between(outgoing, begin, end) else {
                continue;
            };
            let peak = vocal_peak_between(outgoing, begin, end).unwrap_or(1.0);
            if mean > VOCAL_FREE_MEAN_MAX || peak > VOCAL_FREE_PEAK_MAX {
                failure = "the unit has a voice";
                continue;
            }
            return Ok(BridgeUnit {
                start: begin,
                seconds: measured,
                bars,
            });
        }
    }
    Err(failure)
}





pub(super) fn apply_bridge(outgoing: &Analysis, plan: &mut TransitionPlan) {

    if !matches!(
        plan.style,
        TransitionStyle::EqualPower | TransitionStyle::DjFilter
    ) || plan.effect_seconds > 0.0
        || plan.bass_swap
    {
        return;
    }
    match bridge_unit(outgoing, plan) {
        Ok(unit) => {
            plan.transition_end_seconds += unit.seconds;
            plan.fade_seconds += unit.seconds;


            if plan.outgoing_hold_seconds > 0.0 {
                plan.outgoing_hold_seconds += unit.seconds;
            }
            plan.bridge_start_seconds = unit.start;
            plan.bridge_seconds = unit.seconds;
            plan.bridge_repeats = 1;
            plan.reason = format!(
                "{}; bridge: {} bars ({:.1}s) from {:.1}s, replayed once",
                plan.reason, unit.bars, unit.seconds, unit.start
            );
        }
        Err(why) => {
            plan.reason = format!("{}; bridge: none ({why})", plan.reason);
        }
    }
}
