# Security audit

Status of every security finding, 2026-11-17. "Live" means running for users. The Worker and the Sync server only change for users when they are deployed.

## Fixed

| Finding | Where | Fix | Live? |
|---|---|---|---|
| A rejoin could take over any Listen Together seat, including the host's, with only the device ID | Worker `party.ts` | A rejoin needs that seat's current token (PR 283) | After the next Worker deploy |
| Any member could make every other member's client fetch an arbitrary image URL, exposing their IP addresses | Worker `party.ts` | Only YouTube image hosts are relayed (PR 288) | After the next Worker deploy |
| Presence pings scanned the whole table, so a flood exhausted the Durable Object read quota | Worker `index.ts` | Indexes (PR 305) | After the next Worker deploy |
| Anyone could fill the Sync server's disk, and one export could exhaust its memory | `core/sync` | 20 MB per space; paged export (PR 305) | Not deployed yet |
| The shared canvas passcode let anyone replace any canvas | Worker `canvas.ts` | No overwrite (PR 305) | After the next Worker deploy |
| The desktop updater installed a download after only a size check | `update.rs` | SHA-256 against GitHub's asset digest (PR 305) | 1.0.0 onward |
| Android asked the canvas site about every song played (listening history) | `CommunityCanvas.kt` | Uses the downloaded index (PR 305) | Next Android build |
| Shared diagnostics (Android, iOS, desktop log) kept stream URLs and error traces with the user's IP, signed tokens and cookies | `TrackLog`, `ActionMenus.swift`, `logging.rs` | Redacted when recorded or copied (PR 312) | After PR 312 |
| Downloads could fill the phone | `Downloads.kt` | 500 MB floor (PR 311) | Next Android build |

## Checked and healthy

| Area | Why it is fine |
|---|---|
| Android TV pairing server | Per-session token, constant-time compare, 4 connections, socket deadlines, body and header limits |
| Browser extension | Exact origin allow-list; store builds drop localhost; Firefox relay checks source and origin |
| iOS sign-in | Keychain, `AfterFirstUnlockThisDeviceOnly` (not in iCloud or backups) |
| Discord bot | `yt-dlp` without a shell, `ytsearch1:` prefix, YouTube-only https links, timeout and output cap |
| Android release signing | The release workflow refuses any APK not signed with the pinned `CN=licht` certificate (SHA-256 `833677fb…`) and names the key it found |
| Privacy Connection (Android) | Opt-in and fails closed (PR 282, preserved) |

## Open

- **Worker deploy:** the four Worker fixes above are not live until the Worker is redeployed (owner).
- **Canvas passcode:** it is shared and short, so anyone who has it can add canvases. Uploads also grow the code repository; a separate canvas repository fixes that (owner).
- **Licensing:**
  - GlyphStudio's glyphs are Apple's SF Symbols, used on every platform by the owner's decision.
  - The desktop also bundles Apple's SF Pro Display font files, which Apple's font license does not allow redistributing. Not decided.
- **Not audited yet:** native C/C++ code, Gradle build scripts, the rest of the web app.
