# TASK 24 — Migrate Chrome and Firefox extension internals safely

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Inventory manifest, permissions, background/service worker, content scripts, extension UI and connection to web player. Shift portable policy, parsing and shared domain logic into Rust/WASM. Use minimal JavaScript and manifests where browser execution requirements mandate them; do not claim extension service workers run pure WASM without a JS entry. Preserve original popup appearance, messaging, user permissions, activation behavior, security CSP, authentication, and compatibility with released web clients. Build and execute browser-specific tests and artifact inspection for Chrome and Firefox, including reloading/updating an installed extension. Test permissions and browser policy compliance; no new unrequested privileges.

## Acceptance criteria

Both browser targets work and maintain UX, protocol and permission contracts; shared algorithms use canonical Rust core; minimal required JS and manifest glue explicitly inventoried.

## Dependencies

Steps 03, 06, 07, 23.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Use rust-anything only for safely isolated TS/JS helpers with stubbed extension APIs; never execute recorded cases against a live extension/profile. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
