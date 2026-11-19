# TASK 05 — Build one canonical modular Cargo workspace

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inspect existing Rust workspace(s) and avoid duplicating the desktop or iOS native crate graph. Introduce or extend core/rust as the canonical Cargo workspace with a small nuvx-core for portable app rules and only justified support crates. Define module boundaries: shared domain, state, serialization, playlist, queue, sync, storage contracts, download policy, catalog normalization, network policies, audio DSP. Select target-aware feature flags for desktop, Android, Apple, browser/WASM, and server without bundling irrelevant dependencies. Configure formatting, Clippy, tests, target builds, consistent errors, CI and supply-chain audit. Introduce one harmless library function as a compile/usage smoke check without changing behavior.

## Acceptance criteria

One documented Rust source of truth compiles for available targets; workspace avoids cyclic deps and cross-target unsupported imports; all old apps still build and behave identically.

## Dependencies

Steps 01–04.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Generated rust-anything units must ultimately share the canonical Rust workspace; do not create competing cores or silently merge duplicate crates. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
