# Nuvx master plan

The one handoff document for this programme. Update it in the same pull request as the work it describes. Last updated 2026-11-17. Read-only references and the per-issue matrix live in [`audits/REFERENCE_REPOS_AUDIT.md`](audits/REFERENCE_REPOS_AUDIT.md). The repository audit lives in [`audits/FULL_REFACTOR_AUDIT.md`](audits/FULL_REFACTOR_AUDIT.md), security in [`audits/SECURITY_AUDIT.md`](audits/SECURITY_AUDIT.md). Platform support: [`architecture/PLATFORM_AND_UX_MATRIX.md`](architecture/PLATFORM_AND_UX_MATRIX.md). Decisions: [`architecture/TECHNOLOGY_DECISIONS.md`](architecture/TECHNOLOGY_DECISIONS.md). Tests: [`QUALITY_AND_TESTING.md`](QUALITY_AND_TESTING.md). Releases: [`RELEASE_READINESS.md`](RELEASE_READINESS.md).

## Released

**nuvx 1.0.0** (licht line), published 2026-11-17 from `6d3126e6`. It ships 12 files: phone, TV, Auto and Wear APKs signed with the `CN=licht` key (SHA-256 `833677fb…`), the Windows installer, the Linux `.tar.gz`, one universal macOS `.dmg`, the Chrome and Firefox extensions, and `SHA256SUMS`. The website's download routes, old `mac-arm`, `mac-intel` and `foss` links included, resolve to these files; this was checked by running `core/website/api/download.js` against the published asset list. All older releases are pre-releases named "old nuvx build …", and the old 1.0.0 is at `old-nuvx-v1.0.0`.

**nuvx 1.0.1** is being built: tag `v1.0.1` on `9abbddba` (PR 314), draft release, "Nuvx releases" run 44139030811. Apple builds follow it.

## Session handoff (2026-11-17, Phase 0 baseline)

- **Repository:** `bxanedot/nuvx` and `bxanedot/GlyphStudio` now redirect to `lylicht/nuvx` and `lylicht/GlyphStudio`. They are the same repositories.
- **Local checkouts:** the shared object store under `Codex/2026-11-15/hi-2/work/nuvx/.git` is corrupt (missing objects, so `git fetch` and `git log` fail). Work from a fresh clone instead (this session used `Codex/nuvx-phase0/nuvx`).
- **Already in place, so not redone:** the Oracle deploy workflow (`sync-deploy.yml`, manual only, never run against the server), the Sync server (`core/sync`), the GlyphStudio v2 icon registry, and the Ponytail plugin.

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
2. **Desktop tests run in CI only on macOS.** Windows- and Linux-only code paths (audio output, tray, updater, installer) compile in the release but are never unit-tested in CI.
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
| 312 | GlyphStudio v2 icon registry (`NuvxGlyph`/`NuvxSymbol`) with animated play/pause, download and cast states on Android, the same morph on iOS and web; per-device equalizer; undo after deleting a nuvx playlist; redacted diagnostics on Android, iOS and desktop |
| 315 | Android, shared Kotlin and web tests run on pull requests (risk 1) |
| 313 | Icon render and animation-state tests; web signature code in an isolated worker; JitPack limited to `com.github.*`; shared sync replay rules with a fixture; crossfade timing tests; Android playlist export as M3U; security, platform, testing, release and decision docs |

## Open

- **Desktop tests on Linux and Windows** (PR 316, branch `claude/desktop-checks-win-linux`): next item 0.
- **Sync pairing hardening** (branch `claude/sync-pairing-first-approval`): the first approval of a pairing code wins (a second space got to overwrite it and take the device), and the 20-device cap is checked again at claim. 11 Sync tests pass.
- **Orbot hardening** (branch `claude/brave-bohr-qzzh9m`, commit `fd7a743d`): all Android unit tests pass; no PR yet. Do not redo it.
- Leftover branches of closed Dependabot PRs (290, 292, 294 and others; the updates went in through PR 304): `reqwest-0.13.5`, `souvlaki-0.9.3`, `tokio-1.54.1`, `window-vibrancy-0.8.1`, `actions/setup-node-7`. Safe to delete once confirmed.

## Checked and found healthy (2026-11-17, second session)

- Desktop WebView reuse (reference audit, Metrolist PR 4615): the lyrics check and sign-in windows run in a child process that exits, so their WebViews cannot pile up.

## Waiting on the owner

1. **Oracle server preparation** (admin, once): enable lingering for `nuvxdeploy`, install Caddy, open ports 80 and 443. Steps in `ORACLE_DEPLOYMENT.md`. Then the workflow's `check` action.
2. **Sync host name**, and whether off-server backups may run on a schedule. The `backup` action needs a `NUVX_BACKUP_PASSPHRASE` secret.
3. **Redeploy the Cloudflare Worker.** The Listen Together fixes (283, 288) and the presence indexes (305) are not live until then.
4. **GlyphStudio `lylicht` JitPack build:** v2.0.0 ran out of memory on JitPack. GlyphStudio needs `org.gradle.jvmargs=-Xmx4g` in `gradle.properties` and a new tag. Until then nuvx uses the `bxanedot` build of v2.0.0.
5. **Licensing:**
   - The owner chose GlyphStudio's SF Symbols on every platform, knowing Apple licenses them for Apple platforms.
   - Separately, the desktop bundles Apple's SF Pro Display font files, which Apple's font license does not allow redistributing. That one is not decided. See `architecture/ICONS.md`.
6. **Canvas repository** (optional): create a separate repository for community canvases so uploads stop growing the code repository.

## Next, in order

0. **Desktop tests on Linux and Windows in CI** (risk 2), then a scheduled emulator smoke test for the phone app (risk 3).
1. **Remaining audit:**
   - Android crossfade internals, the rest of the iOS app, the desktop UI, the rest of the web app, native C/C++ and Gradle scripts;
   - the "Investigate" rows of the reference audit: gapless transitions, desktop WebView reuse, MPRIS, PipeWire sample rate.
2. **First Sync client**, off by default. Done: the operation kinds and replay rules (`SyncReplay.kt`) and the fixture every client must reproduce (`core/sync/fixtures/replay-v1.json`). Left: the outbox and transport. It needs the Oracle server (owner item 1) to test end to end.
3. **Nuvx Connect** on top of QR pairing: one playback owner, remote control, handoff.
4. **Smart Offline:**
   - done: resume after a kill, a 500 MB floor;
   - left: a user-set quota and auto-download of chosen playlists.
5. **Remaining features:**
   - Privacy & Data Center (devices and sessions once Sync exists);
   - playlist export: done on Android as M3U (playlist menu → Export playlist); the full backup is JSON. CSV not done;
   - diagnostics export.
6. **Visual regression tests** (Roborazzi or Paparazzi) for the icon registry and the main screens.

## Decisions that need evidence and approval first

- **macOS: SwiftUI shell or the current Rust app.** `core/apple` already holds SwiftUI Mac views while the shipped `.dmg` is the Rust app. Measuring start-up, memory and media keys needs a Mac, which the cloud container is not.
- **Shared Rust core versus Kotlin Multiplatform versus React Native.** No prototype yet. Kotlin Multiplatform already carries shared code on Android and iOS.
- **tvOS, Android Automotive, CarPlay.** Not started. CarPlay needs an Apple entitlement nuvx does not hold.
- **Design system and per-device-class specifications.** Not started; representative screens need the maintainer's sign-off first.

No major migration is started without approval.
