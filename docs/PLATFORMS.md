# Platforms and verification

## Current product scope

As of 2026-11-05, active product work is **Android only**: Android phones/tablets, Android TV, and Wear OS. Work on Windows, Linux, macOS, and iOS/iPadOS is paused until bxane gives the next platform direction. The existing Kotlin Multiplatform structure remains the codebase foundation; its presence does not mean those paused targets are complete or currently supported.

Requested targets: Windows, Linux, macOS; Android mobile/tablet, TV and Wear OS; iPhone/iPad IPA. Kotlin Multiplatform is the shared foundation. A target's inclusion in Gradle does not mean a release exists for it.

| Target | Implementation | Latest verified evidence |
|---|---|---|
| Android mobile/tablet | Shared Compose UI, Media3 service, mobile APK/AAB | Mobile debug APK rebuilt on 2026-11-05 (details in `docs/PROGRESS.md`). No emulator was connected, so this artifact was not installed or launched. Earlier emulator search/audio/background/media-control checks are historical evidence from 2026-11-02, not verification of this APK. No signed release exists. |
| Android TV | Shared wide UI, Leanback launcher/banner, Media3 | TV debug APK packaged and Android player host tests passed 3/3 on 2026-11-05. Earlier emulator launch, D-pad onboarding, live search and advancing media-session evidence is from 2026-11-02; the latest APK was not installed or launched. |
| Wear OS | Standalone watch flavour, round-screen vertical UI, same KMP catalogue and Media3 | Wear debug APK packaged and Android player host tests passed 3/3 on 2026-11-05. Reduced motion defaults on the watch and can be changed in the watch UI; Android accessibility motion reduction remains authoritative. Earlier round-emulator launch, keyboard search and advancing media-session evidence is from 2026-11-02; the latest APK was not installed or launched. Hardware audio, rotary input and accessibility remain unverified. |
| Windows | Compose Desktop, VLC decoder, loopback streaming bridge | A fresh isolated app image was built and launched on 2026-11-05. The selected track advanced after resume/restart; the earlier stop near 0:03 did not recur, so the original failure remains unresolved. No audio loopback, video, or frame-time measurement was made. This is debug evidence, not a signed installer or release. |
| Linux | Same desktop JVM app and native VLC transport | Build definition and CI job exist; no Linux build or runtime test has been executed. VLC must currently be installed or supplied through `NUVX_VLC_HOME`. |
| macOS | Same desktop JVM app and native VLC transport | Build definition and CI job exist; no macOS build, signing, or runtime test has been executed. VLC must currently be installed or supplied through `NUVX_VLC_HOME`. |
| iOS/iPadOS | Shared KMP framework targets and Darwin HTTP actual; InnerTubeX in common code | Framework targets exist, but this Windows host did not build them. There is no iOS app shell or AVFoundation player, and no signed IPA. |

## Architecture boundary

`core/shared/commonMain` contains domain contracts, router, catalogue parser, primary InnerTubeX adapter, lyrics, radio and reusable Compose UI. `jvmCommonMain` supplies the OkHttp HTTP actual and eXcor's JVM/Android adapter. `iosMain` supplies the Darwin HTTP actual. `NuvxPlayer` and `KeyValueStore` keep native playback and persistence outside common code. The iOS alternative-engine adapter is still pending, not silently simulated.

Android flavours use the same application ID (`com.bxane.nuvx`) for their different device categories. Desktop code is shared across the three desktop operating systems, with a platform-native VLC library required. Windows preparation downloads the checksum-pinned official portable runtime; Linux/macOS runtime bundling remains release work.

The CI workflow attempts host builds and iOS framework linking on macOS. A successful remote run has not been verified here. It does not produce or claim an IPA, notarized DMG, signed MSI or store-ready release.
