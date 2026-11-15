# Android shared universal APK implementation plan

> Updated 2026-11-15 after the project owner's choice of one shared APK. This replaces the original GMS/FOSS split plan. See [design](../specs/2026-11-13-android-universal-gms-foss-design.md).

**Goal:** Build shared universal Android phone, TV, and nuvx Auto APKs with optional Play Services features, and preserve in-app updates from existing 0.9 installs.

**Release status:** v1.0.0 (Android version code 10) was published on GitHub after owner authorization on 2026-11-15.

## Build and runtime behavior

- [x] Keep only `dev` and `prod` environment flavors in mobile and TV.
- [x] Compile the existing Cast/Wear implementations and dependencies into the shared APKs.
- [x] Keep Cast disabled when Play Services is unavailable; hide the Wear installer and catch Wear client failures.
- [x] Keep mobile and TV package IDs distinct and preserve their signing configuration.
- [x] Keep all three native ABIs in each universal APK.
- [x] Build `:app:testProdDebugUnitTest`, `:app:assembleProdDebug`, and `:tv:assembleProdDebug` locally.

## Updater and release assets

- [x] Set each updater to select the `-universal.apk` asset for its own phone, TV, or Auto package prefix.
- [x] Build one signed release APK per app in the nightly workflow.
- [x] Stage phone, TV, and Auto APKs under canonical `-universal.apk` names; copy identical phone/TV bytes under old `-gms.apk` and `-foss.apk` names for 0.9 clients.
- [x] Verify package metadata, signing certificate, ABI contents, and the eight-APK set (three canonical, four compatibility aliases, one Wear APK) in CI.
- [x] Update the ONNX size experiment to compare one mobile and one TV APK.
- [x] Build mobile, TV, Auto, and Wear release variants locally with version 1.0.0 / code 10; verify their package metadata.
- [x] Run actionlint v1.7.12 against the repository workflows; no diagnostics were reported.
- [ ] Run the ONNX inference/size experiment; retain Maven ONNX Runtime unless evidence supports changing it.

## APK install smoke coverage

- [x] Exercise phone, TV, Auto, and Wear debug APK install/launch paths with ApkStart on an API 36 phone emulator; see the [smoke report](../../ANDROID_APK_SMOKE_TEST.md) for results and limits.

## Release and remaining scope

- [x] Publish v1.0.0 only after owner authorization and verify its release assets and alias hashes.
- [x] Add standalone nuvx Auto as a TV-module flavor with a separate package ID, release artifact, and car-friendly stereo audio defaults. Android Auto projection remains integrated with the phone app.
- [x] Package desktop installers and browser extensions in the v1.0.0 release. The unsigned iOS IPA is being added by the Apple release workflow; iOS still needs external signing and has documented parity limits.
