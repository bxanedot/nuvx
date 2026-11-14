use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

use crate::stream::{self, Format, Shared};

const HOP_MS: i64 = 10;
const MAX_LAG_FRAMES: usize = 6_000;
const MIN_OVERLAP_FRAMES: usize = 300;
const MIN_CONFIDENCE: f64 = 0.4;
const WINDOW_MS: usize = 40;
const ANALYSIS_SECONDS: usize = 90;

static OFFSETS: LazyLock<Mutex<HashMap<(String, String), i64>>> = LazyLock::new(Default::default);

pub(crate) fn cached_offset(source_id: &str, target_id: &str) -> Option<i64> {
    if source_id == target_id {
        return Some(0);
    }
    OFFSETS.lock().ok()?.get(&(source_id.to_owned(), target_id.to_owned())).copied()
}

pub(crate) fn remember_offset(source_id: &str, target_id: &str, offset_ms: i64) {
    let Ok(mut offsets) = OFFSETS.lock() else { return };
    if offsets.len() >= 128 {
        offsets.clear();
    }
    offsets.insert((source_id.to_owned(), target_id.to_owned()), offset_ms);
    offsets.insert((target_id.to_owned(), source_id.to_owned()), -offset_ms);
}

pub(crate) fn decode_envelope(data: Vec<u8>, duration_ms: u64, format: Format) -> anyhow::Result<Vec<f32>> {
    let shared = Shared::from_bytes_with_format(data, duration_ms, None, format, None);
    let decoder = stream::decoder(shared, false)?;
    let channels = rodio::Source::channels(&decoder).get() as usize;
    let rate = rodio::Source::sample_rate(&decoder).get() as usize;
    let count = rate.saturating_mul(ANALYSIS_SECONDS).saturating_mul(channels);
    let samples: Vec<f32> = decoder.take(count).collect();
    Ok(energy_envelope(&samples, channels, rate))
}

fn energy_envelope(samples: &[f32], channels: usize, rate: usize) -> Vec<f32> {
    if channels == 0 || rate == 0 {
        return Vec::new();
    }
    let frame_count = samples.len() / channels;
    let hop_frames = (rate * HOP_MS as usize / 1000).max(1);
    let window_frames = (rate * WINDOW_MS / 1000).max(hop_frames);
    if frame_count < window_frames {
        return Vec::new();
    }
    let count = (frame_count - window_frames) / hop_frames + 1;
    (0..count)
        .map(|frame| {
            let start = frame * hop_frames * channels;
            let end = start + window_frames * channels;
            (samples[start..end].iter().map(|s| s * s).sum::<f32>() / (window_frames * channels) as f32).sqrt()
        })
        .collect()
}

pub(crate) fn offset_ms(source: &[f32], target: &[f32]) -> Option<i64> {
    if source.len() < MIN_OVERLAP_FRAMES || target.len() < MIN_OVERLAP_FRAMES {
        return None;
    }
    let min_lag = -(MAX_LAG_FRAMES.min(source.len() - MIN_OVERLAP_FRAMES) as isize);
    let max_lag = MAX_LAG_FRAMES.min(target.len() - MIN_OVERLAP_FRAMES) as isize;
    let mut best_lag = 0;
    let mut best_correlation = -1.0f64;
    for lag in min_lag..=max_lag {
        let source_start = lag.saturating_neg().max(0) as usize;
        let target_start = lag.max(0) as usize;
        let overlap = (source.len() - source_start).min(target.len() - target_start);
        if overlap < MIN_OVERLAP_FRAMES {
            continue;
        }
        let (mut source_sum, mut target_sum, mut source_sq, mut target_sq, mut cross) = (0.0, 0.0, 0.0, 0.0, 0.0);
        for i in 0..overlap {
            let x = source[source_start + i] as f64;
            let y = target[target_start + i] as f64;
            source_sum += x;
            target_sum += y;
            source_sq += x * x;
            target_sq += y * y;
            cross += x * y;
        }
        let n = overlap as f64;
        let covariance = cross - source_sum * target_sum / n;
        let source_variance = source_sq - source_sum * source_sum / n;
        let target_variance = target_sq - target_sum * target_sum / n;
        let denominator = (source_variance * target_variance).sqrt();
        if denominator > 1e-6 {
            let correlation = covariance / denominator;
            if correlation > best_correlation {
                best_correlation = correlation;
                best_lag = lag;
            }
        }
    }
    if best_correlation < MIN_CONFIDENCE || best_lag == min_lag || best_lag == max_lag {
        return None;
    }
    Some(best_lag as i64 * HOP_MS)
}

#[cfg(test)]
mod tests {
    use super::{cached_offset, energy_envelope, offset_ms, remember_offset};

    fn signal(len: usize, mut state: u32) -> Vec<f32> {
        (0..len)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 17;
                state ^= state << 5;
                state as f32 / u32::MAX as f32
            })
            .collect()
    }

    #[test]
    fn finds_a_later_video_start_with_a_different_duration() {
        let source = signal(700, 1);
        let mut target = signal(65, 2);
        target.extend_from_slice(&source);
        target.extend(signal(90, 3));
        assert_eq!(offset_ms(&source, &target), Some(650));
    }

    #[test]
    fn finds_an_earlier_video_start() {
        let source = signal(765, 4);
        let target = source[65..].to_vec();
        assert_eq!(offset_ms(&source, &target), Some(-650));
    }

    #[test]
    fn finds_a_long_video_intro_and_uses_the_overlapping_audio() {
        let source = signal(6_500, 10);
        let mut target = signal(4_500, 11);
        target.extend_from_slice(&source);
        target.extend(signal(200, 12));
        assert_eq!(offset_ms(&source, &target), Some(45_000));
    }

    #[test]
    fn rejects_silence() {
        assert_eq!(offset_ms(&vec![0.0; 700], &vec![0.0; 800]), None);
    }

    #[test]
    fn rejects_unrelated_audio() {
        assert_eq!(offset_ms(&signal(700, 8), &signal(800, 9)), None);
    }

    #[test]
    fn keeps_offsets_separate_for_each_audio_video_pair() {
        remember_offset("audio-a", "video-a", 650);
        remember_offset("audio-b", "video-b", -1_250);
        assert_eq!(cached_offset("audio-a", "video-a"), Some(650));
        assert_eq!(cached_offset("video-a", "audio-a"), Some(-650));
        assert_eq!(cached_offset("audio-b", "video-b"), Some(-1_250));
        assert_eq!(cached_offset("audio-a", "video-b"), None);
    }

    #[test]
    fn creates_a_mono_energy_envelope_from_stereo_pcm() {
        let samples = vec![0.5; 200];
        let envelope = energy_envelope(&samples, 2, 1_000);
        assert_eq!(envelope.len(), 7);
        assert!(envelope.iter().all(|value| (value - 0.5).abs() < 1e-6));
    }
}
