# TASK 22 — Migrate iOS/iPadOS without SwiftUI redesign

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inspect app/ios and core/apple, existing Rust native audio, KMP bridge, SwiftUI views, signing/entitlement strategy and existing iOS parity gaps. Replace portable Swift/KMP business behavior with the canonical Rust core and safe UniFFI/FFI. Prototype one exact Slint-rendered view only when appropriate; preserve Apple-native SwiftUI view wherever matching UX, system controls or accessibility cannot be proven. Match existing SF Symbols where legally permitted, motion, safe areas, keyboard, touch, scroll, dark mode, typography, permission flows and Apple media controls. Keep AVAudioSession, RemoteCommandCenter, Now Playing, Keychain, widget/app groups, background service and signing adapters. Run Xcode simulator/device verification for each screen/feature and upgrade-path data tests.

## Acceptance criteria

All shipped iOS and iPad flows retain old behavior, sessions, media output and visuals; no claim of signed/device-tested parity without such evidence; native glue documented.

## Dependencies

Steps 03, 06, 07, 12, 15, 17.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Swift/SwiftUI/KMP are not documented direct rust-anything bridge targets. Use manual contract tests and device/simulator UI comparisons. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
