# MASTER RULES — NUVX RUST-FIRST MIGRATION

## Mission and precedence

Replace redundant non-Rust implementation across the *entire existing* Nuvx ecosystem with one canonical, interconnected Rust codebase, while preserving precisely the original user-facing design, workflow, behavior, accessibility, feature set, data, security boundaries, and compatibility. This is a codebase transformation, **not** a redesign.

**Highest priority:** No data loss, no weakened security, no unauthorized production action, and **no UI/UX regression**. Second priority: keep playback/offline functionality reliable. Third: maximize well-proven Rust sharing and remove duplicated logic. Fourth: reduce measured size, CPU, memory, startup, and energy consumption without degrading outcomes.

A language-purity target may **never** override original UX or platform-mandated integration. The goal is the maximum *feasible* Rust implementation, not a fictional "100% Rust" claim.

## Non-negotiable requirements

1. Begin with a clean inspection of the live repository HEAD. Read `AGENTS.md` and all applicable repository instructions.
2. Respect current source and build compatibility; do not trust stale plans as proof of shipped behavior.
3. One canonical Rust workspace/core owns platform-independent domain rules. All app targets use those crates or a versioned generated contract; no duplicate queue, playlist, sync, metadata, or caching algorithms.
4. Prefer existing proven Rust modules over introducing duplicate frameworks. Desktop already uses egui; only replace it if a working parity prototype and benchmarks justify change.
5. Candidate new native UI: **Slint/Rust**. Candidate web: **Leptos/Rust/WASM**. Candidate Rust services: **Axum/Tokio**, and Cloudflare **workers-rs** when API support is verified. These are *candidates*, not licenses to redesign or break platforms.
6. Keep a small, documented Java/Kotlin, Swift/Objective-C, JavaScript, CSS, C/C++, Gradle, Xcode, or platform configuration adapter wherever platform APIs, browsers, drivers, libraries, licenses, or deployment require it. Do not invent replacements for unsupported official APIs.
7. **Exact UI/UX parity:** original layout, typography/font weight and fallback, colors, spacing, gradients, blur, transparency, artwork, symbols, animations/easing, gestures, focus, navigation, media shortcuts, responsive states, accessibility, RTL, reduce-motion, loading/error/offline states, dark/light behavior, and native platform conventions remain the reference.
8. Save visual baselines before migrations. Compare original and new screen captures at identical device configuration. Investigate every observed mismatch. If a different rendering engine cannot achieve the required fidelity, keep the native renderer and migrate shared logic underneath. Do not claim parity from eyeballing alone.
9. Preserve all application identifiers, signing identity assumptions, user data directories, schemas, encrypted credentials, file naming, storage formats, caches where relevant, media downloads, playlists, account sessions, update channels, protocol and URL compatibility, and existing device pairing semantics. Provide backward-compatible migrations and rollback paths where necessary.
10. Preserve features and device classes: Android phones/tablets, Android TV/Google TV, Android Auto projection, standalone Nuvx Auto, Wear OS, iPhone/iPad, Windows, Linux, macOS, web, Chrome/Firefox extensions, relevant cloud services, and Discord bot. Do **not** claim unreleased platforms already work.
11. Preserve and verify platform media behavior: Android Media3, iOS AVAudioSession/Now Playing, background audio, notification controls, Bluetooth, audio focus, Cast, remote/media keys, Car/TV/Wear interactions, USB DAC/bit-perfect, DSP, codecs, gapless, crossfade, and output routing where present.
12. Every migration must be incremental, testable, benchmarked when applicable, and reversible. Keep previous implementations active until parity and upgrade safety are verified; otherwise use feature-flagged coexistence.
13. Run appropriate build/test targets, clippy, fmt, visual regressions, schema/contract tests, end-to-end device checks when accessible. Record actual commands, environment, artifacts, output, and unknowns.
14. Mark evidence `PASS`, `FAIL`, `BLOCKED`, or `NOT_VERIFIED`. Never claim simulated tests are device tests; never invent sizes, timings, coverage, screenshots, or results.
15. Do not change UI/UX by “improving”, “modernizing”, “cleaning up”, replacing licensed icons, changing system design conventions, or using default Slint widgets.
16. Do not wipe local data, remove historical schema support, publish, deploy, force-push, merge protected branches, change live credentials, or purchase cloud services without express authorization. Build on a dedicated migration branch/worktree if available; use focused commits/PRs where supported.
17. Check GPL/AGPL/LGPL and transitive license compatibility, distribution obligations, and icon/font rights. Never redistribute unauthorized assets. Record a blocker if compliance conflicts with a pixel-identical appearance.
18. Never hardcode secrets, permit insecure TLS bypasses, or allow fail-open proxy/DNS routing where existing settings require fail-closed behavior.
19. Prevent unwinding/panics across FFI, define ownership/lifetimes, handle main-thread UI constraints, run concurrency tests, and keep WASM/native conditional compilation clear.
20. Prefer shallow, auditable crate boundaries; avoid a giant monolithic crate or dozens of tiny redundant crates. Use measured dependencies.

## `rust-anything` mandatory external-tool evaluation

Before implementing migrations, run `tasks/00-*.md`, which requires inspecting https://github.com/erkamyaman/rust-anything and documenting its current language support, license, methods, security hazards and Nuvx applicability. Always read `RUST_ANYTHING_INTEGRATION.md`.

Use its analyze/bridge/record/diff/bench/gate for supported **pure or safely isolated** TS/JS, Java and C units where beneficial. It is not a direct Kotlin/KMP/Swift/Compose/SwiftUI/CSS porting tool and never demonstrates UI/UX parity by itself. Do not run its generated edge-case replay on live data, real providers, user accounts, Cloudflare/Oracle, Discord, or production credentials. If it cannot be safely isolated, do NOT run it; create equivalent local golden tests and document `NOT_APPLICABLE`. Integrate any generated Rust logic into the **same canonical core**, not an independent app-specific crate tree. Keep the original on rollback until all Nuvx test gates pass.

## Scope and specific technology evaluation

- **Single Rust core:** transport-agnostic domain models; queue/playlists; sync and conflict resolution; normalization; data migration schemas; caching policies; pure DSP; common network policy; shared serialization and test vectors.
- **Android:** Kotlin/Java for official Android-facing APIs where required, Rust core for portable logic, optional Slint UI only with exact screenshot/interaction/accessibility parity. Treat phone, TV, Auto, and Wear separately.
- **iOS:** Rust core and appropriate Slint/native rendering only where exact parity; retain minimal Swift/Obj-C for Apple APIs, Keychain, audio sessions, extensions, notifications, and signing/entitlements.
- **Desktop:** retain existing Rust/egui unless swapping is a verified improvement preserving UI/UX. Integrate canonical core.
- **Web:** Rust/WASM and Leptos where they can render the original browser UI/DOM/CSS/effects/accessibility with no degradation; CSS output may still exist because browsers use CSS.
- **Extensions:** Rust/WASM for portable algorithms; minimal JavaScript bootstrap and manifest/service-worker glue remain where required by browser APIs.
- **Services:** Rust where cloud/runtime deployment compatibility, scaling, costs, and protocols are preserved. Cloudflare Worker and Oracle sync deployments remain controlled.

## Full-coverage inventory

Search all top-level code directories and build scripts. Include app, core, native/third-party, plugins, media, web, extensions, deployment services, docs, tests, licenses, and CI. Use a machine-readable inventory plus a human-readable platform/feature matrix. Mark each item `MIGRATED`, `GLUE_REQUIRED`, `RETAINED_WITH_EVIDENCE`, `TODO`, `BLOCKED`, or `NOT_VERIFIED`.

## Acceptance contract

A step can be marked completed only with real code or deliberately sufficient documentation (Steps 01–04), tests, evidence, and a stable reversible migration boundary. Program completion requires all in-scope features/platforms actually verified, zero unexplained regressions, no unresolved migration TODOs that were feasible, and a final report of genuinely required non-Rust glue. If external blockers prevent all-pass, **never claim full completion**; continue all remaining independent work and report exact blockers.
