# TASK 02 — Inventory every feature, module and platform

## Objective

Complete this numbered scope as part of the **continuous autonomous** Nuvx Rust migration. This task may require multiple small implementation rounds and must cover every applicable item, not one arbitrary example.

## Required work

Traverse app/, core/, CI workflows, configs, tests, models, native libraries, website, browser extensions, Discord bot and infrastructure. Enumerate features and owning modules for Android phone/tablet, Android TV/Google TV, Android Auto projection, standalone Auto, Wear OS, iOS/iPadOS, Windows, Linux, macOS, web, Chrome/Firefox extensions, Cloudflare Worker and persistent sync. Distinguish shipped/verified, built only, partial, and planned. List every Kotlin/KMP/Swift/C/JS/TS/CSS/React/egui source path and relevant dependencies and data stores. Map provider/auth flows, playlists, playback, sync, networking, downloads, assets, animations, icons, accessibility, and release packaging. Update FEATURE_MATRIX.csv with an explicit row for every relevant target and scenario.

## Acceptance criteria

No silently unenumerated platform; each mapped feature includes owners, preservation checks, data contracts, status and migration target. Mark unknown, not verified or not yet released accurately. No behavior changes.

## Dependencies

Step 01.

## `rust-anything` integration checkpoint

Read `RUST_ANYTHING_INTEGRATION.md` and the Task 00 upstream evaluation before this scope. Inventory which source files are supported by rust-anything bridges vs KMP/Swift/UI and list safe isolation requirements. Record the eligibility decision and tested evidence in `TOOL_ELIGIBILITY.csv`; keep one canonical Rust core.

## Mandatory execution/continuation contract

1. Read `MASTER_RULES.md`, `AUTORUN_PROTOCOL.md`, repository `AGENTS.md`, current `STATUS.json`/`HANDOFF.md`, original implementation and golden baselines. Do not guess.
2. Enumerate all applicable subtargets in the status ledger. Implement in coherent reviewable changes with relevant tests, data/security checks, parity evidence, and a safe rollback.
3. Prefer the canonical Rust workspace; no duplicative standalone Rust "core" per platform. No changes to the existing UI/UX or unapproved production behavior.
4. Record actual commands/results and mark each subtarget `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. If blocked, record evidence and work on the next independent ready subtarget/task.
5. When this task's gates pass, **immediately start the next numbered task without waiting for a user reply**. Never stop merely to issue a status report; only the whole program may be declared complete. Resume from saved status after session boundaries.
