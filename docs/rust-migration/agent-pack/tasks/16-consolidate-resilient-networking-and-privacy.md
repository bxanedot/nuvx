# TASK 16 — Consolidate resilient networking and privacy

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inspect current Android SOCKS5/Orbot and proxy modes, desktop proxy routing, DNS behavior, Tor/VLESS external client interfaces, user settings, security logs, restricted-network fallbacks and platform limits. Canonicalize portable policy/validation in nuvx-core while preserving OS transport and security sandbox constraints. Verify DNS leak boundaries, fail-closed routes, privacy settings, TLS verification, timeout behavior, cancel/resume, offline and filtering failures on each platform where supported. Never promise a network bypass without actual tests and never change defaults or traffic destinations. Keep user-facing settings and error messages identical.

## Acceptance criteria

No weakened privacy/TLS or DNS routing; regression tests confirm defaults, blocked-network behavior and backoff; unverified real-world routing marked NOT_VERIFIED.

## Dependencies

Steps 09–15.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Do not replay generated edge cases through live VPN/proxy/Tor or real restricted-network endpoints; mock transport and test security boundaries separately. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
