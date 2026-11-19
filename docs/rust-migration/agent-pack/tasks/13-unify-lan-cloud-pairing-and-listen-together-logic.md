# TASK 13 — Unify LAN, cloud, pairing and Listen Together logic

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Map Nuvx LAN Sync, encrypted Wi-Fi pairing, cloud persistent operation log, server Sync, and Listen Together/Worker presence as distinct flows. Build shared Rust protocol models, version negotiation, operation validation, deterministic conflict resolution and replay rules where existing protocols permit. Retain protocol versions and interoperate with existing released clients and old servers; preserve user consent, device-local data boundaries, offline behavior, six-digit pairing confirmation and trust revocation. Add property/fixture tests for races, replay, malicious input, old-client messaging, unavailable cloud and simultaneous devices. No undeclared syncing, live deployment, or incompatible silent schema changes.

## Acceptance criteria

Cross-version and cross-platform contract tests pass for all sync products; rollout remains reversible; existing LAN discovery and permissions continue to function.

## Dependencies

Steps 07–12.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. For TS sync/worker pure protocol helpers, consider rust-anything with mock state/storage; prohibit running its recorder against live Oracle, Cloudflare or LAN-paired devices. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
