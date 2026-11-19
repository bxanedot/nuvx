# TASK 14 — Consolidate downloads, caches and offline policy

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inventory download tasks, resumability, range headers, offline library, file integrity, cache keys/eviction, quotas, 500MB safety floor, Wi-Fi-only behavior, background tasks and cross-platform file layout. Implement portable scheduling, integrity and cache policy in nuvx-core with native network/background job adapters. Do not change file paths, remove downloaded songs, silently invalidate encrypted files, or alter offline controls. Test killed-app resume, low storage, partial writes, duplicate requests, network/proxy changes, serialization compatibility and concurrent transfers. Track phone, tablet, iOS and desktop separately where behaviors differ.

## Acceptance criteria

Each supported downloads/cache feature has reproducible parity coverage and intact legacy files; file downloads survive restart; original UI and user options unchanged.

## Dependencies

Steps 08–13.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Avoid rust-anything replay of actual download operations. Only safe deterministic utilities in supported languages with disposable files may use it. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
