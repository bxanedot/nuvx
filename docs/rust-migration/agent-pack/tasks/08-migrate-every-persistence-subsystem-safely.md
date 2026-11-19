# TASK 08 — Migrate every persistence subsystem safely

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Create explicit subtargets for queues, preferences, playlist databases, offline caches/indices, local Nuvx-only playlists, download records, secure session references, sync operation logs and any other platform storage. For each subtarget, build a Rust persistence abstraction and implement one reversible adapter at a time while retaining actual legacy layout, schema, serialization and encryption platform contracts. Test first launch, upgraded install from historical fixture, idempotent migrations, corruption, transactional crash recovery, concurrent reads/writes, disk full and rollback. Never migrate raw secrets into a less secure store. Keep the old storage path alive until proven compatible and repeat until all listed subtargets are covered.

## Acceptance criteria

All storage rows in FEATURE_MATRIX have upgrade and rollback tests and verified preservation; no erased downloads, local playlists or credentials; all pending targets remain tracked rather than disappearing.

## Dependencies

Steps 05–07; per-subtarget dependencies allowed.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Never run rust-anything record/replay against real databases, local settings, downloaded media or user accounts; use disposable fixtures and snapshots only. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
