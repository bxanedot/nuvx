# TASK 15 — Migrate portable audio/DSP with measurable fidelity

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inspect original audio path separately on Android, iOS, Windows, Linux and macOS: Media3, AVAudioSession, Rust engines, custom C/C++ codecs, FFmpeg/librempeg, Oboe/USB, bit-perfect, EQ, loudness, AutoMix, gapless, crossfade, decoders, DSP, casting and output routing. Migrate only portable, proven algorithms to Rust in isolated testable segments. Retain the official platform media service and driver output integrations. Preserve exact audio behavior, sample rates, latency and output switching. Run reference PCM golden tests where valid, audible regression/device tests, stress and memory/CPU/battery benchmarks. Never remove capabilities based only on crate preference or rewrite protected/licensed code without compliance.

## Acceptance criteria

Verified audio stability and quality equal or better; exact platform playback and background controls still work. External decoder/driver/native exceptions documented with legal attribution.

## Dependencies

Steps 05–14.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. C functions with pure deterministic inputs may qualify; audio JNI/C++/drivers, hardware fidelity, timing and platform output must be verified by dedicated tests. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
