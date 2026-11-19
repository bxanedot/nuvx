# TASK 06 — Create audited Android/iOS/native/WASM bindings

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Prove an end-to-end FFI and WASM path using one existing low-risk pure function from nuvx-core: Android Kotlin/JNI or UniFFI, iOS Swift/UniFFI or C ABI, desktop direct Rust, browser wasm-bindgen or equivalent. Define stable exported API, errors, cancellation, lifetime/ownership, allocator rules, panic boundaries, threading/main-thread dispatch, serialization overhead, ABI and version negotiation. Test on all buildable target ABIs and host environments, and record unsupported platforms. Keep generated binding code and minimal platform glue explicit. Avoid converting playback or UI as part of the interoperability proof.

## Acceptance criteria

Calling the same Rust function produces identical fixtures through each available bridge; generated headers/glue are maintained; no unwinding across FFI; portable tests pass and missing device checks are exposed.

## Dependencies

Step 05.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. rust-anything JNI is documented for some Java static methods, not arbitrary Kotlin/KMP or Swift. Use audited UniFFI/JNI/C-ABI/WASM as appropriate and do not claim universal bridge support. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
