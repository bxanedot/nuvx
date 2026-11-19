# TASK 01 — Create the immutable migration contract

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Read the Task 00 `rust-anything` evaluation and record the safety/eligibility contract alongside canonical Rust and immutable UI/UX.

Inspect the actual HEAD, AGENTS.md, existing migration/technology decisions, release processes, platform support, and CI. Record the owner's precise goals: maximum feasible Rust replacement; ONE canonical Rust core; EXACT current UI and UX with no design changes; all shipped feature and user-data compatibility. Establish clear definitions for portable Rust logic, platform glue, native UI, web CSS, and unsupported paths. Create docs/rust-migration/CONTRACT.md and docs/rust-migration/STATUS.json from the templates. Include an explicit prohibition on changing package IDs, signing, paths, credentials, serialized data, or production deployments without a tested and authorized path. Inspect existing docs before creating new ones to avoid conflicting migration contracts.

## Acceptance criteria

Contract exists and explains priorities, every platform, required tests, how to record blockers, and what genuinely necessary non-Rust code may remain. Ledger initializes all 26 tasks honestly. No production logic or UI is changed.

## Dependencies

None; this foundational task prepares the project.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
