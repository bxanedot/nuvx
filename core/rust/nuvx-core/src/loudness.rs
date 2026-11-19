//! Volume normalization: how much a track is turned up or down so songs play at a similar loudness.
//! Shared cases: `core/contracts/loudness-gain.json` (also checked by Android's PlaybackLoudnessTest).

/// YouTube reports a track's `loudnessDb` relative to its own -14 LUFS reference.
pub const DEFAULT_TARGET_LUFS: i32 = -14;

/// Gain in dB for a track measured `loudness_db` above YouTube's reference, for a target in LUFS.
/// Turning down is allowed up to 15 dB, turning up only up to 3 dB (no clipping surprises).
pub fn norm_gain_db(target_lufs: i32, loudness_db: f32) -> f32 {
    ((target_lufs - DEFAULT_TARGET_LUFS) as f32 - loudness_db).clamp(-15.0, 3.0)
}

#[cfg(test)]
mod tests {
    #[test]
    fn agrees_with_the_shared_loudness_gain_cases() {
        let doc: serde_json::Value =
            serde_json::from_str(include_str!("../../../contracts/loudness-gain.json")).unwrap();
        for c in doc["cases"].as_array().unwrap() {
            let got = super::norm_gain_db(
                c["targetLufs"].as_i64().unwrap() as i32,
                c["loudnessDb"].as_f64().unwrap() as f32,
            );
            let want = c["gainDb"].as_f64().unwrap() as f32;
            // Android rounds to a hundredth of a dB (millibels), so compare at that precision.
            assert!(
                ((got * 100.0).round() - (want * 100.0).round()).abs() < 1.0,
                "{}: {got} vs {want}",
                c["name"]
            );
        }
    }
}
