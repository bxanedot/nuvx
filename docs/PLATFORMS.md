# Platforms and verification

Requested targets: Windows, Linux, macOS; Android mobile/tablet, TV and Wear OS; iPhone/iPad IPA. Kotlin Multiplatform is the shared foundation. A target's inclusion in Gradle does not mean a release exists for it.

| Target | Implementation | Verified on 2026-11-02 |
|---|---|---|
| Android mobile/tablet | Shared Compose UI, Media3 service, mobile APK/AAB | APK/AAB built, lint and replay unit test; emulator live search/audio, background playback, media Pause key |
| Android TV | Shared wide UI, Leanback launcher/banner, Media3 | APK/AAB built, emulator launch, D-pad onboarding, live search and advancing PLAYING media session |
| Wear OS | Standalone watch flavour, round-screen vertical UI, same KMP catalogue and Media3 | APK/AAB built; round emulator launch, keyboard search and advancing PLAYING media session; hardware audio/rotary/accessibility not yet verified |
| Windows | Compose Desktop, VLC decoder, private loopback streaming bridge | JDK 27 bundled app image built; real native playback/pause/seek smoke test passed. Packaged EXE launch was blocked by automatic approval review |
| Linux | Same desktop JVM app and native VLC transport | Build definition and CI job added; no Linux build or runtime test executed locally |
| macOS | Same desktop JVM app and native VLC transport | Build definition and CI job added; no macOS build, signing or runtime test executed locally |
| iOS/iPadOS | Shared KMP framework targets and Darwin HTTP actual; InnerTubeX in common code | Not built on this Windows host. AVFoundation player, iOS app shell and signed IPA remain unfinished |

## Architecture boundary

`core/shared/commonMain` contains domain contracts, router, catalogue parser, primary InnerTubeX adapter, lyrics, radio and reusable Compose UI. `jvmCommonMain` supplies the OkHttp HTTP actual and eXcor's JVM/Android adapter. `iosMain` supplies the Darwin HTTP actual. `NuvxPlayer` and `KeyValueStore` keep native playback and persistence outside common code. The iOS alternative-engine adapter is still pending, not silently simulated.

Android flavours use the same application ID (`com.bxane.nuvx`) for their different device categories. Desktop code is shared across the three desktop operating systems, with a platform-native VLC library required. Windows preparation downloads the checksum-pinned official portable runtime; Linux/macOS runtime bundling remains release work.

The CI workflow attempts all host builds and iOS framework linking on macOS. It has not yet run remotely. It does not produce or claim an IPA, notarized DMG, signed MSI or store-ready release.
