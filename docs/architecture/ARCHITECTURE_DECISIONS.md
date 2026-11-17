# Architecture decisions

Short records of decisions taken, newest first. Each says what was decided, why, and what would change it.

## 2026-11-17: GlyphStudio SF Symbols on every platform

- **Decision:** Compose surfaces (Android phone, TV, Wear, Auto) draw symbols with GlyphStudio v2 through one registry (`NuvxGlyph`/`NuvxSymbol`). iOS uses Apple's own SF Symbols with the matching SwiftUI transition. The desktop and web keep their own vector glyphs with the same meanings and motion.
- **Why:** owner's decision, for one icon language. GlyphStudio has no Rust or web API, so those use equivalents.
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
