# TASK 19 — Migrate Android phone and tablet, preserving every pixel

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Treat phone portrait/landscape and adaptive tablet layouts as separate UI matrices. Inventory every Android Compose screen and element. Migrate portable Kotlin and KMP logic to nuvx-core first, then evaluate Slint Rust UI component by component using real original screenshots, Compose interaction goldens and automated accessibility checks. Keep native Kotlin/Java adapters for Media3, notifications, Android Auto media sessions, permissions, IME, window insets, background jobs and any missing Slint OS hooks. Match blur, glass, symbols/SFStudio semantics, transitions, gestures, scrolling, haptics, text, focus and reduced motion. Keep original UI via a reversible development switch until parity is proven. Repeat until all Android mobile/tablet screens and flows are covered.

## Acceptance criteria

Every screen/state passed screenshot, interaction, device/API, accessibility and data checks; no redesign or lost Android feature. Any impossible rendering difference is BLOCKED and old UI remains active.

## Dependencies

Steps 03, 06, 12, 15, 17.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Kotlin/Jetpack Compose are not documented direct rust-anything bridge targets. Preserve original UI with Android screenshot and device tests. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
