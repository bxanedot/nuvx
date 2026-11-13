# Android universal GMS and FOSS APKs

## Intent

Publish one universal mobile APK and one universal Android TV APK for each distribution: GMS and FOSS. Each APK contains the existing supported Android ABIs, so users choose a distribution rather than an ABI-specific file.

## Goals

- Keep all current Android features in GMS builds and preserve the existing GMS package IDs and signing identity.
- Build FOSS variants without Google Play Services dependencies. Chromecast and Wear OS pairing/install are GMS-only; core playback, downloads, and the rest of the app remain available in FOSS.
- Produce exactly four phone/TV release APKs: mobile GMS, mobile FOSS, TV GMS, and TV FOSS.
- Make in-app updates select the same distribution as the installed build.
- Continue reducing APK size through the model-specific ONNX Runtime work.

## Non-goals and anti-goals

- No desktop, Wear OS, web, or UI redesign work.
- Do not remove features from GMS builds.
- Do not publish ABI-split APKs or bundle an Android App Bundle for this distribution.
- Do not claim a universal APK is smaller than an arm64-only APK; it is expected to be larger per file.

## Approaches considered

1. **Add a GMS/FOSS product-flavor dimension to the existing mobile and TV modules, and disable ABI splitting.** This reuses current modules, preserves GMS behavior, and isolates GMS-only source, dependencies, and manifest entries to GMS. Recommended.
2. **Create separate GMS and FOSS Android modules.** This duplicates build configuration and risks feature drift without benefit over flavors.
3. **Keep ABI splits and publish all split assets.** This keeps each download smaller but conflicts with the requested one-file-per-distribution installer flow.

## Design

Add a distribution flavor dimension to the existing `:app` and `:tv` modules. GMS remains the default production behavior and keeps the current application IDs and signing identity so existing installations update in place. FOSS uses the same application IDs and signing identity, making the two builds alternatives that can update one another while preserving app data; users cannot install both side by side.

Move or isolate Cast and Wear OS integration code and its manifest declarations into GMS source sets. Provide FOSS implementations at existing call sites that report those optional integrations unavailable. Keep GMS-only Play Services artifacts in GMS configurations. The TV module follows the same rule for Cast.

Disable ABI splits for mobile and TV release variants. Stage and verify exactly four universal release APKs in the Android nightly. Give each asset a stable GMS/FOSS and mobile/TV suffix. Update `AppUpdateChecker` to select the asset matching its installed flavor rather than guessing from ABI. Keep the separate Wear OS APK and desktop assets unchanged.

## Constraints and risks

- Universal packages contain `armeabi-v7a`, `arm64-v8a`, and `x86_64`; their size will exceed the corresponding arm64-only APK. The reduced ONNX Runtime is useful only if real model inference passes and total APK size improves against the universal baseline.
- GMS and FOSS share package IDs and signing, so they are mutually exclusive installs. This supports in-place switching and data preservation.
- The current app updater selects ABI-named assets, so release naming and updater selection must change together.
- FOSS must not retain Play Services classes through its dependency graph or merged manifest.

## Validation

- Assemble and inspect all four signed release APKs; verify IDs, signatures, universal ABI contents, and absence of Cast/Wear Play Services from FOSS.
- Run the ONNX model inference check on an Android emulator and compare universal baseline/custom APK sizes.
- Verify in-app update selection for both flavors using their matching release assets.
