





use crate::mixer::TransitionStyle;

use super::plan::plan_pair;
use super::smoothness::score_plan;


#[derive(Debug, Clone)]
pub struct CandidateScore {
    pub index: u32,
    pub cost: f64,
    pub smoothness: f64,
    pub style: TransitionStyle,
    pub playback_rate: f64,
}





pub fn rank_candidates(
    current_path: &str,
    candidate_paths: &[String],
    current_text: &str,
    candidate_texts: &[String],
    crossfade_seconds: f64,
    skip_vocals: bool,
    decode: impl Fn(&str, f64, f64, bool) -> Option<(Vec<f32>, u32, f64)> + Copy,
    duration_of: impl Fn(&str) -> f64 + Copy,
) -> Vec<CandidateScore> {
    let mut scored = Vec::with_capacity(candidate_paths.len());
    for (index, path) in candidate_paths.iter().enumerate() {
        let text = candidate_texts.get(index).map(String::as_str).unwrap_or("");
        let plan = plan_pair(
            current_path,
            path,
            current_text,
            text,
            false,
            crossfade_seconds,
            0.0,
            0.0,
            skip_vocals,
            "sequencer",



            true,
            decode,
            duration_of,
        );
        let smooth = score_plan(&plan);
        let stretch = (plan.playback_rate - 1.0).abs();
        let mut cost = 1.0 - smooth.score;
        cost += stretch * 2.0;
        cost += plan.vocal_overlap * 0.5;
        if smooth.forced_stretch {
            cost += 2.0;
        }
        match plan.style {
            TransitionStyle::DjBlend => {}
            TransitionStyle::DjFilter => cost += 0.15,
            TransitionStyle::EqualPower => cost += 0.4,
            TransitionStyle::Gapless => cost += 0.05,


            TransitionStyle::EchoOut
            | TransitionStyle::LoopRoll
            | TransitionStyle::ReverbWash
            | TransitionStyle::DropCut
            | TransitionStyle::Texture
            | TransitionStyle::Brake => cost += 0.1,
        }

        if matches!(plan.style, TransitionStyle::DjBlend) && stretch <= 0.04 {
            cost -= 0.1;
        }


        if plan.bass_swap && plan.fade_seconds >= 8.0 {
            cost -= 0.05;
        }
        if plan.cue_seconds > 0.5 && plan.cue_seconds < 48.0 {
            cost -= 0.03;
        }
        scored.push(CandidateScore {
            index: index as u32,
            cost,
            smoothness: smooth.score,
            style: plan.style,
            playback_rate: plan.playback_rate,
        });
    }
    scored.sort_by(|a, b| {
        a.cost
            .partial_cmp(&b.cost)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| a.index.cmp(&b.index))
    });
    scored
}


pub fn rank_candidate_indices(
    current_path: &str,
    candidate_paths: &[String],
    current_text: &str,
    candidate_texts: &[String],
    crossfade_seconds: f64,
    skip_vocals: bool,
    decode: impl Fn(&str, f64, f64, bool) -> Option<(Vec<f32>, u32, f64)> + Copy,
    duration_of: impl Fn(&str) -> f64 + Copy,
) -> Vec<u32> {
    rank_candidates(
        current_path,
        candidate_paths,
        current_text,
        candidate_texts,
        crossfade_seconds,
        skip_vocals,
        decode,
        duration_of,
    )
    .into_iter()
    .map(|s| s.index)
    .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mixer::TransitionPlan;

    #[test]
    fn rank_prefers_transparent_blend_over_forced_equal_power_costs() {

        let blend = TransitionPlan {
            style: TransitionStyle::DjBlend,
            playback_rate: 1.0,
            fade_seconds: 12.0,
            bass_swap: true,
            ..TransitionPlan::default()
        };
        let plain = TransitionPlan {
            style: TransitionStyle::EqualPower,
            playback_rate: 1.0,
            ..TransitionPlan::default()
        };
        let warp = TransitionPlan {
            style: TransitionStyle::DjBlend,
            playback_rate: 1.12,
            ..TransitionPlan::default()
        };
        assert!(score_plan(&blend).score > score_plan(&plain).score);
        assert!(score_plan(&plain).score > score_plan(&warp).score);
        assert!(score_plan(&warp).forced_stretch);
    }
}
