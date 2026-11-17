# License map

The repository's default license is GPL-3.0, as stated in [LICENSE](LICENSE). Existing file-level SPDX identifiers and component notices keep their declared terms; the default does not replace them.

The full AGPLv3 text is in [LICENSE-AGPL-3.0.txt](LICENSE-AGPL-3.0.txt), copied from the [SPDX License List Data v3.29.0](https://github.com/spdx/license-list-data/blob/v3.29.0/text/AGPL-3.0-only.txt). It is the text for both `AGPL-3.0-only` and `AGPL-3.0-or-later`; each source file's identifier specifies which applies.

The full LGPLv3 text is in [LICENSE-LGPL-3.0-only.txt](LICENSE-LGPL-3.0-only.txt), copied from the [SPDX License List Data v3.29.0](https://github.com/spdx/license-list-data/blob/v3.29.0/text/LGPL-3.0-only.txt).

The LGPL-2.1-or-later text is in [`app/android/mobile/src/main/cpp/dsd/dst/COPYING.LGPL-2.1`](app/android/mobile/src/main/cpp/dsd/dst/COPYING.LGPL-2.1); it also covers the pinned libusb dependency listed in [`core/native/third_party/README.md`](core/native/third_party/README.md).

## `LGPL-3.0-only`

- `app/android/mobile/src/main/cpp/bitperfect/`.
- `app/android/mobile/src/main/java/com/licht/nuvx/playback/audio/bitperfect/`.
- `app/android/mobile/src/test/java/com/licht/nuvx/playback/audio/bitperfect/`.
- Adapted from Mornye; see [`core/native/third_party/README.md`](core/native/third_party/README.md) for attribution.

## `AGPL-3.0-only`

- `app/web/app/src/playback/automix/engine/`, `app/web/app/src/playback/automix/db.ts`, and `app/web/app/src/playback/automix/analysis/` except `models.ts`.
- `core/shared/src/commonMain/kotlin/com/licht/nuvx/playback/smart/LoopMix.kt`.

## `AGPL-3.0-or-later`

- `core/native/analyzer/` and `app/android/mobile/src/main/cpp/jni/{analysis,mel,vocal}_jni.cpp`.
- `app/android/mobile/src/main/java/com/licht/nuvx/playback/smart/{AudioDecoder,TrackAnalysisRegionProcessor,TrackAnalysisRenditionSelector,TrackAnalysisSourceResolver,TrackAnalyzer}.kt` and `app/android/mobile/src/main/java/com/licht/nuvx/ui/YtMusicWebSessionScreen.kt`.
- `core/shared/src/commonMain/kotlin/com/licht/nuvx/playback/smart/` except `LoopMix.kt`; `core/shared/src/jvmSharedMain/kotlin/com/licht/nuvx/playback/smart/`.
- `core/shared/src/jvmSharedMain/kotlin/com/licht/nuvx/data/YtMusic*Repository.kt` and `core/shared/src/jvmSharedMain/kotlin/com/licht/nuvx/data/innertube/Innertube*Parser.kt`.
- `app/web/app/src/playback/automix/analysis/models.ts`.

## `GPL-3.0-only`

- [`GlassSelectionPill.kt`](app/android/mobile/src/main/java/com/licht/nuvx/ui/components/floatingtabbar/GlassSelectionPill.kt) is adapted from BitChord's `GlassSelectionPill.kt` at commit `edc1e6f`. The file's pill-motion code also ports portions of `liquid_glass_easy` 4.3.4 under MIT; its copyright and full MIT text are included in Android APKs at [`LICENSE-MIT-Liquid-Glass-Easy.txt`](app/android/legal-assets/licenses/LICENSE-MIT-Liquid-Glass-Easy.txt).

## MIT-licensed source code and portions

- [`ShakaPlayerAdapter.ts`](app/web/app/src/playback/youtube/ShakaPlayerAdapter.ts) and [`shakaHelpers.ts`](app/web/app/src/playback/youtube/shakaHelpers.ts) adapt the Shaka SABR adapter and helpers from Kira at commit `7a41cdc`; Copyright (c) 2025 LuanRT. The web app serves the full MIT text and attribution under `/licenses/` from [`public/licenses/`](app/web/app/public/licenses/).
- The pill-motion portions in [`GlassSelectionPill.kt`](app/android/mobile/src/main/java/com/licht/nuvx/ui/components/floatingtabbar/GlassSelectionPill.kt) are adapted from `liquid_glass_easy` 4.3.4; Copyright (c) 2025 Ahmed Gamil. The full MIT text and attribution ship in Android `assets/licenses/`.

## MIT-licensed AutoMix model assets

- `app/web/app/public/models/beat_this_final0_q8.onnx` is from the Beat This! final0 checkpoint by the Institute of Computational Perception, JKU Linz.
- `app/web/app/public/models/vocals_umxhq_int8.onnx` is the open-unmix umxhq vocals checkpoint by Fabian-Robert Stöter and Antoine Liutkus.
- Android APKs include the full model notices in `assets/licenses/LICENSE-MIT-Model-Weights.txt`.

Check each file's SPDX header for its exact declaration. AutoMix attribution is in [`app/web/app/src/playback/automix/NOTICE.md`](app/web/app/src/playback/automix/NOTICE.md). Orchard-derived analyzer attribution and its bundled license text are in [`app/ios/THIRD_PARTY_LICENSES.md`](app/ios/THIRD_PARTY_LICENSES.md) and [`core/apple/resources/Resources/AudioLicenses/ThirdPartyNotices.txt`](core/apple/resources/Resources/AudioLicenses/ThirdPartyNotices.txt).

Android APKs include the project license map, full project license texts, Orchard analyzer attribution, and native/model third-party notices under `assets/licenses/`; Android library notices remain under `META-INF/`.
