# TASK 09 — Move portable networking/auth logic into Rust

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inventory all Ktor/OkHttp, Swift URLSession, reqwest, browser fetch, provider sessions, cookies, token renewal, retries, backoff, rate limits, transport and HTTP error parsing. Separate portable policies from platform-specific credential vaults, HTTP engine constraints, WebViews and browser browser-origin/security APIs. Migrate portable concerns in small testable slices to nuvx-core. Preserve observable retry timing, authentication persistence, session scopes, proxy settings, timeouts, regional headers and fail-closed routing. Mock server fixtures for success, 401/403, rate limit, redirects, packet loss, offline, cancellations and legacy response versions. Do not move secrets into JS, logs, URLs, or insecure storage.

## Acceptance criteria

Every migrated behavior has old/new fixture parity, security tests and measured overhead where relevant; keychain/keystore integration continues; no sign-in flow or HTTP semantics regressions.

## Dependencies

Steps 06–08.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Use rust-anything only for deterministic mockable TS/JS, Java or C helpers, not live account/session APIs. Block uncontrolled outbound network and use synthetic tokens. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
