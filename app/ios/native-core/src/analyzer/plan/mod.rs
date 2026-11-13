

mod analysis;
mod bridge;
mod cache;
mod choose;
mod cue;
mod edges;
mod key;
mod mix_out;
mod technique;
mod vocal_overlap;
mod wsola;

#[cfg(test)]
mod tests;

pub(crate) use cache::clear_analysis_cache;
pub use cache::{last_analysis_sources, next_energy_dip, seed_analysis_overlay, AnalysisOverlay};
pub use cue::{last_transition_edges, TransitionEdges};

use super::audio_analysis;
use super::beat;
use super::resample;
use super::vocal;
use crate::mixer::{TransitionPlan, TransitionStyle};

use analysis::Analysis;
use bridge::apply_bridge;
use cache::{analyze_cached, last_sources};
use choose::plan_from_with_phrase_bars;
use cue::record_transition_edges;
use edges::respect_music_edges;
use mix_out::resolve_mix_out_anchor;
use vocal_overlap::vocal_activity_between;
use wsola::phrase_switch;

const MIN_BEATMATCH_CONFIDENCE: f64 = 0.55;
const MIN_DJ_CONFIDENCE: f64 = 0.2;
const MIN_BPM: f64 = 40.0;
const MAX_BPM: f64 = 220.0;





const MAX_STRETCH_DEVIATION: f64 = 0.04;

const MAX_BEATMATCH_STRETCH: f64 = MAX_STRETCH_DEVIATION;
const VOCAL_ACTIVE_THRESHOLD: f64 = 0.6;
const FILTER_SWEEP: f64 = 1.0;
const AUTO_TRANSITION_MAX_BEATS: f64 = 16.0;
const AUTO_TRANSITION_MAX_SECONDS: f64 = 12.0;




const MAX_BRIDGED_SECONDS: f64 = 16.0;
const AUTO_MIN_SECONDS: f64 = 4.0;
const AUTO_FAST_TRACK_MIN_SECONDS: f64 = 6.0;

const AUTO_FALLBACK_SECONDS: f64 = 8.0;


const MIN_SMART_DURATION_SECONDS: f64 = 45.0;







const MIN_DECODED_FRACTION: f64 = 0.95;


const MIX_IN_END_MARGIN_SECONDS: f64 = 10.0;













const MAX_CUE_SECONDS: f64 = 8.0;
const MAX_CUE_BEATS: f64 = 16.0;
const MAX_CUE_FRACTION: f64 = 0.06;

const HANDOFF_FRACTION: f64 = 0.5;
const DEFAULT_BASS_SWAP_FRACTION: f64 = 0.7;
const MAX_BASS_SWAP_FRACTION: f64 = 0.85;
const MIN_BASS_STRUCTURE_SCORE: f64 = 0.25;
const BASS_SWAP_MAX_SECONDS: f64 = 6.0;





const GAPLESS_FADE_SECONDS: f64 = AUTO_MIN_SECONDS;


const MAX_DISCARDED_MUSIC_SECONDS: f64 = 12.0;


const AUDIBLE_ENERGY_FRACTION: f64 = 0.1;


const MIN_FADE_BEATS: usize = 4;


const UPSTREAM_MAX_FADE_BEATS: usize = 16;
const UPSTREAM_MAX_OVERLAP_SECONDS: f64 = 16.0;


const LONG_BLEND_BEATS: usize = 64;
const LONG_BLEND_SECONDS: f64 = 32.0;




const POST_BLEND_GLIDE_BEATS: f64 = 8.0;



const NEGLIGIBLE_STRETCH: f64 = 0.010 / LONG_BLEND_SECONDS;

const BED_GAIN_DB: f64 = -14.0;
const ARRANGEMENT_OVERLAP_BEATS: f64 = 8.0;
const MIN_CLEARANCE_SECONDS: f64 = 5.0;
const VOCAL_CLASH_TOLERANCE: f64 = 0.05;

pub fn plan_pair(
    outgoing_path: &str,
    incoming_path: &str,
    outgoing_text: &str,
    incoming_text: &str,
    album_sequential: bool,
    crossfade_seconds: f64,
    outgoing_duration_hint: f64,
    incoming_duration_hint: f64,
    skip_vocals: bool,
    tier_label: &str,
    allow_effects: bool,
    decode: impl Fn(&str, f64, f64, bool) -> Option<(Vec<f32>, u32, f64)>,
    duration_of: impl Fn(&str) -> f64,
) -> TransitionPlan {
    plan_pair_with_phrase_bars(
        outgoing_path,
        incoming_path,
        outgoing_text,
        incoming_text,
        album_sequential,
        crossfade_seconds,
        outgoing_duration_hint,
        incoming_duration_hint,
        skip_vocals,
        tier_label,
        allow_effects,
        0,
        decode,
        duration_of,
    )
}

pub fn plan_pair_with_phrase_bars(
    outgoing_path: &str,
    incoming_path: &str,
    outgoing_text: &str,
    incoming_text: &str,
    album_sequential: bool,
    crossfade_seconds: f64,
    outgoing_duration_hint: f64,
    incoming_duration_hint: f64,
    skip_vocals: bool,
    tier_label: &str,
    allow_effects: bool,
    phrase_bars: u32,
    decode: impl Fn(&str, f64, f64, bool) -> Option<(Vec<f32>, u32, f64)>,
    duration_of: impl Fn(&str) -> f64,
) -> TransitionPlan {
    let fade = if crossfade_seconds > 0.0 {
        crossfade_seconds.clamp(1.0, 12.0)
    } else {
        AUTO_FALLBACK_SECONDS
    };






    let hinted_duration = |hint: f64, path: &str| {
        if hint.is_finite() && hint > 0.0 {
            hint
        } else {
            duration_of(path)
        }
    };
    let outgoing_duration = hinted_duration(outgoing_duration_hint, outgoing_path);
    let out = analyze_cached(outgoing_path, skip_vocals, &decode, outgoing_duration);



    if blocked_text(outgoing_text) || blocked_text(incoming_text) {
        return plain_fallback(fade, &out);
    }





    if album_sequential {
        let (anchor, _) = resolve_mix_out_anchor(&out);
        let end = if out.content_end > 0.0 {
            out.content_end
        } else {
            out.duration
        };
        let has_interior_mix_out = anchor > 0.0 && anchor < end - 1.0;
        if !has_interior_mix_out {
            return TransitionPlan {
                style: TransitionStyle::Gapless,
                bass_swap: false,
                bass_swap_fraction: DEFAULT_BASS_SWAP_FRACTION,
                filter_sweep: 0.0,
                vocal_overlap: 0.0,
                fade_seconds: GAPLESS_FADE_SECONDS,
                outgoing_hold_seconds: 0.0,
                bridge_start_seconds: 0.0,
                bridge_seconds: 0.0,
                bridge_repeats: 0,
                effect_seconds: 0.0,
                effect_start_seconds: 0.0,
                transition_end_seconds: 0.0,
                cue_seconds: 0.0,
                playback_rate: 1.0,
                bed_fraction: 0.0,
                bed_gain_db: 0.0,
                dip_depth: 0.0,
                dip_width: 0.0,
                post_glide_seconds: 0.0,
                outgoing_duration_seconds: outgoing_duration,
                beat_seconds: 0.0,
                reason: String::new(),
            };
        }
    }

    let incoming_duration = hinted_duration(incoming_duration_hint, incoming_path);
    let inc = analyze_cached(incoming_path, skip_vocals, &decode, incoming_duration);



    let mut plan =
        phrase_switch(&out, &inc).unwrap_or_else(|| {
            plan_from_with_phrase_bars(&out, &inc, fade, allow_effects, phrase_bars)
        });
    respect_music_edges(&out, &inc, &mut plan);
    apply_bridge(&out, &mut plan);
    record_transition_edges(&out, &inc, &plan);
    log_plan(&out, &inc, &plan, tier_label);
    plan
}









fn log_plan(outgoing: &Analysis, incoming: &Analysis, plan: &TransitionPlan, tier: &str) {
    let grid = |a: &Analysis| {
        format!(
            "bpm={:.1} conf={:.2} beat={:.3} downbeats={} audible={:.1} content_end={:.1} dur={:.1} vocal_p={:.2} \
             key={}@{:.2}",
            a.bpm,
            a.beat_confidence,
            a.beat_interval,
            a.downbeats.len(),
            a.audible_start_time,
            a.content_end,
            a.duration,
            a.vocal_probability,
            if a.key.is_empty() { "?" } else { a.key.as_str() },
            a.key_confidence,
        )
    };



    let window_vocal = |a: &Analysis, from: f64, to: f64| {
        vocal_activity_between(a, from, to)
            .map(|value| format!("{value:.2}"))
            .unwrap_or_else(|| "-".into())
    };
    let out_beat = if outgoing.beat_interval > 0.0 {
        outgoing.beat_interval
    } else {
        0.5
    };
    let in_beat = if incoming.beat_interval > 0.0 {
        incoming.beat_interval
    } else {
        0.5
    };
    let out_end = if outgoing.content_end > 0.0 {
        outgoing.content_end
    } else {
        outgoing.duration
    };
    let in_start = incoming.audible_start_time.max(0.0);
    log::info!(
        "automix plan [{}] style={:?} cue={:.2}s fade={:.2}s end={:.1}s rate={:.4} \
         bed={:.2}@{:.0}dB dip={:.2} glide={:.1}s vocal_overlap={:.2} bass_swap={}@{:.2}",
        tier,
        plan.style,
        plan.cue_seconds,
        plan.fade_seconds,
        plan.transition_end_seconds,
        plan.playback_rate,
        plan.bed_fraction,
        plan.bed_gain_db,
        plan.dip_depth,
        plan.post_glide_seconds,
        plan.vocal_overlap,
        plan.bass_swap,
        plan.bass_swap_fraction,
    );
    log::info!(
        "automix out [{}]: {} vocal_tail={}",
        if outgoing.analysis_source.is_empty() {
            "dsp"
        } else {
            &outgoing.analysis_source
        },
        grid(outgoing),
        window_vocal(outgoing, out_end - 8.0 * out_beat, out_end)
    );
    log::info!(
        "automix in [{}]: {} vocal_head={}",
        if incoming.analysis_source.is_empty() {
            "dsp"
        } else {
            &incoming.analysis_source
        },
        grid(incoming),
        window_vocal(incoming, in_start, in_start + 8.0 * in_beat)
    );
    last_sources::set(&outgoing.analysis_source, &incoming.analysis_source);
}






fn plain_fallback(fade: f64, outgoing: &Analysis) -> TransitionPlan {
    let mut end = resolve_mix_out_anchor(outgoing).0;
    if end <= 0.0 {
        end = if outgoing.content_end > 0.0 {
            outgoing.content_end
        } else {
            outgoing.duration
        };
    }
    TransitionPlan {
        style: TransitionStyle::EqualPower,
        bass_swap: false,
        bass_swap_fraction: DEFAULT_BASS_SWAP_FRACTION,
        filter_sweep: 0.0,
        vocal_overlap: 0.0,
        fade_seconds: fade,
        outgoing_hold_seconds: 0.0,
        bridge_start_seconds: 0.0,
        bridge_seconds: 0.0,
        bridge_repeats: 0,
        effect_seconds: 0.0,
        effect_start_seconds: 0.0,
        transition_end_seconds: end,
        cue_seconds: 0.0,
        playback_rate: 1.0,
        bed_fraction: 0.0,
        bed_gain_db: 0.0,
        dip_depth: 0.0,
        dip_width: 0.0,
        post_glide_seconds: 0.0,
        outgoing_duration_seconds: outgoing.duration,
        beat_seconds: 0.0,
        reason: String::new(),
    }
}



fn blocked_text(text: &str) -> bool {
    const BLOCKED: [&str; 6] = [
        "podcast",
        "episode",
        "audiobook",
        "live",
        "concert",
        "performance",
    ];
    let lower = text.to_lowercase();
    let bytes = lower.as_bytes();
    for word in BLOCKED {
        let mut start = 0;
        while let Some(relative) = lower[start..].find(word) {
            let absolute = start + relative;
            let before_ok = absolute == 0 || !bytes[absolute - 1].is_ascii_alphanumeric();
            let end = absolute + word.len();
            let after_ok = end == bytes.len() || !bytes[end].is_ascii_alphanumeric();
            if before_ok && after_ok {
                return true;
            }
            start = absolute + word.len();
        }
    }
    false
}
