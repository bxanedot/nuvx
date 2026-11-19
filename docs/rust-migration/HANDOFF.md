# Handoff

**State (2026-11-19):** Tasks 00–02 PASS, 03–04 partly done (NOT_VERIFIED). No product code changed by these tasks.

**Files:** `CONTRACT.md`, `RUST_ANYTHING_EVALUATION.md`, `TOOL_ELIGIBILITY.csv`, `FEATURE_MATRIX.csv`,
`UI_PARITY.csv`, `METRICS.csv`, `BLOCKERS.csv`, `STATUS.json`. Visual baselines live in `baselines/` on the
working machine only (gitignored: album art and personal data).

**Next:** Task 05. Two Rust trees already exist (desktop app crate, `app/ios/native-core`). Do not start by
rewriting apps; start a workspace with one small shared crate whose first content is logic duplicated today in
Kotlin and Rust (update tag/version rules in `app/desktop/src/update.rs` and `AppUpdateChecker.kt`), proven by
shared golden vectors before either side switches.

**Open blockers:** see `BLOCKERS.csv` (tool install needs approval; iOS/TV/Wear/web baselines; runtime metrics;
scope decision for 05+).
