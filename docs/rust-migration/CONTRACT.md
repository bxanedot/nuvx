# Nuvx Rust migration contract (Task 01)

Written 2026-11-19 against `main`. Instruction pack: `docs/rust-migration/agent-pack/` (MASTER_RULES, AUTORUN_PROTOCOL,
RUST_ANYTHING_INTEGRATION). The repository has no `AGENTS.md`; the owner's standing instructions are: commit and push
straight to `main`, no stable `v*` tags or releases without the owner asking, nightlies are automatic.

## Goal, in priority order

1. **No data loss, no weaker security, no unapproved production action, no UI/UX change.**
2. Playback and offline keep working.
3. One canonical Rust core owns platform-independent rules; no second copy of an algorithm.
4. Smaller, faster, lighter, measured, never at the cost of 1 or 2.

"Maximum feasible Rust" is the target, not "100 % Rust". A step that would change what a user sees or does is not
taken; the native renderer stays and only the logic under it moves.

## What exists today (the starting point)

| Area | Language | Size (tracked source) | Notes |
|---|---|---|---|
| Desktop (Windows, Linux, macOS) | Rust + egui | 205 files, ~45k lines | Already Rust; the UI is egui and stays egui. |
| iOS audio engine `app/ios/native-core` | Rust (staticlib/cdylib, UniFFI) | 86 files, ~35k lines | Already Rust, used from Swift. |
| Android phone/tablet `app/android/mobile` | Kotlin + Compose | 637 files, ~127k lines | Media3, Cast, Auto browse, Wear bridge. |
| Android TV + Auto `app/android/tv` | Kotlin + Compose for TV | 44 files, ~11k lines | |
| Wear OS `app/android/wearos` | Kotlin | 15 files, ~3k lines | |
| Shared Kotlin `core/shared`, `core/sharedUi` | Kotlin Multiplatform | 282 files, ~45k lines | Models, repositories, shared Compose UI. |
| iOS app `app/ios`, `core/apple` | Swift / SwiftUI | 291 files, ~73k lines | |
| Web player `app/web/app` | TypeScript + React + CSS | 210 files, ~25k lines | |
| Browser extension `app/web/extension` | TypeScript | 16 files, ~2.5k lines | Chrome + Firefox. |
| Listen Together worker `core/worker` | TypeScript (Cloudflare) | 12 files, ~1.8k lines | Durable Objects. |
| Sync server `core/sync` | TypeScript (Node + SQLite) | 2 files, ~0.4k lines | |
| Discord bot `core/discord-bot` | JavaScript (ESM) | 3 source files | |
| Native DSP `core/native` | C++ | 11 files, ~2.5k lines | Analyzer, loop mix. |
| Website `core/website` | HTML/CSS | 33 files | Static. |

Rust today: two separate trees (desktop app crate, iOS native-core). Task 05 decides how they become one workspace.

## Definitions

- **Portable logic**: anything that does not touch an OS, UI toolkit or browser API: models, parsing, queue,
  playlists, sync and conflict rules, crypto envelopes, DSP math, caching policy, validation. **Target: Rust core.**
- **Platform glue**: the thinnest code an OS or browser requires: Android services/Media3/Cast/Auto/Wear APIs,
  iOS AVAudioSession/Now Playing/Keychain/entitlements, browser manifests and service workers, Cloudflare runtime
  bindings. **Stays native**, documented in `HANDOFF.md` as required glue.
- **Native UI**: Compose, Compose for TV, Wear Compose, SwiftUI, egui. **Stays as is** unless a Rust renderer
  reaches pixel, interaction and accessibility parity in recorded side-by-side tests (MASTER_RULES 7–8).
- **Web CSS/DOM**: stays CSS/DOM; only TypeScript logic under it may move to Rust/WASM.
- **Unsupported path**: a target where Rust cannot meet parity (e.g. a platform API with no binding). Recorded as
  `BLOCKED` with evidence, never silently skipped.

## Things that must not change without a tested, authorized path

- Application IDs: Android `com.licht.nuvx` (dev `com.licht.nuvx.dev`) for phone, TV and Wear; iOS
  `com.licht.nuvx` and `com.licht.nuvx.widget`; desktop app id `nuvx`.
- Signing keys and signing configuration, update channels (stable `v*`, `nightly.N.M` tags and their comparison
  rules in `app/desktop/src/update.rs` and `AppUpdateChecker.kt`).
- User data locations and formats: Android SharedPreferences names (`nuvx_*`), Room/files under the app's data
  directory, desktop eframe storage keys and `lan_sync.json`, iOS containers; downloads (`files/downloads/*`),
  playlists, sessions, encrypted credentials, the LAN Sync log and keys.
- Wire protocols: LAN Sync (`/lan/hello`, pairing, `/lan/v1` sealed envelopes, `/v1/*` routes), Listen Together,
  sync server API, Discord RPC.
- Production deployments (Cloudflare worker, Oracle sync server, website) and published releases.

## Required tests per change

Existing suites keep passing: `cargo test` (desktop, native-core), Gradle unit tests and builds for mobile/TV/Wear,
`node --test` (sync), worker and web checks, the Apple CI workflow. A ported unit additionally needs golden vectors
shared by the old and new implementation, run on both, before the old one is removed. Device behaviour is checked
on a device or emulator; a missing device check is `NOT_VERIFIED`, never `PASS`.

## Status words

`PASS` (verified with recorded evidence), `FAIL`, `BLOCKED` (with the concrete blocker), `NOT_VERIFIED`, `TODO`.
Inventory rows add `MIGRATED`, `GLUE_REQUIRED`, `RETAINED_WITH_EVIDENCE`.

## Blockers

One row per blocker in `BLOCKERS.csv`: what, why, evidence, what would unblock it. Work moves on to the next
independent task.

## `rust-anything`

Policy and candidates: `RUST_ANYTHING_EVALUATION.md`, `TOOL_ELIGIBILITY.csv`. It is used only on isolated pure
TypeScript units, never on live services or user data.
