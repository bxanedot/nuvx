//! What playback does when a song fails: retry it, skip it, or stay. The same rules as Android's
//! `RecoveryPolicy` (PlaybackErrorRecovery.kt), checked against the shared cases in
//! `core/contracts/stream-recovery.json`.

pub const MAX_RECOVERIES: u32 = 2;
pub const MAX_SKIPS_IN_A_ROW: u32 = 4;
const FIRST_DELAY_MS: u64 = 350;

#[derive(Debug, PartialEq, Eq)]
pub enum Decision {
    /// Try the same song again after this many milliseconds.
    Retry(u64),
    Skip,
    /// Leave it: it played before, or too many songs in a row failed.
    Stay,
}

/// `attempts` counts this song's failures so far (1 on the first).
pub fn decide(
    attempts: u32,
    permanent: bool,
    never_started: bool,
    skips_in_a_row: u32,
) -> Decision {
    if !permanent && attempts <= MAX_RECOVERIES {
        return Decision::Retry(FIRST_DELAY_MS << attempts.saturating_sub(1).min(MAX_RECOVERIES));
    }
    if permanent {
        Decision::Skip
    } else if !never_started || skips_in_a_row >= MAX_SKIPS_IN_A_ROW {
        Decision::Stay
    } else {
        Decision::Skip
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn agrees_with_the_shared_stream_recovery_cases() {
        let doc: serde_json::Value =
            serde_json::from_str(include_str!("../../../contracts/stream-recovery.json")).unwrap();
        for c in doc["cases"].as_array().unwrap() {
            let n = |k: &str| c[k].as_u64().unwrap() as u32;
            let got = decide(
                n("attempts"),
                c["permanent"].as_bool().unwrap(),
                c["neverStarted"].as_bool().unwrap(),
                n("skipsInARow"),
            );
            let want = match c["decision"].as_str().unwrap() {
                "retry" => Decision::Retry(c["delayMs"].as_u64().unwrap()),
                "skip" => Decision::Skip,
                _ => Decision::Stay,
            };
            assert_eq!(got, want, "{}", c["name"]);
        }
    }
}
