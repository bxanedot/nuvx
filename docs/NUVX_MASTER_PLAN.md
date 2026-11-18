# Nuvx master plan

The one handoff document for this programme. The owner's standing instructions are in [`NUVX_MASTER_PROMPT.md`](NUVX_MASTER_PROMPT.md); every session follows them. Update it in the same pull request as the work it describes. Last updated 2026-11-18. Read-only references and the per-issue matrix live in [`audits/REFERENCE_REPOS_AUDIT.md`](audits/REFERENCE_REPOS_AUDIT.md). The repository audit lives in [`audits/FULL_REFACTOR_AUDIT.md`](audits/FULL_REFACTOR_AUDIT.md), security in [`audits/SECURITY_AUDIT.md`](audits/SECURITY_AUDIT.md). Platform support: [`architecture/PLATFORM_AND_UX_MATRIX.md`](architecture/PLATFORM_AND_UX_MATRIX.md). Decisions: [`architecture/TECHNOLOGY_DECISIONS.md`](architecture/TECHNOLOGY_DECISIONS.md). Tests: [`QUALITY_AND_TESTING.md`](QUALITY_AND_TESTING.md). Releases: [`RELEASE_READINESS.md`](RELEASE_READINESS.md).

## Released

**nuvx 1.0.0** (licht line), published 2026-11-17 from `6d3126e6`. It ships 12 files: phone, TV, Auto and Wear APKs signed with the `CN=licht` key (SHA-256 `833677fb…`), the Windows installer, the Linux `.tar.gz`, one universal macOS `.dmg`, the Chrome and Firefox extensions, and `SHA256SUMS`. The website's download routes, old `mac-arm`, `mac-intel` and `foss` links included, resolve to these files; this was checked by running `core/website/api/download.js` against the published asset list. All older releases are pre-releases named "old nuvx build …", and the old 1.0.0 is at `old-nuvx-v1.0.0`.

**nuvx 1.0.1** and **1.0.2** (Android version code 3) are published; 1.0.2 is the latest. Tag `v1.0.3` (Tor exit switching, code 4) exists on `b45a9250`, but its release run was cancelled and nothing was published. The owner asked for no new releases until further notice (2026-11-18); later work collects under "Unreleased" in `CHANGELOG.md`.

## Session handoff (2026-11-17, Phase 0 baseline)

- **Repository:** `bxanedot/nuvx` redirects to `lylicht/nuvx`; SFStudio is now `lylicht/SFStudio`. They are the same repositories.
- **Local checkouts:** the shared object store under `Codex/2026-11-15/hi-2/work/nuvx/.git` is corrupt (missing objects, so `git fetch` and `git log` fail). Work from a fresh clone instead (this session used `Codex/nuvx-phase0/nuvx`).
- **Already in place, so not redone:** the Oracle deploy workflow (`sync-deploy.yml`, manual only, never run against the server), the Sync server (`core/sync`), the SFStudio v2 icon registry, and the Ponytail plugin.

### Test baseline on the owner's Windows machine, 2026-11-17, `main` at `9abbddba`

| Suite | Result |
|---|---|
| Listen Together Worker (`npm test`, `tsc`) | 29 passed, type-check clean |
| Sync server | 9 passed |
| Website | 62 passed |
| Web app + extension (`pnpm -r test`), web type-check | 245 + 2 passed, type-check clean |
| Shared Kotlin (`:shared:jvmTest :sharedUi:jvmTest`) | 139 + 53 passed |
| Android phone unit tests | Not run here: no Android platform or build-tools in the local SDK. Run by the new Android check in CI. |
| Desktop (`cargo test`) | Not run here: the local Rust toolchain does not start (`rustc` exits with STATUS_DLL_NOT_FOUND). It runs in CI on macOS. |
| iOS / Swift | Not run: needs macOS (CI "Apple builds") |

### Top measured risks

1. **Most tests did not run on pull requests.** The 1,378 Android unit tests ran only after merge, inside the release build. The 247 web tests and the web type-check never ran in CI. *Addressed by the "Android checks" and "Web checks" workflows (PR in "Open").*
2. **Desktop tests run in CI only on macOS.** Windows- and Linux-only code paths (audio output, tray, updater, installer) compile in the release but were never unit-tested in CI, including the Windows and Linux saved-login vault tests. *Addressed by "Desktop checks" (PR in "Open").*
3. **No device or emulator tests.** TV, Wear, nuvx Auto and Android Auto are compile-only; `androidTest` needs an emulator.
4. **Server fixes may not be live.** The Listen Together workflow only tests; it does not deploy. Whether the Worker was redeployed after PRs 283, 288 and 305 was not verified (owner item 3).
5. **Licensing:** the desktop still bundles Apple's SF Pro Display fonts (owner item 5).

## Merged on 2026-11-17

| PR | What |
|---|---|
| 283, 288 | Listen Together: a rejoin needs the seat's token; only YouTube image hosts are relayed as thumbnails |
| 284 | Nuvx Sync API and the manual Oracle deployment workflow (not deployed) |
| 285, 289, 301 | Android: external shuffle, reduced motion for symbol animations, nuvx playlist menu |
| 286, 303 | This plan and the audit matrix; Ponytail enabled |
| 291, 296, 299, 304 | Dependency updates; 304 also carries the licht 1.0.0 line, the Nightly updates switch and the launch-crash fix |
| 305 | Audit fixes: presence indexes, Sync size cap and paged export, canvas no-overwrite, updater SHA-256 check, private canvas lookup, download counter, the old-releases workflow removed; desktop quality badge read from the file |
| 306 | Android downloads resume after the app is killed |
| 307 | Android holds a Wi-Fi lock while streaming |
| 308 | Audit notes: desktop audio, web player, iOS sign-in storage and the Discord bot are healthy |
| 309 | Android backups include nuvx playlists |
| 310 | Desktop retries a song once on a network blip |
| 311 | Android refuses to download with under 500 MB free |
| 312 | SFStudio v2 icon registry (`NuvxGlyph`/`NuvxSymbol`) with animated play/pause, download and cast states on Android, the same morph on iOS and web; per-device equalizer; undo after deleting a nuvx playlist; redacted diagnostics on Android, iOS and desktop |
| 315 | Android, shared Kotlin and web tests run on every pull request that touches them |
| 316 | Desktop tests run on Linux and Windows for every pull request that touches `app/desktop` |
| 318 | yt-dlp on Windows with a bad PATH folder: a PATH folder Windows cannot resolve no longer skips every song that needs yt-dlp (BitChord-PC PR 13 equivalent). |
| 319 | Sync: a 10 GB whole-server cap (`507 server_full`) so free space creation cannot fill the VM disk; last-seen written at most once a minute. |
| 320 | Worker: `counts()` reuses its answer for 5 s (cleared on every ping and party update), so a stats or badge request loop no longer runs DELETEs on the one Presence object. Needs a Worker redeploy (owner item 3). |
| 321 | Sync transport client (`core/shared/.../data/sync/SyncClient.kt`): push batches under server limits, pull pages, retry-or-stop results, bounded backoff. Not wired into any app yet. |
| 322 | Android: export a nuvx playlist as CSV (formula-safe), label in all 20 locales. |
| 317 | Sync pairing: the first approval of a pairing code wins (a second space got to overwrite it and take the device), and the 20-device cap is checked again at claim. |
| 313 | Icon render and animation-state tests; web signature code in an isolated worker; JitPack limited to `com.github.*`; shared sync replay rules with a fixture; crossfade timing tests; Android playlist export as M3U; security, platform, testing, release and decision docs |

## Open

- **Orbot hardening** (branch `claude/brave-bohr-qzzh9m`, commit `fd7a743d`): all Android unit tests pass; no PR yet. Do not redo it.
- Leftover branches of closed Dependabot PRs (290, 292, 294 and others; the updates went in through PR 304): `reqwest-0.13.5`, `souvlaki-0.9.3`, `tokio-1.54.1`, `window-vibrancy-0.8.1`, `actions/setup-node-7`. Safe to delete once confirmed.
- **Dependabot alert 1 (high, `picomatch` ReDoS, `app/web`)**: comes only from build and test tools (vite, vitest, wxt, unimport, tinyglobby), not the shipped web app or extension. No patched version yet; update when one exists.

### Session queue (2026-11-17, second session), from the master prompt §10 and §12

| # | Phase | Item | Result |
|---|---|---|---|
| 1 | 1 Stabilize | yt-dlp PATH on Windows | PR 318 |
| 2 | 1 Stabilize | Android gapless (Metrolist PR 4814 row) | Not affected: with crossfade and Smart Fade off, `planAutoTransition` returns at `configuredFadeMs() <= 0`, so ExoPlayer advances inside its own playlist (its gapless path). No second player is involved. |
| 3 | 2 Simplify | Ponytail audit of `core/sync` and `core/worker` | 4 findings; 1 and 2 fixed (PRs 319, 320). Left: per-push quota scan reads the whole space (running totals on `spaces` later); Presence object has no unit-test harness. |
| 4 | 4 Ecosystem | Sync transport client | PR 321 |
| 5 | 5 Features | Playlist CSV export | PR 322 |

Next session, in order: wire `SyncClient` into Android behind an off-by-default switch with a persisted outbox (needs owner item 1 for an end-to-end test); then the emulator smoke test (risk 3); then the remaining audit rows (Convx 153 stale local library, Automix loop timeouts).

## Done on main, 2026-11-18

Committed straight to `main` (owner's choice), each with its tests; Android items were also checked on a Galaxy phone with the dev build.

| Commit | What |
| --- | --- |
| `e8b22767`, `37c6d4a6` | LAN Sync: nuvx playlists between Android and desktop over local Wi-Fi, encrypted, with a 6-digit match code |
| `2714731e` … `3d9a22a2`, `1064bb0a`, `e3890901` | Android Local SOCKS5 mode, fail-closed routing, Orbot/SOCKS latency tolerance, Tor exit switching (up to four exits) |
| `42b9a7f6`, `663ac66e`, `1e047fb5` | Desktop custom proxies resolve names remotely and fail closed; accurate proxy guidance per OS |
| `40599b9c` | Desktop Community and Spotify Canvas in Android's source order, capped clip cache |
| `a0a529e9`, `94c7d5b3` | Android restores the playing track after a kill past the saved window; on-device test that the saved queue survives a fresh read from disk |
| `14d46a6d`, `1c250405` | Android crossfade stops when a call takes audio focus; the chosen output survives a replayed device list |
| `135b60f8` | Desktop plays a YouTube video's original audio track instead of the first dub listed (Task 61) |
| `3a5dfac1` | Android explains, once, that songs cannot play when the network blocks googlevideo.com (DNS that only proxies YouTube's pages) |
| `9a05101b` | Real code-scanning findings fixed; false alarms closed |
| `12c58526` | Android: import a playlist from an M3U or CSV file (round-trip tested with the export) |
| `3bdf1d66` | Android: opt-in Settings → Miscellaneous → Copy diagnostics (redacted, clipboard only) |
| `2e25c37b` | Android: optional downloads storage limit (off by default) |
| `e1deefa1` | Android: songs can be removed from nuvx playlists again, with Undo |
| `dd4b13d0` | Android, signed out: New playlist and Add to playlist work on this device |

## Checked and found healthy (2026-11-17, second session)

- Desktop WebView reuse (reference audit, Metrolist PR 4615): the lyrics check and sign-in windows run in a child process that exits, so their WebViews cannot pile up.

## Waiting on the owner

1. **Oracle server preparation** (admin, once): enable lingering for `nuvxdeploy`, install Caddy, open ports 80 and 443. Steps in `ORACLE_DEPLOYMENT.md`. Then the workflow's `check` action.
2. **Sync host name**, and whether off-server backups may run on a schedule. The `backup` action needs a `NUVX_BACKUP_PASSPHRASE` secret.
3. **Redeploy the Cloudflare Worker.** The Listen Together fixes (283, 288) and the presence indexes (305) are not live until then.
4. **SFStudio JitPack build:** `lylicht/SFStudio` tag `v1.0.1` exists, but JitPack has shown it as "Building" since 2026-11-17 (1.0.0 built fine). Once it builds, nuvx can switch to `com.github.lylicht:SFStudio`; until then it uses the `bxanedot` build of v2.0.0.
5. **Licensing:**
   - The owner chose SFStudio's SF Symbols on every platform, knowing Apple licenses them for Apple platforms.
   - Separately, the desktop bundles Apple's SF Pro Display font files, which Apple's font license does not allow redistributing. That one is not decided. See `architecture/ICONS.md`.
6. **Canvas repository** (optional): create a separate repository for community canvases so uploads stop growing the code repository.

## Next, in order

1. **Remaining audit:**
   - Android crossfade internals, the rest of the iOS app, the desktop UI, the rest of the web app, native C/C++ and Gradle scripts;
   - the "Investigate" rows of the reference audit: gapless transitions, desktop WebView reuse, MPRIS, PipeWire sample rate.
2. **Sync beyond Android:** server Sync is on Android (opt-in, PR 326) and LAN Sync covers Android ↔ desktop playlists. Left: server Sync on desktop, iOS and web; it needs the Oracle server (owner item 1) to run for real.
3. **Nuvx Connect** on top of QR pairing: one playback owner, remote control, handoff.
4. **Smart Offline:** done on Android (resume after a kill, 500 MB floor, Wi-Fi-only, auto-sync of downloaded playlists, optional storage limit). Left: the same on iOS and desktop.
5. **Remaining features:**
   - Privacy & Data Center: one screen for devices, sync, diagnostics and export (Android has the parts in separate places);
   - playlist import/export and diagnostics: done on Android; iOS and desktop left; import does not yet search for entries without a YouTube link;
   - playlist history: undo for delete and song removal on Android; no version-restore screen yet.
6. **Visual regression tests** (Roborazzi or Paparazzi) for the icon registry and the main screens.

## Decisions that need evidence and approval first

- **macOS: SwiftUI shell or the current Rust app.** `core/apple` already holds SwiftUI Mac views while the shipped `.dmg` is the Rust app. Measuring start-up, memory and media keys needs a Mac, which the cloud container is not.
- **Shared Rust core versus Kotlin Multiplatform versus React Native.** No prototype yet. Kotlin Multiplatform already carries shared code on Android and iOS.
- **tvOS, Android Automotive, CarPlay.** Not started. CarPlay needs an Apple entitlement nuvx does not hold.
- **Design system and per-device-class specifications.** Not started; representative screens need the maintainer's sign-off first.

No major migration is started without approval.
