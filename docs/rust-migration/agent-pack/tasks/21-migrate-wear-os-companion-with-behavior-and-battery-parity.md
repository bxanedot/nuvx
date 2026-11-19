# TASK 21 — Migrate Wear OS companion with behavior and battery parity

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inventory every Wear UI and its phone protocol: pairing, play/pause/skip/volume, playlist access, artwork/scrolling, accessibility, rotary input, touch targets, focus, background communication, notifications and power. Move portable rules to nuvx-core; migrate one watch view/component at a time toward Rust UI only if Slint supports the exact original semantics. Retain native Wear OS/Google Play Services adapters where required. Match existing watch form factors, low-power states and visuals; test real or emulated watch interaction plus compatibility with older phone versions. Record package size, battery and memory effects, and never regress phone/watch paired operations.

## Acceptance criteria

All applicable watch screens/interactions and transport protocol cases pass, or old native component stays in place with explicit BLOCKED UI parity record.

## Dependencies

Steps 03, 06, 12, 17.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. No rust-anything Wear OS/Compose migration bridge. Retain native integration and test power/companion behavior. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
