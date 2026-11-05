# Third-Party Notices

nuvx © 2026 bxane, licensed GPL-3.0-only. This file lists every third-party component that nuvx distributes. Each component's own licence text ships with its artifact, and full licence texts are linked below.

## Integrated dependencies

| Component | Version | Licence | Upstream |
|---|---|---|---|
| InnerTubeX | v0.11.2 | GPL-3.0 | https://github.com/MetrolistGroup/innertubex |
| QuackJS (transitive, InnerTubeX) | 0.1.2 | see upstream | https://github.com/MetrolistGroup/quackjs |
| eXcor | 0.2.0 (commit 170e81f) | Apache-2.0 | https://github.com/bxanedot/eXcor |
| Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization | 2.4.21 / 1.11.0 / 1.12.0 | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin |
| Ktor client | 3.6.1 | Apache-2.0 | https://github.com/ktorio/ktor |
| OkHttp / Okio (transitive) | see build | Apache-2.0 | https://github.com/square/okhttp |
| Compose Multiplatform | 1.12.2 | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform |
| GlyphStudio SF Symbols Compose library | v1.0.0 | Apache-2.0 | https://github.com/bxanedot/GlyphStudio |
| Coil 3 | 3.6.3 | Apache-2.0 | https://github.com/coil-kt/coil |
| AndroidX Media3 | 1.11.1 | Apache-2.0 | https://github.com/androidx/media |
| AndroidX Activity | 1.13.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Kyant0/backdrop | 2.0.0 (vendored Android source) | Apache-2.0 | https://github.com/Kyant0/backdrop |
| vlcj / vlcj-natives | 4.12.1 / 4.12.0 | GPL-3.0 | https://github.com/caprica/vlcj |
| JNA (transitive, vlcj) | 5.16.0 | LGPL-2.1-or-later / Apache-2.0 | https://github.com/java-native-access/jna |
| VLC Windows runtime (packaging download) | 3.0.24 | GPL-2.0-or-later; components retain their own licences | https://www.videolan.org/vlc/ |

Licence texts: GPL-3.0 at https://www.gnu.org/licenses/gpl-3.0.txt; Apache-2.0 at https://www.apache.org/licenses/LICENSE-2.0.txt.

The SF Symbols used through GlyphStudio are Apple artwork and remain subject to Apple's SF Symbols terms. nuvx uses these symbols only as interface icons, not as its app icon or brand mark.

## Original nuvx assets

The nuvx mark (white and black variants, traced to vector from bxane's artwork) was created by bxane and is used with permission.

## Integrated GPL source code

The Android rubber-band scrolling implementation is derived from BitChord's `IosOverscroll.kt`; its header credits the Convx implementation it adapted from. The copied source retains GPL-3.0 licensing and attribution. No upstream artwork or brand assets were copied.

The Android Explore browse layout and welcome-page motion are adaptations of GPL-3.0 Compose UI from Convx's `ExploreScreen.kt` and BitChord's `OnboardingScreen.kt`. Nuvx keeps its own screen state, text, product flows, artwork and navigation wiring; no upstream artwork or brand assets were copied.

The Android glass surface adapts bxane's supplied GPL-3.0 `Nuvx_GlassEffect_bxane.kt` and vendors Kyant0/backdrop 2.0.0 under Apache-2.0. Copyright and license headers are retained in the vendored source files.

| Project | Licence | URL |
|---|---|---|
| BitChord (`App/androidApp/.../RubberBandOverscroll.kt`; adapted source at `app/src/main/java/com/music/bitchord/ui/utils/IosOverscroll.kt`) | GPL-3.0 | https://github.com/kushagrasinghx/BitChord |
| Convx (upstream lineage credited in the copied file header) | GPL-3.0 | https://github.com/cosmictaserdev-creator/Convx |
| Kyant0/backdrop (`App/androidApp/src/main/kotlin/com/bxane/nuvx/backdrop/`) | Apache-2.0 | https://github.com/Kyant0/backdrop |
