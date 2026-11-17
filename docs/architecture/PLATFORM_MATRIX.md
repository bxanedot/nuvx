# Platform matrix

What each platform has, as of 2026-11-17 (nuvx 1.0.0). The terms:
- **Released:** in the 1.0.0 GitHub release.
- **Built in CI:** compiles on every change but is not shipped.
- **Device-tested:** someone ran it on real hardware or an emulator.

| Platform | Technology | Status | Evidence |
|---|---|---|---|
| Android phone and tablet | Kotlin, Jetpack Compose, Media3 | Released (`nuvx-v1.0.0.apk`) | 1,364 unit tests; emulator smoke test (Android 16) of the 1.0.0 candidate |
| Android TV / Google TV | Kotlin, Compose for TV | Released (`NuvxTV-v1.0.0.apk`) | Compiles; no device test recorded |
| Wear OS | Kotlin, Compose for Wear | Released (`NuvxWear-v1.0.0.apk`) | Compiles; no device test recorded |
| Android Auto (projection) | Media3 media library in the phone app | Released with the phone app | `AndroidAutoMediaLibraryTest` (instrumented) exists; not run here |
| nuvx Auto (standalone head unit) | Kotlin | Released (`NuvxAuto-v1.0.0.apk`) | Compiles |
| Android Automotive OS | — | Not started | — |
| iPhone and iPad | SwiftUI + Kotlin Multiplatform shared module | Released (`nuvx-1.0.0-universal.ipa`, unsigned, for sideloading) | CI build on macOS runners |
| Apple TV (tvOS) | — | Not started | — |
| CarPlay | — | Not started; needs an Apple entitlement nuvx does not hold | — |
| Windows | Rust + egui | Released (`nuvx-setup-1.0.0.exe`, x64 and ARM64) | 230 desktop tests (Linux); release build on Windows runners |
| macOS | Rust + egui | Released (`nuvx-setup-1.0.0-macos-universal.dmg`, Apple silicon and Intel) | Release build on macOS runners; unsigned and not notarized |
| Linux | Rust + egui | Released (`nuvx-setup-1.0.0-linux-universal.tar.gz`) | 230 desktop tests |
| Web | React + TypeScript, Shaka | Live at web.nuvx.lol | 241 tests |
| Chrome and Firefox extensions | WXT | Released (zip) | 2 tests; built in the release |

## Cross-platform features

| Feature | Android | iOS | Desktop | Web |
|---|---|---|---|---|
| Listen Together | Yes | Yes | Yes | Yes |
| Nightly updates switch | Yes | No (stable only) | Yes | n/a |
| Per-device equalizer | Yes (PR 312) | No | No | No |
| Playlists in backups | Yes | No | No | No |
| Playlist export (M3U) | Yes | No | No | No |
| Downloads resume after a kill | Yes | — | — | — |
| Animated play/pause | GlyphStudio | SF Symbols `.replace` | Own morph | CSS morph |
| Redacted diagnostics | Yes | Yes | Yes (log file) | — |
| Nuvx Sync / Connect | Server only, not deployed | — | — | — |
