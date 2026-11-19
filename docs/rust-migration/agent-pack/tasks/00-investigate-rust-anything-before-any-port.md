# TASK 00 — Investigate `erkamyaman/rust-anything` before the migration

## Objective

Before modifying Nuvx, inspect **https://github.com/erkamyaman/rust-anything** and determine exactly how `rust-anything` can improve this Nuvx migration. Do not treat it as an automatic Kotlin/Swift conversion tool. Follow `RUST_ANYTHING_INTEGRATION.md` and the invariant `MASTER_RULES.md`.

## Work to perform

1. Inspect the currently available upstream repository and identify exact commit/release, source license, CLI/MCP installation instructions, README, migration skill, whole-app guide, and supported languages. Use baseline SHA `8a2e1d1f3725c25cea9d6cd851f12d060540b057` only as historical context; verify live HEAD.
2. Verify JavaScript/TypeScript, Java, C and other actually supported bridges. Explicitly confirm whether Kotlin/KMP and Swift now have direct support; if not, document why manual parity tests and platform FFI are needed.
3. Read its security warning: recorder/replayer executes original code many times. Build a complete **side-effect isolation policy** before recording ANY Nuvx unit. No live network, paid API, real database, user data, authentication sessions, signing keys, production infrastructure.
4. In a disposable directory **only**, install a reviewed pinned version if toolchain, environment and permissions permit. Run `rust-anything --help`, `doctor` if available, and (if safe) a tiny synthetic, pure TS/JS test function to prove analyze/bridge/record/build/diff/gate and record exact command outputs. The synthetic test is optional if blocked; do not claim installation or a working tool without evidence.
5. Identify specific low-side-effect Nuvx candidates in web, worker, sync, Java/C pure helpers. For each record supported? real inputs? risk? isolation plan? expected gain? ownership in canonical Rust core? replacement and rollback plan?
6. Create `docs/rust-migration/RUST_ANYTHING_EVALUATION.md` and `docs/rust-migration/TOOL_ELIGIBILITY.csv` from the included references/template.
7. Incorporate a mandatory eligibility check into subsequent tasks. Do not modify Nuvx's production code as part of Task 00.

## Acceptance

- Upstream source actually inspected, recorded exact revision and tool version (or clearly NOT VERIFIED).
- Supported and unsupported language boundaries stated accurately.
- Side-effect containment and data-safety rules documented.
- Scope-specific candidate matrix and proof/limitations written.
- Candidate isolated dry-run results recorded if executed; no fake assertions.
- Entire project otherwise unchanged.

## Continuation

When Task 00 finishes or is genuinely blocked, checkpoint `STATUS.json` and `HANDOFF.md`, **immediately start the next numbered task, Task 01, without waiting for another message**. Do not use this task as an opportunity to stop after writing a plan. Finish the rest of the 26-task migration program through continuous verified iterations.
