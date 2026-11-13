


use super::analysis::{Analysis, Tier};
use super::bridge::{vocal_peak_between, VOCAL_FREE_MEAN_MAX, VOCAL_FREE_PEAK_MAX};
use super::choose::{energy_between, pair_salt};
use super::cue::{beat_seconds_of, incoming_handoff};
use super::edges::{assess, final_chord_attack, normalized_tempo_ratio, tempo_summary};
use super::key::{key_distance, trusted_key};
use super::mix_out::resolve_mix_out_anchor;
use super::vocal_overlap::vocal_activity_between;
use super::{
    AUTO_FALLBACK_SECONDS, AUTO_MIN_SECONDS, AUTO_TRANSITION_MAX_SECONDS,
    DEFAULT_BASS_SWAP_FRACTION, FILTER_SWEEP, MAX_BPM, MAX_STRETCH_DEVIATION, MIN_BPM,
    MIN_DJ_CONFIDENCE,
};
use crate::mixer::{TransitionPlan, TransitionStyle};





#[cfg(not(test))]
pub(super) static RECENT_TECHNIQUES: std::sync::Mutex<Vec<(usize, TransitionStyle)>> =
    std::sync::Mutex::new(Vec::new());
#[cfg(test)]
thread_local! {
    static RECENT_TECHNIQUES: std::cell::RefCell<Vec<(usize, TransitionStyle)>> =
        const { std::cell::RefCell::new(Vec::new()) };
}

pub(super) fn with_recent_techniques<R>(
    body: impl FnOnce(&mut Vec<(usize, TransitionStyle)>) -> R,
) -> R {
    #[cfg(not(test))]
    {
        let mut recent = RECENT_TECHNIQUES
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        body(&mut recent)
    }
    #[cfg(test)]
    {
        RECENT_TECHNIQUES.with(|recent| body(&mut recent.borrow_mut()))
    }
}

pub(super) fn recent_techniques(salt: usize) -> Vec<TransitionStyle> {
    with_recent_techniques(|recent| {
        recent
            .iter()
            .rev()
            .filter(|(key, _)| *key != salt)
            .map(|(_, style)| *style)
            .collect()
    })
}

pub(super) fn note_technique(salt: usize, style: TransitionStyle) {
    with_recent_techniques(|recent| {
        recent.retain(|(key, _)| *key != salt);
        recent.push((salt, style));
        if recent.len() > 4 {
            recent.remove(0);
        }
    });
}


pub(super) const MAX_THROW_DISCARD_SECONDS: f64 = 10.0;






pub(super) const EFFECT_INTRO_BEATS: f64 = 8.0;
pub(super) const EFFECT_INTRO_MIN_SECONDS: f64 = 4.0;



pub(super) const EFFECT_INTRO_DB: f64 = -4.0;

pub(super) const EFFECT_INTRO_KEY_CLASH_DB: f64 = -9.0;

pub(super) const DROP_CUT_INTRO_DB: f64 = -12.0;





pub(super) fn last_strong_downbeat(outgoing: &Analysis, mix_end: f64) -> Option<f64> {
    let reference = loud_reference(outgoing)?;
    let strong = outgoing
        .energy_curve
        .iter()
        .filter(|point| point.time.is_finite() && point.time <= mix_end)
        .filter(|point| point.energy >= reference * 0.707)
        .map(|point| point.time)
        .next_back()?;
    let bar = 4.0 * beat_seconds_of(outgoing);
    outgoing
        .downbeats
        .iter()
        .copied()
        .filter(|time| time.is_finite() && *time <= strong + 1e-6 && strong - *time <= bar + 1e-6)
        .next_back()
        .or(Some(strong))
}


pub(super) fn handover_is_busy(analysis: &Analysis, time: f64) -> bool {
    let Some(reference) = loud_reference(analysis) else {
        return true;
    };
    let window = 2.0;
    let energy = energy_between(analysis, time - window, time + window)
        .or_else(|| energy_between(analysis, time, time + window));
    match energy {

        Some(value) => value >= reference * 0.707,
        None => true,
    }
}


pub(super) fn loud_reference(analysis: &Analysis) -> Option<f64> {
    let mut values: Vec<f64> = analysis
        .energy_curve
        .iter()
        .map(|point| point.energy)
        .filter(|energy| energy.is_finite() && *energy > 0.0)
        .collect();
    if values.is_empty() {
        return None;
    }
    values.sort_by(|a, b| a.total_cmp(b));
    values.get((values.len() - 1) * 3 / 4).copied()
}




pub(super) fn clean_crossfade(
    outgoing: &Analysis,
    mix_end: f64,
    incoming_cue: f64,
) -> Option<TransitionPlan> {
    if mix_end <= 0.0 {
        return None;
    }
    let reference = loud_reference(outgoing)?;
    let fall_start = outgoing
        .energy_curve
        .iter()
        .filter(|point| point.time.is_finite() && point.time <= mix_end)
        .filter(|point| point.energy >= reference * 0.707)
        .map(|point| point.time)
        .next_back()
        .unwrap_or((mix_end - AUTO_FALLBACK_SECONDS).max(0.0));



    let beat = beat_seconds_of(outgoing);
    let phrase = 8.0 * beat;
    let fall = (mix_end - fall_start)
        .max(phrase)
        .clamp(2.0, AUTO_TRANSITION_MAX_SECONDS);




    let bar = 4.0 * beat;
    let start = mix_end - fall;



    let min_keep = phrase.min(AUTO_TRANSITION_MAX_SECONDS);
    let snapped_start = if bar > 0.1 {
        let mut candidates: Vec<(f64, bool, f64)> = Vec::new();
        for (index, bar_time) in outgoing.downbeats.iter().enumerate() {
            if !bar_time.is_finite() {
                continue;
            }
            let distance = (bar_time - start).abs();
            if distance > bar + 1e-6 {
                continue;
            }
            let fall_after = mix_end - *bar_time;
            if fall_after + 1e-6 < min_keep || fall_after > AUTO_TRANSITION_MAX_SECONDS {
                continue;
            }
            candidates.push((*bar_time, index % 2 == 0, distance));
        }


        candidates.sort_by(|a, b| {
            b.1.cmp(&a.1)
                .then(a.2.total_cmp(&b.2))
                .then(a.0.total_cmp(&b.0))
        });
        candidates.first().map(|(bar_time, _, _)| *bar_time)
    } else {
        None
    };
    let (legacy_fall, legacy_start, snapped) = match snapped_start {
        Some(bar_time) => (mix_end - bar_time, bar_time, true),
        None => (fall, start, false),
    };
    let target = (16.0 * beat).min(AUTO_TRANSITION_MAX_SECONDS);
    let floor = 8.0 * beat;
    let outro_phrase = if beat > 0.0 && target >= floor {
        outgoing
            .downbeats
            .iter()
            .enumerate()
            .filter(|(index, time)| *index % 2 == 0 && time.is_finite() && **time <= fall_start)
            .filter_map(|(_, time)| {
                let candidate_fall = mix_end - *time;
                if candidate_fall + 1e-6 < floor
                    || candidate_fall > target + 1e-6
                    || candidate_fall <= legacy_fall + 1e-6
                {
                    return None;
                }
                let mean = vocal_activity_between(outgoing, *time, mix_end)?;
                let peak = vocal_peak_between(outgoing, *time, mix_end)?;
                (mean <= VOCAL_FREE_MEAN_MAX && peak <= VOCAL_FREE_PEAK_MAX)
                    .then_some((*time, candidate_fall))
            })
            .max_by(|left, right| left.1.total_cmp(&right.1))
    } else {
        None
    };
    let (fall, start, extended, snapped) = match outro_phrase {
        Some((phrase_start, phrase_fall)) => (phrase_fall, phrase_start, true, true),
        None => (legacy_fall, legacy_start, false, snapped),
    };
    Some(TransitionPlan {





        style: TransitionStyle::DjFilter,
        bass_swap: false,
        bass_swap_fraction: DEFAULT_BASS_SWAP_FRACTION,
        filter_sweep: FILTER_SWEEP,
        vocal_overlap: 0.0,
        fade_seconds: fall,
        outgoing_hold_seconds: 0.0,
        bridge_start_seconds: 0.0,
        bridge_seconds: 0.0,
        bridge_repeats: 0,
        effect_seconds: 0.0,
        effect_start_seconds: 0.0,
        transition_end_seconds: mix_end,
        cue_seconds: incoming_cue,
        playback_rate: 1.0,
        bed_fraction: 0.0,
        bed_gain_db: 0.0,
        dip_depth: 0.0,
        dip_width: 0.0,
        post_glide_seconds: 0.0,
        outgoing_duration_seconds: outgoing.duration,
        beat_seconds: 0.0,
        reason: if extended {
            format!(
                "filter fade: outro phrase; the outgoing falls away from {fall_start:.1}s and the \
                 {fall:.1}s blend starts at {start:.1}s (bar-aligned)"
            )
        } else {
            format!(
                "filter fade: no shared tempo; the outgoing falls away from {fall_start:.1}s and the \
                 {fall:.1}s blend starts at {start:.1}s{}",
                if snapped { " (bar-aligned)" } else { "" }
            )
        },
    })
}

pub(super) fn technique_plan(
    outgoing: &Analysis,
    incoming: &Analysis,
    tier: Tier,
    entry: f64,
    blend: Option<(f64, f64, f64)>,
    throw_at: Option<f64>,
    phrase_bars: u32,
) -> Option<TransitionPlan> {
    let outgoing_falls = throw_at.is_some();
    let out_bpm = outgoing.bpm;
    let in_bpm = incoming.bpm;
    let has_tempo = (MIN_BPM..=MAX_BPM).contains(&out_bpm);
    let ratio = if has_tempo && (MIN_BPM..=MAX_BPM).contains(&in_bpm) {
        normalized_tempo_ratio(out_bpm, in_bpm)
    } else {
        1.0
    };
    let tempo_gap = (1.0 - ratio).abs();


    if matches!(tier, Tier::Beatmatched) && tempo_gap <= MAX_STRETCH_DEVIATION {
        return None;
    }
    let duration = if outgoing.content_end > 0.0 {
        outgoing.content_end
    } else {
        outgoing.duration
    };
    if duration <= 0.0 {
        return None;
    }
    let beat = if has_tempo {
        beat_seconds_of(outgoing).max(0.25)
    } else {
        0.5
    };
    let grid = has_tempo && outgoing.beat_confidence >= MIN_DJ_CONFIDENCE;
    let incoming_grid = has_tempo && incoming.beat_confidence >= MIN_DJ_CONFIDENCE;

    let (anchor, _) = resolve_mix_out_anchor(outgoing);
    let mix_end = anchor.min(duration).max(0.0);
    let out_energy = energy_between(outgoing, mix_end - 8.0 * beat, mix_end);
    let in_energy = energy_between(incoming, entry, entry + 8.0 * beat.max(0.4));
    let out_vocal = vocal_activity_between(outgoing, mix_end - 4.0 * beat, mix_end)
        .unwrap_or(outgoing.vocal_probability * 0.6);
    let slow = has_tempo && out_bpm < 92.0;
    let energetic_in = in_energy
        .zip(out_energy)
        .is_some_and(|(i, o)| i >= o * 0.9 && i > 0.35);
    let key_clash =
        key_distance(&trusted_key(outgoing), &trusted_key(incoming)).is_some_and(|d| d > 3);


    let mut eligible: Vec<(TransitionStyle, &str)> = Vec::new();
    if grid && out_vocal >= 0.35 {
        eligible.push((
            TransitionStyle::EchoOut,
            "echo out: the outgoing record ends on a vocal",
        ));
    } else if grid {



        eligible.push((
            TransitionStyle::EchoOut,
            "echo out: thrown on the record's last beat",
        ));
    }
    if grid && outgoing.beat_confidence >= 0.45 && !slow && out_vocal < 0.5 {
        eligible.push((
            TransitionStyle::LoopRoll,
            "loop roll: a strong grid and an instrumental ending",
        ));
    }
    if grid && outgoing.beat_confidence >= 0.4 && out_vocal < 0.8 {
        eligible.push((
            TransitionStyle::Texture,
            "texture send: a reliable grid gives the outro a light flanger ride",
        ));
    }
    if grid && outgoing.beat_confidence >= 0.35 && (slow || out_vocal >= 0.2) {
        eligible.push((
            TransitionStyle::Brake,
            "varispeed brake: the outgoing phrase slows into the handoff",
        ));
    }


    if grid && incoming_grid && energetic_in && !outgoing_falls {
        eligible.push((
            TransitionStyle::DropCut,
            "cut on the one: the next record comes in hot",
        ));
    }
    if slow || key_clash || !grid || tempo_gap > 0.12 {
        eligible.push((
            TransitionStyle::ReverbWash,
            if slow {
                "reverb wash: a slow record dissolves better than it cuts"
            } else if key_clash {
                "reverb wash: the keys clash, so the two never overlap dry"
            } else if !grid {
                "reverb wash: no reliable beat to cut or echo on"
            } else {
                "reverb wash: the tempos are too far apart to blend"
            },
        ));
    }
    if grid && eligible.is_empty() {
        eligible.push((
            TransitionStyle::EchoOut,
            "echo out: tempos too far apart for a blend",
        ));
    }



    let salt = pair_salt(outgoing, incoming);
    let recent = recent_techniques(salt);
    let candidates = avoid_threepeat(&eligible, &recent);
    let weights = candidates
        .iter()
        .map(|(style, _)| {
            technique_style_weight(
                *style,
                out_vocal,
                slow,
                key_clash,
                energetic_in,
                outgoing_falls,
                &recent,
            )
        })
        .collect::<Vec<_>>();
    let total_weight: f64 = weights.iter().sum();
    if total_weight <= 0.0 {
        return None;
    }
    let mixed = (salt as u64) ^ ((salt as u64).rotate_left(21)) ^ 0x9E37_79B9_7F4A_7C15;
    let unit = (mixed >> 11) as f64 / ((1u64 << 53) as f64);
    let target = unit * total_weight;
    let mut cumulative = 0.0;
    let selected = weights
        .iter()
        .position(|weight| {
            cumulative += *weight;
            target < cumulative
        })
        .unwrap_or(weights.len() - 1);
    let (style, why) = candidates[selected];
    note_technique(salt, style);




    let (blend_start, blend_fade, blend_cue) = match blend {
        Some((start, fade, cue)) => (start, fade, cue),
        None => (
            (mix_end - AUTO_FALLBACK_SECONDS).max(0.0),
            AUTO_FALLBACK_SECONDS,
            entry,
        ),
    };
    let adaptive_window = crate::mixer::technique_window_seconds(style, beat)?;
    let intro = (EFFECT_INTRO_BEATS * beat).max(EFFECT_INTRO_MIN_SECONDS);
    let mut available_outgoing = (duration - outgoing.audible_start_time.max(0.0)).max(0.0);
    if phrase_bars != 0
        && matches!(
            style,
            TransitionStyle::EchoOut
                | TransitionStyle::ReverbWash
                | TransitionStyle::Texture
                | TransitionStyle::Brake
        )
        && (mix_end - duration).abs() <= 1.5
    {
        if let Some(chord) = final_chord_attack(outgoing) {


            available_outgoing = available_outgoing.min((duration - chord).max(0.0));
        }
    }
    let incoming_content_end = if incoming.content_end > 0.0 {
        incoming.content_end
    } else {
        incoming.duration
    };
    let available_incoming = (incoming_content_end - blend_cue.max(0.0)).max(0.0);
    let technique_window = phrase_window_or_adaptive(
        style,
        beat,
        phrase_bars,
        available_outgoing,
        available_incoming,
        intro,
    );
    let phrase_window_active = technique_window > adaptive_window;
    let analysed_fade = if style == TransitionStyle::DropCut {

        blend_fade.clamp(0.5, 4.0 * beat)
    } else {
        blend_fade.max(AUTO_MIN_SECONDS)
    };



    let blend_fade = if style == TransitionStyle::DropCut {
        analysed_fade
    } else {
        let required = analysed_fade.max(technique_window + intro);
        if phrase_window_active {
            required
        } else {
            required.min(AUTO_TRANSITION_MAX_SECONDS.max(analysed_fade))
        }
    };
    let effect_seconds = if style == TransitionStyle::DropCut {
        (beat / 4.0).max(0.06)
    } else {
        technique_window
    }
    .min(blend_fade)
    .max(0.06);





    let natural_end = blend_start + analysed_fade;
    let end = match throw_at.filter(|_| !phrase_window_active) {
        Some(throw)
            if throw + effect_seconds < natural_end
                && natural_end - (throw + effect_seconds) <= MAX_THROW_DISCARD_SECONDS =>
        {
            throw + effect_seconds
        }
        _ => natural_end,
    };
    let cue = if style == TransitionStyle::DropCut {
        let drop = incoming_handoff(incoming, 8.0 * beat);
        if drop > entry && drop < entry + 32.0 * beat {
            drop
        } else {
            entry
        }
    } else {
        blend_cue
    };



    let intro_db = if style == TransitionStyle::DropCut {
        DROP_CUT_INTRO_DB
    } else if key_clash {
        EFFECT_INTRO_KEY_CLASH_DB
    } else {
        EFFECT_INTRO_DB
    };
    let grids_clash = !grid || !incoming_grid || tempo_gap > MAX_STRETCH_DEVIATION;
    let intro_band = if grids_clash { 1.0 } else { 0.4 };
    Some(TransitionPlan {
        style,
        bass_swap: false,
        bass_swap_fraction: DEFAULT_BASS_SWAP_FRACTION,
        filter_sweep: intro_band,
        vocal_overlap: 0.0,
        fade_seconds: blend_fade,




        outgoing_hold_seconds: (blend_fade - effect_seconds).max(0.0),
        bridge_start_seconds: 0.0,
        bridge_seconds: 0.0,
        bridge_repeats: 0,
        effect_seconds,
        effect_start_seconds: effect_window_start(end, effect_seconds),
        transition_end_seconds: end,
        cue_seconds: cue,
        playback_rate: 1.0,
        bed_fraction: 0.0,



        bed_gain_db: intro_db,
        dip_depth: 0.0,
        dip_width: 0.0,
        post_glide_seconds: 0.0,
        outgoing_duration_seconds: outgoing.duration,
        beat_seconds: beat,
        reason: format!(
            "{why} (clash; {blend_fade:.1}s blend with the next record in at {intro_db:.0} dB, \
             {effect_seconds:.1}s effect; {}, grid {:.2})",
            tempo_summary(out_bpm, in_bpm),
            outgoing.beat_confidence
        ),
    })
}




pub(super) fn technique_style_weight(
    style: TransitionStyle,
    outgoing_vocal: f64,
    slow: bool,
    key_clash: bool,
    energetic_incoming: bool,
    falling_tail: bool,
    recent: &[TransitionStyle],
) -> f64 {
    let outgoing_vocal = if outgoing_vocal.is_finite() {
        outgoing_vocal.clamp(0.0, 1.0)
    } else {
        0.5
    };
    let instrumental = 1.0 - outgoing_vocal;
    let fit = match style {
        TransitionStyle::EchoOut => 0.75 + outgoing_vocal * 0.55,
        TransitionStyle::LoopRoll => 0.80 + instrumental * 0.45,
        TransitionStyle::DropCut => {
            0.70 + (if energetic_incoming { 0.65 } else { 0.0 })
        }
        TransitionStyle::ReverbWash => {
            0.72 + (if slow { 0.28 } else { 0.0 }) + (if key_clash { 0.28 } else { 0.0 })
        }
        TransitionStyle::Texture => 1.0 + instrumental * 0.25,
        TransitionStyle::Brake => {
            0.68 + (if slow { 0.4 } else { 0.0 }) + (if falling_tail { 0.3 } else { 0.0 })
        }
        TransitionStyle::EqualPower
        | TransitionStyle::DjFilter
        | TransitionStyle::DjBlend
        | TransitionStyle::Gapless => 0.0,
    };
    let history = recent
        .iter()
        .take(2)
        .enumerate()
        .fold(1.0, |weight, (age, used)| {
            if *used == style {
                weight * if age == 0 { 0.28 } else { 0.58 }
            } else {
                weight
            }
        });
    fit * history
}

pub(super) fn avoid_threepeat<'a>(
    candidates: &[(TransitionStyle, &'a str)],
    recent: &[TransitionStyle],
) -> Vec<(TransitionStyle, &'a str)> {
    if recent.len() < 2 || recent[0] != recent[1] {
        return candidates.to_vec();
    }
    let varied = candidates
        .iter()
        .copied()
        .filter(|(candidate, _)| *candidate != recent[0])
        .collect::<Vec<_>>();
    if varied.is_empty() {
        candidates.to_vec()
    } else {
        varied
    }
}

pub(super) fn phrase_window_or_adaptive(
    style: TransitionStyle,
    beat_seconds: f64,
    phrase_bars: u32,
    available_outgoing: f64,
    available_incoming: f64,
    intro_seconds: f64,
) -> f64 {
    let adaptive = crate::mixer::technique_window_seconds(style, beat_seconds)
        .unwrap_or(AUTO_FALLBACK_SECONDS);
    let requested = crate::mixer::technique_window_seconds_for_phrase(
        style,
        beat_seconds,
        phrase_bars,
    )
    .unwrap_or(adaptive);
    let required_outgoing = requested + intro_seconds.max(0.0);


    let required_incoming = 4.0 * beat_seconds.max(0.0) + intro_seconds.max(0.0);
    if requested > adaptive
        && available_outgoing >= required_outgoing
        && available_incoming >= required_incoming
    {
        requested
    } else {
        adaptive
    }
}

pub(super) fn effect_window_start(window_end_seconds: f64, window_seconds: f64) -> f64 {
    (window_end_seconds - window_seconds).max(0.0)
}

pub(super) fn long_blend_allowed(outgoing: &Analysis, incoming: &Analysis) -> bool {
    assess(outgoing, incoming) == Tier::Beatmatched
}
