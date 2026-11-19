# TASK 23 — Migrate web app to shared Rust and Leptos without visual change

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inventory full React/TypeScript/WXT web player, DOM semantics, existing CSS, animation styles, keyframes, responsive layouts, browser feature support, authentication, DRM-like/media limitations and Shaka interactions. Migrate one component/algorithm at a time using Rust/WASM and Leptos where it preserves the same browser output; keep generated or authored CSS where browser rendering requires it. Compare screenshot goldens at fixed browsers/resolutions, color schemes, font scales, keyboard, screen reader and animation states. Preserve all URLs, routing, cookies, local storage, service-worker caching, extension integration and web-player controls. Measure WASM/bundle overhead and startup cost; avoid massive WASM dependencies just for language purity.

## Acceptance criteria

Every web screen, dynamic interaction, style and accessibility test matches the reference; network/auth/extension interoperability verified; unavoidable CSS/JS adapter tracked.

## Dependencies

Steps 03, 06, 07, 17.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Use rust-anything for suitable isolated TS/JS pure functions, NOT React DOM/CSS rendering. Keep browser screenshot and E2E parity tests mandatory. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
