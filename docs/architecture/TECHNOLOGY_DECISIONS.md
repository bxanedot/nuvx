# Technology decisions

Short records of decisions taken, newest first. Each says what was decided, why, and what would change it.

## 2026-11-19: One shared Rust core for rules every app must agree on; Kotlin Multiplatform stays for UI-side code; React Native not adopted

**For the maintainer to approve or reverse.** Nothing here removes an app or changes what a user sees.

- **Decision:**
  1. Rules that must give the same answer on every device live once, in `core/rust/nuvx-core`, with their expected results in `core/contracts/*.json` that the Kotlin tests also read. Today: release order and updates, stream recovery, volume normalization, lyrics parsing (LRC, TTML, QRC), LAN Sync crypto and replay, Nuvx Connect formats, the equalizer curves, playlist files, and the data model.
  2. Kotlin Multiplatform (`core/shared`, `core/sharedUi`, the iOS `NuvxShared` framework) stays for models, repositories and Compose UI, where it already works.
  3. React Native is not adopted.
  4. No app is rewritten. A rule moves only when a shared test proves the old and new give identical results.
- **Evidence (measured 2026-11-19):**
  - The same rules existed twice or three times (Rust desktop, Kotlin Android, Swift/Rust iOS), and in places had drifted: the desktop skipped a failed song at once while Android retried twice; nightly builds never moved on to the next stable release on either app.
  - After the move the desktop's tests were unchanged in number (282) and all pass; the iOS audio engine produces the same equalizer curves for all 242 tone settings and 16 presets (within 0.0001 dB) before it switched to the core's.
  - Code today: Kotlin ~185k lines, Swift ~73k, Rust ~80k, TypeScript ~30k. The UI is in Compose, SwiftUI, egui and React. The brief makes the current look immutable.
- **Why not React Native:** it would add a JavaScript runtime to the phone and iPhone apps and mean rewriting about 200k lines of working Compose and SwiftUI with pixel parity, while still not covering desktop, TV, Wear or Auto. It solves a problem (one UI codebase) the owner has said he does not want solved at the cost of the current UI.
- **Why not "everything in Rust":** Compose, SwiftUI, Media3, Cast and Android Auto have no Rust equivalents; Rust cannot reproduce them with the required parity. Rust owns the logic, the platforms own the screens.
- **How Android and iOS will use the core:** for now, through the shared test cases only (no new runtime piece). When a rule needs to be *executed* from Kotlin (e.g. LAN Sync crypto), UniFFI for iOS and JNI for Android are the candidates, added one rule at a time and only if a shared test is not enough.
- **Revisit if:** a rule drifts again despite shared cases, a Kotlin port of a rule turns out harder to keep in step than calling the core, or a Rust UI reaches pixel, input and accessibility parity in recorded side-by-side tests.

## 2026-11-17: SFStudio SF Symbols on every platform

- **Decision:** Compose surfaces (Android phone, TV, Wear, Auto) draw symbols with SFStudio v2 through one registry (`NuvxGlyph`/`NuvxSymbol`). iOS uses Apple's own SF Symbols with the matching SwiftUI transition. The desktop and web keep their own vector glyphs with the same meanings and motion.
- **Why:** owner's decision, for one icon language. SFStudio has no Rust or web API, so those use equivalents.
- **Risk accepted by the owner:** Apple licenses SF Symbols for Apple platforms.
- **Revisit if:** Apple objects, or a store requires it. The registry makes a swap to another icon set a contained change.

## 2026-11-17: Keep Listen Together on the Cloudflare Worker; Sync on the Oracle server

- **Decision:** real-time sessions stay on the existing Worker (Durable Objects). Persistent sync (spaces, pairing, operation log) is a small dependency-free Node service with SQLite on the owner's 1 GB Oracle VM, deployed manually from GitHub Actions.
- **Why:** the Worker already works and holds no long-term data. Sync needs durable storage, and a single SQLite file fits the server's memory.
- **Revisit if:** sync use outgrows one small VM.

## 2026-11-17: Per-device equalizer without a switch

- **Decision:** each audio output remembers its own EQ automatically. An output without saved settings inherits the current ones.
- **Why:** nothing changes for someone who never tunes per device. No new setting or translations were needed.
- **Revisit if:** users ask for one EQ everywhere.

## 2026-11-17: Redact diagnostics where they are recorded

- **Decision:** log lines are redacted as they are recorded (Android, desktop) or copied (iOS), not when a report is assembled.
- **Why:** any later path, such as a new export button or a crash upload, gets clean data for free.

## 2026-11-17: Desktop stays Rust + egui; macOS SwiftUI shell not adopted

- **Decision:** no frontend migration.
- **Why:** the brief requires measured evidence (start-up, memory, media keys) before a migration, and that needs a Mac this project does not have in CI or locally.
- **Revisit with:** those measurements.
