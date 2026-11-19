# TASK 20 — Migrate Android TV, Google TV and Auto separately

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Treat Android TV/Google TV, Android Auto projection and standalone Nuvx Auto as distinct subtargets, each with its own screen/input/service inventory. Use the shared Rust core for portable logic. Consider Rust/Slint visual rendering only where it matches original 10-foot UI, remote D-pad focus, large typography, Back behavior, browse controls, automotive-safe media UI and Android platform API constraints. Preserve Media3 MediaLibraryService and official automotive templates when mandatory. For each device class and component run emulator or real-device navigation, back-stack, audio/transport control, accessibility and screenshot tests. Never substitute a custom car screen for officially required automotive UI. Repeat until every current subtarget is verified.

## Acceptance criteria

All three device-class matrices pass or explicitly block unsafe/unsupported UI conversion while leaving original interface working; shared logic is Rust; no driver-safety or input regressions.

## Dependencies

Steps 03, 06, 12, 17–19.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. No rust-anything coverage for TV/Auto/official Android media APIs. Test with platform-specific emulators and hardware. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
