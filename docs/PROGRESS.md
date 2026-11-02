# Progress

Updated 2026-11-02. Engineering checkpoint; not a release.

| Area | Implemented / verified | Remaining |
|---|---|---|
| Shared KMP | Core/router/parser, common InnerTubeX, reusable Compose UI, JVM tests | iOS framework compilation on macOS |
| Engines | Real default/alternative switch, fallback, health and persisted settings; current Android/Windows native playback | eXcor upstream stream refusals; iOS alternative adapter |
| Brand | All 30 original files verified against supplied archives; shared/Android vectors and generated Windows ICO | Remaining store artwork/platform icon review |
| Android mobile | APK/AAB, lint, replay regression; emulator live search/playback/background/system Pause | Physical devices, lifecycle stress, release signing |
| Android TV | APK/AAB, Leanback launcher/banner, wide UI; D-pad onboarding, live search and advancing media playback in emulator | Full remote/focus/accessibility/hardware review |
| Wear OS | Standalone APK/AAB, round-screen UI; keyboard search and advancing playback in emulator | Rotary, battery, hardware audio and accessibility |
| Windows | Native live decoding/pause/seek test; bundled JDK 27/VLC app image and MSI built | Packaged EXE launch blocked by automatic approval review; installer clean-install/signing checks |
| Linux/macOS | Shared desktop implementation, package definitions and CI jobs | Host builds and runtime checks, VLC bundling and signing |
| iOS/iPadOS | KMP framework targets, common primary backend, Darwin networking | AVFoundation player, app shell, macOS build and signed IPA |
| Lyrics | LRC/enhanced-LRC parser tests, LRCLIB client, line-highlighted view | Word highlighting, TTML, timing corrections UI |
| Radio | Interrupted session's seed/refill core and test preserved | Radio UI/internet radio and cancellation/race hardening |
| CI | SHA-pinned 3-host workflow, macOS iOS framework attempt, Dependabot; YAML parsed | Workflow has not run remotely |

Still required by the full mission: library/favourites/playlists and browsing, local music, authorized offline storage, OAuth, radio UI, gapless/crossfade/effects, Discord, Listen Together Worker, website, native iOS app/IPA, signing, security/provenance review, performance/accessibility and full release testing. Unsupported audio technologies are not advertised.

Offline live-backend tests now skip explicitly unless enabled. A skipped test is not a live verification. See PLATFORMS.md, BLOCKERS.md and TOOLCHAIN.md. Generated reports live in each module's build/reports directory.
