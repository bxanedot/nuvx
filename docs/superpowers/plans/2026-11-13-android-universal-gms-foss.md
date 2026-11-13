# Android universal GMS/FOSS APKs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish universal GMS and FOSS APKs for Android mobile and TV, with in-app updates selecting the matching flavor.

**Architecture:** Add a `distribution` flavor dimension to the existing `:app` and `:tv` modules. Keep common app behavior in `main`, isolate Google Cast and Wear OS integrations and their manifests/dependencies in GMS sources, and provide FOSS behavior that reports those integrations unavailable. Disable ABI splits and stage four universal APKs from the nightly workflow.

**Tech Stack:** Kotlin, Android Gradle Plugin/Kotlin DSL, Android manifests, GitHub Actions, Android Emulator.

**Spec:** `docs/superpowers/specs/2026-11-13-android-universal-gms-foss-design.md`

## Global Constraints

- Preserve GMS package IDs, signing identity, and existing GMS feature behavior.
- FOSS contains no Google Play Services dependency or merged Cast/Wear service declarations.
- Mobile and TV universal APKs include `armeabi-v7a`, `arm64-v8a`, and `x86_64`.
- Emit exactly four mobile/TV APKs: GMS and FOSS for each module; preserve the separate Wear OS and desktop release assets.
- Select in-app updates by installed distribution; continue supporting older ABI-split releases as a fallback.
- Keep the custom ONNX Runtime experimental until universal APK size and model inference checks support shipping it.

## Review Focus

- FOSS runtime dependency graph or merged manifest still references Play Services — verify resolved dependencies and inspect each FOSS manifest.
- GMS application identity or features regress — verify package IDs, certificate digest, and GMS release APKs.
- A universal APK omits one supported ABI — inspect each APK's native libraries.
- Updater selects the wrong distribution or cannot update from an older split release — test GMS/FOSS matching and legacy ABI fallback.
- The universal custom runtime is larger or fails either model — keep Maven runtime in release and report measured sizes.

---

### Task 1: Add GMS/FOSS variants and isolate optional integrations

**Files:**
- Modify: `app/android/mobile/build.gradle.kts`
- Modify: `app/android/tv/build.gradle.kts`
- Modify: `app/android/mobile/src/main/AndroidManifest.xml`
- Modify: `app/android/tv/src/main/AndroidManifest.xml`
- Move GMS-only Cast/Wear implementation files to `src/gms/` source sets.
- Add FOSS source-set implementations for the public methods/types consumed by shared playback, settings, and player UI.
- Test: Android flavor compile tasks and FOSS dependency/merged-manifest inspection.

**Interfaces:**
- Consumes: existing `CastController`, `CastPlayback`, `WearBridge`, and `WatchInstaller` call sites.
- Produces: `gms` and `foss` variants for both modules; FOSS implementations expose the same app-facing API and report Cast/Wear unavailable.

- [ ] Add the `distribution` flavor dimension with `gms` and `foss`; leave `dev`/`prod` environment flavors intact and keep the existing GMS application IDs.
- [ ] Move Play Services Cast/Wear dependencies and corresponding manifest entries to GMS-only configurations/source manifests.
- [ ] Separate GMS implementation classes from common call sites; add FOSS implementations that preserve normal local playback and hide/unavailable optional Cast/Wear UI.
- [ ] Disable ABI splits in mobile and TV so each release variant emits one APK containing all three configured ABIs.
- [ ] Build all four debug variants; inspect FOSS runtime dependencies and merged manifests to confirm no Play Services artifacts/declarations remain.

### Task 2: Make app updates distribution-aware

**Files:**
- Modify: `app/android/mobile/build.gradle.kts`
- Modify: `app/android/tv/build.gradle.kts`
- Modify: `app/android/mobile/src/main/java/com/bxane/nuvx/data/AppUpdateChecker.kt`
- Test: `app/android/mobile/src/test/java/com/bxane/nuvx/data/AppUpdateCheckerTest.kt` (create if no existing focused test exists).

**Interfaces:**
- Consumes: `BuildConfig.UPDATE_APK_PREFIX` and the distribution flavor.
- Produces: exact current-variant asset selection, with legacy ABI-split fallback.

- [ ] Add a `BuildConfig` distribution identifier (`gms` or `foss`) to both modules.
- [ ] Add a pure asset-selection helper that first selects the release APK matching the app prefix and distribution suffix, then falls back to the matching ABI-split legacy asset.
- [ ] Test GMS-vs-FOSS selection, unsupported/missing matching assets, and legacy ABI selection.
- [ ] Assemble and verify `:app:assembleProdGmsRelease`, `:app:assembleProdFossRelease`, `:tv:assembleProdGmsRelease`, and `:tv:assembleProdFossRelease`.

### Task 3: Publish and prune the four Android nightly APKs

**Files:**
- Modify: `.github/workflows/android-nightly.yml`

**Interfaces:**
- Consumes: the four signed release APKs from Task 1 and their distribution identifiers.
- Produces: assets `nuvx-v<version>-<nightly>-gms.apk`, `nuvx-v<version>-<nightly>-foss.apk`, `NuvxTV-v<version>-<nightly>-gms.apk`, and `NuvxTV-v<version>-<nightly>-foss.apk`.

- [ ] Build all four variants in the Android job and stage only their universal APK outputs.
- [ ] Verify package ID, version code/name, signing certificate, and all three ABI libraries in every staged APK.
- [ ] Update nightly notes to describe universal mobile/TV GMS and FOSS APKs; retain Wear OS APK staging.
- [ ] Keep existing latest-nightly publication/pruning behavior and update `SHA256SUMS` for the four APKs.
- [ ] Run actionlint and verify the workflow’s paths/output names match the Gradle variants.

### Task 4: Measure reduced runtime with universal APKs

**Files:**
- Modify: `.github/workflows/android-ort-size-experiment.yml`
- Modify: `docs/superpowers/plans/2026-11-13-android-ort-size-experiment.md`

**Interfaces:**
- Consumes: universal GMS/FOSS mobile and TV release/debug variants and the reduced AAR.
- Produces: inference results and byte comparisons for all four APKs.

- [ ] Build universal baseline and custom-runtime APKs for all four variants.
- [ ] Run beat and vocal ONNX inference with the custom AAR on an emulator for mobile GMS and FOSS variants.
- [ ] Compare exact baseline/custom APK names and sizes for mobile and TV, GMS and FOSS; upload the report and AAR artifact.
- [ ] Keep release dependency on Maven unless the real model inference and universal APK size results meet the design constraints.

### Task 5: Release and verify

- [ ] Run the Android nightly workflow from current `main`; confirm exactly four universal mobile/TV APKs plus the existing Wear OS and desktop assets publish.
- [ ] Download and inspect release APK metadata and native libraries; verify updater asset names map to each build flavor.
- [ ] Confirm the release workflow prunes older nightly releases and tags, leaving only the latest nightly.
- [ ] Record actual universal APK sizes; leave the custom AAR experimental if size or model validation is insufficient.
