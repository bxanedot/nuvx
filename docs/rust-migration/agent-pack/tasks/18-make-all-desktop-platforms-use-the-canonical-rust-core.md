# TASK 18 — Make all desktop platforms use the canonical Rust core

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Audit Windows/Linux/macOS Rust/egui source and confirm whether a separate workspace already implements duplicate domain algorithms. Migrate each portable algorithm to nuvx-core without changing egui layout, UI identity, tray, shortcuts, media keys, window decorations, drag/drop, settings, updater or platform-specific audio. Reuse existing Rust rather than rewrite for fashion. Run tests and UI regressions on all three operating systems; package x64/ARM64 targets as applicable. Benchmark any proposed egui -> Slint migration on real representative screens BEFORE deciding; do not migrate rendering if pixel/behavior parity cannot be verified. Keep all platform-specific tests and release artifacts.

## Acceptance criteria

Desktop uses canonical algorithms without duplicate copies; Windows, macOS, Linux interfaces indistinguishable under agreed test setups; no loss of media key or OS integration.

## Dependencies

Steps 05–17.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Desktop already uses Rust. Apply native Rust tests and benchmarks rather than using rust-anything for Rust-to-Rust translation. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
