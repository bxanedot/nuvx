# TASK 12 — Unify deterministic queue and playback state

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Extract and migrate queue ordering, shuffle seed semantics, repeat, autoplay, next/previous, history, progress, resume position, startup restore, queue persistence, transitions and error/retry state into Rust. Preserve Android ExoPlayer/Media3, native iOS media sessions, and desktop output where necessary; separate logical queue state from playback engine. Use a deterministic cross-platform event/state test corpus, including process kill, disk restore, gapless/crossfade state, device handoff, duplicates, disappearing tracks and offline playback. Compare all original and Rust transitions and persistence byte/semantic format. Iterate for every platform until covered.

## Acceptance criteria

One portable queue algorithm drives relevant app adapters; no changed audible behavior, queue persistence, or controls. Background/device checks explicitly verified or flagged.

## Dependencies

Steps 07–11.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Use rust-anything only where a pure supported-language queue helper can be isolated; manual equivalence tests are mandatory for Kotlin/Swift queue logic and playback state. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
