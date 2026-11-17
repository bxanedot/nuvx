# Full repository audit

Ponytail audit, run 2026-11-17 on `main` at `f1e655b1` (licht 1.0.0). Assumed load: a small public community (hundreds to a few thousand installs) with the Worker and the Sync API open to anyone on the internet.

## Read in depth

| Area | Result |
|---|---|
| Worker: Listen Together, presence, community stats, canvas upload, invite page | Findings 1, 3, 6 |
| Sync API (`core/sync`) | Finding 2 |
| Desktop updater (`app/desktop/src/update.rs`) | Finding 4 |
| Android TV pairing server (`TvAuthServer.kt`) | Healthy: pairing token, connection cap, socket deadlines, body and header limits |
| Browser extension messaging | Healthy: exact origin allow-list; release builds drop localhost |
| Android community canvas lookup | Finding 5 |
| Release workflows | Finding 7 |

## Findings and status

| # | Finding | Fix | Status |
|---|---|---|---|
| 1 | Presence pings scanned the whole installs table (no index); about 5.7 M row reads a day at 100 open apps | Indexes on `last_ms` and `source_hash` | Fixed |
| 2 | Anyone could fill the Sync disk (200k ops x 32 KB per space) and an export loaded a full space into memory | 20 MB per space; export pages like a pull | Fixed |
| 3 | The shared canvas passcode let anyone replace any canvas; every upload grows the code repository | Uploads never replace an existing canvas | Fixed. Moving canvases to their own repository waits on the owner creating it |
| 4 | Desktop updater installed a download after only a size check | Checks GitHub's SHA-256 `digest` of the asset | Fixed |
| 5 | Android asked `canvas.nuvx.lol` about every song played (leaks listening history, adds latency) | Uses the already downloaded index | Fixed |
| 6 | Download counter skipped nuvx Auto and browser-extension packages | Pattern includes them | Fixed |
| 7 | `mark-old-releases` workflow: its tag step cannot work with the workflow token, and a rerun would mark the new 1.0.0 as old | Workflow deleted; its job is done | Fixed |

## Not read yet

Android playback and download services, the iOS app, the desktop audio engine and UI, the web player, the Discord bot, native C/C++ and Gradle build scripts. These hold most of the code; playback and download reliability need device testing. Next audits go one subsystem at a time, in that order.
