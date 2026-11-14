# Android shared universal APK implementation plan

> Updated 2026-11-15 after the user's choice of one shared APK. This replaces the original GMS/FOSS split plan. See [design](../specs/2026-11-13-android-universal-gms-foss-design.md).

**Goal:** Build one universal Android phone APK and one universal Android TV APK with optional Play Services features, and preserve in-app updates from existing 0.9 installs.

**Release hold:** Do not create or publish version 1.0.0 (Android version code 10) without owner authorization.

## Build and runtime behavior

- [x] Keep only `dev` and `prod` environment flavors in mobile and TV.
- [x] Compile the existing Cast/Wear implementations and dependencies into the shared APKs.
- [x] Keep Cast disabled when Play Services is unavailable; hide the Wear installer and catch Wear client failures.
- [x] Keep mobile and TV package IDs distinct and preserve their signing configuration.
- [x] Keep all three native ABIs in each universal APK.
- [x] Build `:app:testProdDebugUnitTest`, `:app:assembleProdDebug`, and `:tv:assembleProdDebug` locally.

## Updater and release assets

- [x] Set each updater to select the `-universal.apk` asset for its own phone or TV prefix.
- [x] Build one signed release APK per app in the nightly workflow.
- [x] Stage each APK under its canonical `-universal.apk` name and copy identical bytes under old `-gms.apk` and `-foss.apk` names for 0.9 clients.
- [x] Verify package metadata, signing certificate, ABI contents, and the seven-APK set (two canonical, four compatibility aliases, one Wear APK).
- [x] Update the ONNX size experiment to compare one mobile and one TV APK.
- [x] Build the mobile and TV release variants locally.
- [ ] Run actionlint or CI workflow validation; actionlint is not installed in this checkout.
- [ ] Run the ONNX inference/size experiment; retain Maven ONNX Runtime unless evidence supports changing it.

## Release and remaining scope

- [ ] Only after separate authorization, run the nightly workflow and verify actual release assets and alias hashes.
- [ ] Do not create a 1.0.0 release/tag as part of this work.
- [ ] Android Auto remains integrated with the phone app; standalone Android Automotive packaging needs separate design work.
- [ ] iOS parity/signing, desktop installers, extension packaging, GlyphStudio installer inclusion, and remaining handoff tasks stay in the master plan.
