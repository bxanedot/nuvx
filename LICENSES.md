# License map

The repository's default license is GPL-3.0, as stated in [LICENSE](LICENSE). Existing file-level SPDX identifiers and component notices keep their declared terms; the default does not replace them.

The full AGPLv3 text is in [LICENSE-AGPL-3.0.txt](LICENSE-AGPL-3.0.txt), copied from the [SPDX License List Data v3.29.0](https://github.com/spdx/license-list-data/blob/v3.29.0/text/AGPL-3.0-only.txt). It is the text for both `AGPL-3.0-only` and `AGPL-3.0-or-later`; each source file's identifier specifies which applies.

The full LGPLv3 text is in [LICENSE-LGPL-3.0-only.txt](LICENSE-LGPL-3.0-only.txt), copied from the [SPDX License List Data v3.29.0](https://github.com/spdx/license-list-data/blob/v3.29.0/text/LGPL-3.0-only.txt).

## `LGPL-3.0-only`

- `app/android/mobile/src/main/cpp/bitperfect/`.
- `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/`.
- `app/android/mobile/src/test/java/com/bxane/nuvx/playback/audio/bitperfect/`.
- Adapted from Mornye; see [`core/native/third_party/README.md`](core/native/third_party/README.md) for attribution.

## `AGPL-3.0-only`

- `app/web/app/src/playback/automix/engine/`, `app/web/app/src/playback/automix/db.ts`, and `app/web/app/src/playback/automix/analysis/` except `models.ts`.
- `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/LoopMix.kt`.

## `AGPL-3.0-or-later`

- `core/native/analyzer/` and `app/android/mobile/src/main/cpp/jni/{analysis,mel,vocal}_jni.cpp`.
- `app/android/mobile/src/main/java/com/bxane/nuvx/playback/smart/{AudioDecoder,TrackAnalysisRegionProcessor,TrackAnalysisRenditionSelector,TrackAnalysisSourceResolver,TrackAnalyzer}.kt` and `app/android/mobile/src/main/java/com/bxane/nuvx/ui/YtMusicWebSessionScreen.kt`.
- `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/` except `LoopMix.kt`; `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/playback/smart/`.
- `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/YtMusic*Repository.kt` and `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/innertube/Innertube*Parser.kt`.
- `app/web/app/src/playback/automix/analysis/models.ts`.

Check each file's SPDX header for its exact declaration. AutoMix attribution is in [`app/web/app/src/playback/automix/NOTICE.md`](app/web/app/src/playback/automix/NOTICE.md). Orchard-derived analyzer attribution and its bundled license text are in [`app/ios/THIRD_PARTY_LICENSES.md`](app/ios/THIRD_PARTY_LICENSES.md) and [`core/apple/resources/Resources/AudioLicenses/ThirdPartyNotices.txt`](core/apple/resources/Resources/AudioLicenses/ThirdPartyNotices.txt).
