# TASK 03 — Freeze the current UI/UX as testable goldens

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Record all existing screens/components and interaction states for each shipped device class. Produce baseline screenshots and recordings at fixed density, viewport, system theme, language, font scale, and input method; include dark/light, portrait/landscape, RTL, accessibility, reduced motion, loading/offline/error, menus, overlays, player controls and navigation. Extract a source-of-truth list of tokens, symbols, animations, easing, gestures, focus, keyboard/remote behavior, and audio controls. Build a repeatable visual and interaction comparison harness using appropriate platform tools; include metadata and permitted image tolerance for unavoidable rendering noise, but do not accept human-visible changes. If physical devices or Apple signing are unavailable, flag specific coverage NOT_VERIFIED and preserve native UI as the baseline.

## Acceptance criteria

Baseline coverage table exists, verified captures and reproducible test procedure recorded, missing coverage visible; no styling or features changed. Set exact UX parity as a blocking migration gate.

## Dependencies

Steps 01–02.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
