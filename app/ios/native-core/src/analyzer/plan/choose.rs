


use super::analysis::{Analysis, Tier};
use super::cue::{
    aligned_transition_start, cue_for, incoming_entry, incoming_handoff, realized_window,
};
use super::edges::{
    align_tempo_octave, assess, normalized_tempo_ratio, section_fade_cap, transparent_playback_rate,
};
use super::key::{key_distance, keys_clash, trusted_key};
use super::mix_out::{bass_swap_fraction_for, resolve_mix_out_anchor};
use super::technique::{
    clean_crossfade, handover_is_busy, last_strong_downbeat, long_blend_allowed, technique_plan,
};
use super::vocal_overlap::{handover_vocal_clash, planned_vocal_overlap};
use super::{
    plain_fallback, ARRANGEMENT_OVERLAP_BEATS, AUTO_FAST_TRACK_MIN_SECONDS, AUTO_MIN_SECONDS,
    AUTO_TRANSITION_MAX_BEATS, AUTO_TRANSITION_MAX_SECONDS, BED_GAIN_DB,
    DEFAULT_BASS_SWAP_FRACTION, FILTER_SWEEP, LONG_BLEND_BEATS, LONG_BLEND_SECONDS,
    MAX_BEATMATCH_STRETCH, MAX_DISCARDED_MUSIC_SECONDS, MIN_CLEARANCE_SECONDS,
    MIN_SMART_DURATION_SECONDS, POST_BLEND_GLIDE_BEATS,
};
use crate::mixer::{TransitionPlan, TransitionStyle, MAX_DUCK_DEPTH};

#[cfg(test)]
pub(super) fn plan_from(
    outgoing: &Analysis,
    incoming: &Analysis,
    fade: f64,
    allow_effects: bool,
) -> TransitionPlan {
    plan_from_with_phrase_bars(outgoing, incoming, fade, allow_effects, 0)
}

pub(super) fn plan_from_with_phrase_bars(
    outgoing: &Analysis,
    incoming: &Analysis,
    fade: f64,
    allow_effects: bool,
    phrase_bars: u32,
) -> TransitionPlan {
    let entry = incoming_entry(incoming);


    let too_short = (outgoing.duration > 0.0 && outgoing.duration < MIN_SMART_DURATION_SECONDS)
        || (incoming.duration > 0.0 && incoming.duration < MIN_SMART_DURATION_SECONDS);
    let tier = assess(outgoing, incoming);



    if too_short {
        let mut plan = plain_fallback(fade, outgoing);
        plan.cue_seconds = entry;
        plan.reason = "fade: a track is too short for a technique".into();
        return plan;
    }
    if matches!(tier, Tier::Plain) {
        if allow_effects {
            if let Some(plan) = technique_plan(
                outgoing,
                incoming,
                tier,
                entry,
                None,
                None,
                phrase_bars,
            ) {
                return plan;
            }
        }
        let mut plan = plain_fallback(fade, outgoing);
        plan.cue_seconds = entry;
        plan.reason = "fade: no usable tempo on either side".into();
        return plan;
    }

    let out_bpm = outgoing.bpm;
    let in_bpm = incoming.bpm;
    let ratio = normalized_tempo_ratio(out_bpm, in_bpm);
    let same_beat = (1.0 - ratio).abs() <= 0.05
        && (outgoing.beat_confidence >= 0.2 || incoming.beat_confidence >= 0.2);




    let playback_rate = if matches!(tier, Tier::Beatmatched) {
        transparent_playback_rate(out_bpm, in_bpm, MAX_BEATMATCH_STRETCH).unwrap_or(1.0)
    } else {
        1.0
    };





    let (anchor, _) = resolve_mix_out_anchor(outgoing);
    let vocal_conflict = handover_vocal_clash(outgoing, incoming, anchor, entry);


    let long = long_blend_allowed(outgoing, incoming) && !vocal_conflict;
    let key_distance = key_distance(&trusted_key(outgoing), &trusted_key(incoming));
    let overlap_beats = if long {
        LONG_BLEND_BEATS as f64
    } else if !vocal_conflict && ((1.0 - ratio).abs() > 0.07 || key_distance.is_some_and(|d| d > 4))
    {
        16.0
    } else {
        8.0
    };
    let beat_seconds = if out_bpm > 0.0 { 60.0 / out_bpm } else { 0.5 };
    let min_overlap = if out_bpm >= 140.0 {
        AUTO_FAST_TRACK_MIN_SECONDS
    } else {
        AUTO_MIN_SECONDS
    };
    let max_seconds = if long {
        LONG_BLEND_SECONDS
    } else {
        AUTO_TRANSITION_MAX_SECONDS
    };
    let max_beats_cap = if long {
        LONG_BLEND_BEATS as f64
    } else {
        AUTO_TRANSITION_MAX_BEATS
    };
    let overlap = (overlap_beats * beat_seconds).clamp(min_overlap, max_seconds);




    let content_end = if outgoing.content_end > 0.0 {
        outgoing.content_end
    } else {
        outgoing.duration
    };
    let arrangement = if same_beat && (anchor - content_end).abs() < 0.05 && out_bpm > 0.0 {
        (ARRANGEMENT_OVERLAP_BEATS * 60.0 / out_bpm).min(MAX_DISCARDED_MUSIC_SECONDS)
    } else {
        0.0
    };
    let mix_end = (anchor - arrangement).max(0.0);
    let mut max_overlap = (max_beats_cap * beat_seconds)
        .min(max_seconds)
        .min((mix_end * 0.4).max(min_overlap))
        .min(if incoming.duration > 0.0 {
            (incoming.duration * 0.4).max(min_overlap)
        } else {
            max_seconds
        })
        .max(min_overlap);



    if let Some(cap) = section_fade_cap(outgoing, incoming) {
        max_overlap = max_overlap.min(cap.max(min_overlap));
    }

    let handoff_beats = if same_beat { 8.0 } else { 4.0 };
    let handoff_seconds = if out_bpm > 0.0 {
        (handoff_beats * 60.0 / out_bpm).clamp(2.0, if same_beat { 6.0 } else { 5.0 })
    } else {
        4.0
    };



    let drop = incoming_handoff(incoming, max_overlap);
    let aligned_in = align_tempo_octave(out_bpm, in_bpm);
    let requested_handoff = if same_beat && aligned_in > 0.0 {
        drop + ARRANGEMENT_OVERLAP_BEATS * 60.0 / aligned_in
    } else {
        drop
    };
    let max_in_handoff = incoming.duration - MIN_CLEARANCE_SECONDS;
    let handoff = if max_in_handoff >= drop {
        requested_handoff.min(max_in_handoff)
    } else {
        drop
    };




    let beatmatched_blend = matches!(tier, Tier::Beatmatched)
        && same_beat
        && (playback_rate - 1.0).abs() <= MAX_BEATMATCH_STRETCH + 1e-6;

    let (transition_start, fade_seconds) = if beatmatched_blend && beat_seconds > 0.0 {
        let intro_span = handoff / playback_rate.max(0.8);
        let total = intro_span.clamp(12.0_f64.min(max_overlap), max_overlap);
        let target = (mix_end - total).max(0.0);
        let earliest = (mix_end - max_overlap).max(0.0);
        let start = aligned_transition_start(
            outgoing,
            target,
            (mix_end - 0.05).max(earliest),
            true,
            earliest,
        );
        realized_window(mix_end, start, total, min_overlap)
    } else {
        let desired = overlap.max(handoff_seconds * 0.42);
        let actual = desired.clamp(handoff_seconds.min(max_overlap), max_overlap);
        let target = (mix_end - actual).max(0.0);
        let earliest = (mix_end - max_overlap).max(0.0);
        let start = aligned_transition_start(
            outgoing,
            target,
            (mix_end - 0.05).max(earliest),
            desired > overlap + 0.5,
            earliest,
        );
        realized_window(mix_end, start, actual, min_overlap)
    };
    let cue = cue_for(entry, handoff, fade_seconds, playback_rate);

    let incoming_beat_seconds = if in_bpm > 0.0 { 60.0 / in_bpm } else { 0.5 };
    let bass_swap_fraction = if beatmatched_blend {
        bass_swap_fraction_for(
            outgoing,
            incoming,
            transition_start,
            cue,
            beat_seconds,
            incoming_beat_seconds,
            fade_seconds,
            overlap_beats as usize,
        )
    } else {
        DEFAULT_BASS_SWAP_FRACTION
    };
    let vocal_overlap = planned_vocal_overlap(
        outgoing,
        incoming,
        transition_start,
        transition_start + fade_seconds,
        cue,
        playback_rate,
    );
    let style = if beatmatched_blend {
        TransitionStyle::DjBlend
    } else {
        TransitionStyle::DjFilter
    };
    let shape = mix_shape(
        style,
        beatmatched_blend,
        vocal_overlap,
        vocal_conflict,
        fade_seconds,
        beat_seconds,
        bass_swap_fraction,
        playback_rate,
        out_bpm,
    );






    let tempo_gap = (1.0 - ratio).abs();



    let key_clash = keys_clash(
        &outgoing.key,
        outgoing.key_confidence,
        &incoming.key,
        incoming.key_confidence,
    );






    let out_busy = handover_is_busy(outgoing, mix_end);
    let in_busy = handover_is_busy(incoming, cue);
    log::info!(
        "automix clash check [{:?}]: key_clash={key_clash} tempo_gap={tempo_gap:.3} \
         vocal_conflict={vocal_conflict} vocal_overlap={vocal_overlap:.3} \
         out_busy={out_busy} in_busy={in_busy}",
        tier
    );
    let effect_wanted = effect_earned(key_clash, tempo_gap);
    if effect_wanted && allow_effects {
        if let Some(plan) = technique_plan(
            outgoing,
            incoming,
            tier,
            entry,
            Some((transition_start, fade_seconds, cue)),
            if out_busy {
                None
            } else {
                last_strong_downbeat(outgoing, mix_end)
            },
            phrase_bars,
        ) {
            return plan;
        }
    }
    if !key_clash && tempo_gap > 0.07 && falls_away(out_busy, in_busy) {




        if let Some(plan) = clean_crossfade(outgoing, mix_end, entry) {
            return plan;
        }
    }

    TransitionPlan {


        style,
        bass_swap: true,
        bass_swap_fraction,
        filter_sweep: if same_beat { 0.0 } else { FILTER_SWEEP },
        vocal_overlap,
        fade_seconds,
        outgoing_hold_seconds: 0.0,
        bridge_start_seconds: 0.0,
        bridge_seconds: 0.0,
        bridge_repeats: 0,
        effect_seconds: 0.0,
        effect_start_seconds: 0.0,
        transition_end_seconds: transition_start + fade_seconds,
        cue_seconds: cue,
        playback_rate,
        bed_fraction: shape.bed_fraction,
        bed_gain_db: shape.bed_gain_db,
        dip_depth: shape.dip_depth,
        dip_width: shape.dip_width,
        post_glide_seconds: shape.post_glide_seconds,
        outgoing_duration_seconds: outgoing.duration,
        beat_seconds: 0.0,
        reason: if effect_wanted && !allow_effects {


            "pacing: effect budget spent, so the pair is blended".to_string()
        } else if vocal_conflict {
            format!(
                "blend: both records sing through the handover; mids ducked {:.0}% for {:.0}% of it",
                shape.dip_depth * 100.0,
                vocal_overlap * 100.0
            )
        } else {
            String::new()
        },
    }
}

pub(super) struct MixShape {
    pub(super) bed_fraction: f64,
    pub(super) bed_gain_db: f64,
    pub(super) dip_depth: f64,
    pub(super) dip_width: f64,
    pub(super) post_glide_seconds: f64,
}



pub(super) fn mix_shape(
    style: TransitionStyle,
    same_beat: bool,
    vocal_overlap: f64,
    vocal_conflict: bool,
    fade_seconds: f64,
    beat_seconds: f64,
    swap_fraction: f64,
    playback_rate: f64,
    bpm: f64,
) -> MixShape {
    if !matches!(style, TransitionStyle::DjBlend | TransitionStyle::DjFilter) {
        return MixShape {
            bed_fraction: 0.0,
            bed_gain_db: 0.0,
            dip_depth: 0.0,
            dip_width: 0.0,
            post_glide_seconds: 0.0,
        };
    }
    let clash = vocal_overlap
        .clamp(0.0, 1.0)
        .max(if vocal_conflict { 0.7 } else { 0.0 });
    let dip = if same_beat {
        (0.28 + 0.5 * clash).clamp(0.0, MAX_DUCK_DEPTH)
    } else {
        (0.2 * clash).clamp(0.0, MAX_DUCK_DEPTH)
    };
    let rise = if fade_seconds > 0.0 && beat_seconds > 0.0 {
        (2.0 * beat_seconds / fade_seconds).clamp(0.08, 0.3)
    } else {
        0.15
    };
    let bed = (swap_fraction - rise).clamp(0.15, 0.8);
    let dip_width = if fade_seconds > 0.0 && beat_seconds > 0.0 {
        (beat_seconds / fade_seconds).clamp(0.02, 0.2)
    } else {
        0.06
    };
    let post_glide_seconds = if same_beat && (playback_rate - 1.0).abs() > 1e-3 && bpm > 0.0 {
        POST_BLEND_GLIDE_BEATS * 60.0 / bpm
    } else {
        0.0
    };
    MixShape {
        bed_fraction: bed,
        bed_gain_db: BED_GAIN_DB,
        dip_depth: dip,
        dip_width,
        post_glide_seconds,
    }
}











pub(super) fn energy_between(analysis: &Analysis, start: f64, end: f64) -> Option<f64> {
    let values: Vec<f64> = analysis
        .energy_curve
        .iter()
        .filter(|p| p.time.is_finite() && p.time >= start && p.time <= end && p.energy.is_finite())
        .map(|p| p.energy)
        .collect();
    (!values.is_empty()).then(|| values.iter().sum::<f64>() / values.len() as f64)
}



pub(super) fn pair_salt(outgoing: &Analysis, incoming: &Analysis) -> usize {
    let a = (outgoing.duration * 1000.0) as u64;
    let b = (incoming.duration * 1000.0) as u64;
    let c = (outgoing.bpm * 100.0) as u64 ^ ((incoming.bpm * 100.0) as u64).rotate_left(17);
    (a.wrapping_mul(0x9E37_79B9_7F4A_7C15) ^ b.rotate_left(29) ^ c) as usize
}












pub(super) fn effect_earned(key_clash: bool, tempo_gap: f64) -> bool {
    key_clash || tempo_gap > 0.07
}









pub(super) fn falls_away(out_busy: bool, in_busy: bool) -> bool {
    !(out_busy && in_busy)
}
