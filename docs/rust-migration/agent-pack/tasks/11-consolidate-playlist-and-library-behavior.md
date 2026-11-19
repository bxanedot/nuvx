# TASK 11 — Consolidate playlist and library behavior

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Enumerate operations: Nuvx-only local versus YouTube Music playlists, create/edit/delete, ordering/reordering, add/remove/undo, imports/exports M3U/CSV, starred/saved albums, duplicate items, missing track handling, offline behavior and conflict rules. Move all portable algorithms to the canonical Rust core in small parts. Add golden fixtures from existing Kotlin, Swift, desktop and web logic, including user consent/privacy and round-trip exports. Preserve local-device-only storage defaults and never upload Nuvx-only playlists without the user's existing explicit choice. Keep the original UI and storage adapters active until all cases match.

## Acceptance criteria

Playlist/library feature rows pass old/new parity including import/export and data migration; no unintended remote writes, changed sorting, data loss or interface changes.

## Dependencies

Steps 07–10.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. For pure playlist transformations, consider rust-anything if implemented in supported languages, only with synthetic fixture playlists, not real accounts. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
