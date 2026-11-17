# Nuvx master plan

The one handoff document for this programme. Update it in the same pull request as the work it describes. Last updated 2026-11-17. Read-only references and the per-issue matrix live in [`audits/REFERENCE_REPOS_AUDIT.md`](audits/REFERENCE_REPOS_AUDIT.md). The repository audit lives in [`audits/FULL_REFACTOR_AUDIT.md`](audits/FULL_REFACTOR_AUDIT.md).

## Released

**nuvx 1.0.0** (licht line), published 2026-11-17 from `6d3126e6`. It ships 12 files: phone, TV, Auto and Wear APKs signed with the `CN=licht` key (SHA-256 `833677fb…`), the Windows installer, the Linux `.tar.gz`, one universal macOS `.dmg`, the Chrome and Firefox extensions, and `SHA256SUMS`. The website's download routes, old `mac-arm`, `mac-intel` and `foss` links included, resolve to these files; this was checked by running `core/website/api/download.js` against the published asset list. All older releases are pre-releases named "old nuvx build …", and the old 1.0.0 is at `old-nuvx-v1.0.0`.

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

## Open

| PR | What | State |
|---|---|---|
| 312 | GlyphStudio v2 icon registry (`NuvxGlyph`/`NuvxSymbol`) with animated play/pause, download and cast states on Android; the same morph on iOS and web; per-device equalizer on Android; undo after deleting a nuvx playlist | Android 1,361 tests and web 241 pass; the iOS build is re-running after a fix |

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

1. Merge 313 (render tests, isolated web decipher, JitPack scope, sync replay rules, crossfade characterization, playlist export).
2. **Remaining audit:**
   - Android crossfade internals, the rest of the iOS app, the desktop UI, the rest of the web app, native C/C++ and Gradle scripts;
   - the "Investigate" rows of the reference audit: gapless transitions, desktop WebView reuse, MPRIS, PipeWire sample rate.
3. **First Sync client**, off by default: operation kinds for playlists, favorites and settings, one fixture every client replays. It needs the Oracle server (item 1 above) to test end to end.
4. **Nuvx Connect** on top of QR pairing: one playback owner, remote control, handoff.
5. **Smart Offline:**
   - done: resume after a kill, a 500 MB floor;
   - left: a user-set quota and auto-download of chosen playlists.
6. **Remaining features:**
   - Privacy & Data Center (devices and sessions once Sync exists);
   - playlist export: done on Android as M3U (playlist menu → Export playlist); the full backup is JSON. CSV not done;
   - diagnostics export.
7. **Visual regression tests** (Roborazzi or Paparazzi) for the icon registry and the main screens.

## Decisions that need evidence and approval first

- **macOS: SwiftUI shell or the current Rust app.** `core/apple` already holds SwiftUI Mac views while the shipped `.dmg` is the Rust app. Measuring start-up, memory and media keys needs a Mac, which the cloud container is not.
- **Shared Rust core versus Kotlin Multiplatform versus React Native.** No prototype yet. Kotlin Multiplatform already carries shared code on Android and iOS.
- **tvOS, Android Automotive, CarPlay.** Not started. CarPlay needs an Apple entitlement nuvx does not hold.
- **Design system and per-device-class specifications.** Not started; representative screens need the maintainer's sign-off first.

No major migration is started without approval.
