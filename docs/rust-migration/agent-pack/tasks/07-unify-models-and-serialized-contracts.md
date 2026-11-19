# TASK 07 — Unify models and serialized contracts

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inventory Kotlin/KMP, Swift, desktop Rust and TypeScript domain models for songs/tracks, album, artist, playlists, queue, settings, sync operations, metadata, IDs, and errors. Make nuvx-core the canonical owner of transport-neutral types and their serialization. Build compatibility adapters/generated bindings instead of duplicating business rules. Extract representative fixtures from the original code and historical stores; verify round-trips, unknown fields, defaults, ordering, timezones, encoding and old versions. Preserve currently persisted files, network protocol and user settings exactly; feature-flag integration and migrate one model family at a time until complete.

## Acceptance criteria

Every applicable model family has a cross-platform contract test and old-client compatibility evidence; stored user data unaffected; no silent format drift.

## Dependencies

Steps 05–06.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Evaluate portable TS/JS and Java model-mapping helpers with rust-anything recorded fixtures only in isolated tests; manually verify Kotlin/Swift serialization contracts. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
