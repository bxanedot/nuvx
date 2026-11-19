# TASK 04 — Measure and explain size/performance baselines

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Produce repeatable release build and artifact analysis for all accessible targets. Record APK architecture slices and asset sizes, KMP/Compose/Media3 dependencies, ONNX and model weight size, native decoder libs, fonts, app images, desktop install/installed footprint, iOS frameworks, JS bundles and extension archives. Benchmark cold/warm start, idle/playback RAM, CPU, scroll frame timing, audio stability/latency, network requests and energy only where measurable. Inspect existing ONNX size experiment and avoid guessing savings. Capture exact commands, version, environment, artifact SHA, and results, labeling unrun metrics NOT_VERIFIED. Define a baseline against which every migration or framework replacement can be justified.

## Acceptance criteria

Metrics.csv and documented methodology present; data distinguishes release/uncompressed/installed sizes and architecture-specific artifacts; no imaginary benchmarks; no implementation changes.

## Dependencies

Steps 01–03.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. For hot paths in supported runtimes, assess whether rust-anything profile/bench can generate trustworthy, reproducible measurements without live side effects; it is not a device UI or binary-size profiler. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
