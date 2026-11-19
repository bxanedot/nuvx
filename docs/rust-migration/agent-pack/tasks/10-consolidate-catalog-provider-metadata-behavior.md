# TASK 10 — Consolidate catalog/provider metadata behavior

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inventory search, recommendations, track resolving, album/artist browsing, metadata translation, lyrics and provider-specific behavior on each client. Extract portable normalization, filtering, ordering, lookup-result interpretation and mapping algorithms to nuvx-core; retain platform-specific network adapter/proprietary SDK integration if necessary. Preserve exact search results and deterministic ordering for identical responses, error mapping, regional behavior, safe URLs, provider-specific fallbacks and rate limits. For each provider use recorded fixtures, golden comparisons, and offline tests. Never remove features or swap third-party libraries blindly to increase Rust source counts.

## Acceptance criteria

Canonical provider-independent behavior covers each current client with parity evidence; actual current endpoints and UI results remain compatible; provider adapters are explicitly documented.

## Dependencies

Steps 07–09.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. For deterministic isolated metadata helpers, consider rust-anything bridge/record/diff/gate; do not call real YouTube/Spotify APIs with recorder-generated arguments. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
