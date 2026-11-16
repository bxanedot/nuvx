# Nuvx modernization: state and next steps

The durable handoff for this programme. Update it in the same PR as the work it describes. Last updated 2026-11-16.

## Done

| What | Where | Verified by |
|---|---|---|
| Listen Together seat takeover closed | PR 283 | 28 worker tests |
| Android external shuffle routed to the app's shuffle | PR 285 | 1,343 Android tests; no real controller |
| Nuvx Sync API with QR pairing, revocation, export, delete | PR 284, `core/sync` | 8 API tests, HTTP smoke test |
| Manual, approval-gated Oracle deployment with snapshot, health check and rollback | PR 284 | Script exercised locally; never run on the server |
| Search dock keyboard inset (issue 279) | `main` | 1,343 Android tests |
| Desktop Privacy Connection (Tor) | `main` | 219 desktop tests; no real Tor |
| Reference repository matrix | `docs/audits/REFERENCE_REPOS_AUDIT.md` | Code reads noted per row |

Baseline on `main` at `e1cafdb`: desktop 219 pass, 27 ignored. Android phone 1,343 pass, 1 skipped. Web and shared Kotlin suites exit clean. Worker 27 pass.

Ponytail 5.3.3 is installed and its rules were applied to every change above. The full `/ponytail-audit` of the repository has **not** been run; only the Worker, the sync service and the touched Android and desktop files were audited.

## Waiting on the owner

1. Approve and run the one-time server steps in `docs/ORACLE_DEPLOYMENT.md`, then the workflow's `check` action.
2. Choose the sync host name.
3. Redeploy the Worker after PR 283 merges; the fix is not live until then.
4. Decide whether off-server backups may run on a schedule.

## Next, in order

1. Read the "Investigate" rows of the reference matrix: download durability, gapless, desktop WebView reuse, MPRIS, PipeWire rate.
2. Run the full Ponytail audit per subsystem and record it in `docs/audits/FULL_REFACTOR_AUDIT.md`. Size for planning: web about 590k lines including vendored code, desktop 187k, iOS 140k, Android 138k, Apple core 78k.
3. Operation kinds and payload schemas for playlists, favorites and settings, with one conformance fixture every client replays.
4. First sync client. Android or web, behind an off-by-default setting.
5. Nuvx Connect on top of pairing: one playback owner, remote control, handoff.
6. GlyphStudio: read `bxanedot/glyphstudio`, then write the icon registry. Android already uses its Compose runtime; the other platforms need adapters.
7. Import and export (M3U, JSON, CSV, Nuvx backup), playlist history, Smart Offline, audio profiles, diagnostics export.

## Decisions that need evidence first

- **macOS: SwiftUI or the Rust desktop app.** `core/apple` already holds SwiftUI Mac views (`MacShell`, `MacMiniPlayer`), and the shipped `.dmg` comes from the Rust app. No measurement is possible from a Linux container. Needs a Mac to compare start-up, memory and media-key behavior before any migration.
- **tvOS, CarPlay, Android Automotive.** Not started. CarPlay needs an Apple entitlement nuvx does not have.
- **Device-class design specifications.** Not started. Screenshots of every platform are needed first.

## Not started

Everything in the brief not listed above, including the repository-wide refactor, performance baselines, visual regression tests, SBOM and release signing work.
