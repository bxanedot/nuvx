# TASK 26 — Final comprehensive parity gate, safe cleanup and acceptance

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Re-read CONTRACT, STATUS, FEATURE_MATRIX and the original baselines; verify all 25 prior tasks and every subtarget on Android phone/tablet/TV/Auto/Wear, iOS/iPad, desktop platforms, web, extensions, services and bot. Run end-to-end playback, sync, auth, downloads, UI/UX goldens, accessibility, storage upgrade/rollback, old-client protocol compatibility, builds and vulnerability/license checks. Compare measured post-migration download size, installed size, startup, CPU, RAM, frame timing and battery with Step 04; document regressions and resolve them before claiming benefit. Remove only obsolete source proven unused and replaceable, one reversible segment at a time; do not delete legacy formats or required platform glue. Inspect release packaging and operator docs. Produce a line-by-line acceptance report showing remaining non-Rust exceptions and exact evidence. Reopen earlier tasks automatically if anything is incomplete, and repeat until all feasible scope is genuinely covered.

## Acceptance criteria

No unverified UI/UX/features/platforms/data flows falsely marked done; one canonical Rust core; all required build/tests pass; old implementation is removed only where fully replaced; remaining platform-required glue documented. If external blockers remain, final state INCOMPLETE/BLOCKED, NOT FULLY COMPLETED.

## Dependencies

Every preceding step and all platform/feature subtargets.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Audit actual tool eligibility/results for every candidate; rust-anything gate alone does not satisfy native, security or UI/UX parity. Document unsupported Kotlin/Swift explicitly. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
