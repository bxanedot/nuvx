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

## Android downloads and playback (read 2026-11-17)

| Area | Result |
|---|---|
| Range download (`Downloader.kt`) | Healthy: checks every Content-Range, total size and short reads; re-resolves once on 403/404/410 |
| Failure cleanup (`DownloadStore.kt`) | Healthy on errors: a pending MediaStore row or `.part` file is aborted. **Fixed:** a download killed with the app left its hidden `.part` file forever; the next download now removes `.part` files untouched for an hour |
| Queue durability | **Gap:** the queue lives in memory (`DownloadSession`, `START_NOT_STICKY`); if Android kills the app, queued songs must be requested again. Belongs to Smart Offline (persist the queue, resume at start) |
| Audio offload | Not enabled, so the offload freeze seen in Metrolist #4763 cannot happen |
| Skip silence | Off by default; uses Media3's default threshold. Whether it trims quiet intros (vivi #1055) needs listening on a device |
| Desktop quality badge | **Fixed:** Lossless and Hi-Res came from the addon's label; codec and bit depth now come from the file's first bytes |

## Not read yet

Android playback service internals (crossfade, focus, output routing), the iOS app, the desktop audio engine and UI, the web player, the Discord bot, native C/C++ and Gradle build scripts. These hold most of the code; playback and download reliability need device testing. Next audits go one subsystem at a time, in that order.
