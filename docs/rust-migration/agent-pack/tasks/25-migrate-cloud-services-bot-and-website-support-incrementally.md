# TASK 25 — Migrate cloud services, bot and website support incrementally

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inventory Cloudflare Worker Listen Together/presence, Node persistent sync/Oracle, Discord bot, website backend/APIs, and other supporting scripts. Migrate one distinct deployable/service at a time to Rust as supported by target runtime: e.g. workers-rs, Axum/Tokio, or minimal existing JS binding where platform requires it. Reuse canonical Rust protocol/validation crates, without coupling UI binaries to services. Preserve exact endpoint URLs, signatures, authentication, database schema, storage bindings, rates/quotas, operational budgets, failure handling, backwards-compatible clients, bot slash command behavior and observability. Build dual-run/contract tests, failover/restart fixtures and rollbacks. Never deploy or alter live infrastructure absent separate authorization; mark unverified production behavior accordingly. Repeat until each deployable and tool has a final disposition.

## Acceptance criteria

Every in-scope service/bot/backend module has Rust migration or justified platform adapter, CI tests, matching external contracts, security checks, and a reversible deploy plan; existing production remains untouched unless authorized.

## Dependencies

Steps 05–16 and service-specific protocol dependencies.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Consider rust-anything for pure TS worker/sync/bot helpers, and app record only on disposable/isolated CLI or HTTP test servers; no production secrets or endpoints. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
