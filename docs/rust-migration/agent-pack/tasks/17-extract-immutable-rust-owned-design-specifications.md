# TASK 17 — Extract immutable Rust-owned design specifications

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

From the captured baselines and original source, create machine-readable UI design tokens and platform-specific behavior contracts: exact fonts/fallbacks, text metrics, colors, gradients, shadows, blur, translucency, spacing, size, responsive rules, icons, symbolic transitions, easing, gesture thresholds, accessibility and focus states. Research available Slint/egui/Leptos/native rendering controls only as necessary. Create a small read-only prototype of an existing component with two states and compare to golden pixel/interaction references. Keep original fonts/icon distribution rights in view. Do not invent a new visual language or silently adjust differences.

## Acceptance criteria

Extracted tokens match original screenshots and CSS/Compose/SwiftUI values; parity harness can block diffs; if prototype cannot match, retain old rendering and record blocker rather than compromising UX.

## Dependencies

Steps 01–04 and applicable core steps.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. rust-anything cannot prove pixel-perfect UI. Keep screenshot, motion and accessibility tests as the acceptance evidence. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
