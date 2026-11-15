# Android shared universal APKs

## Decision

Updated 2026-11-15: build one shared universal APK for Android phones/tablets and one for Android TV, plus a standalone nuvx Auto APK from the TV module. This supersedes the original GMS/FOSS distribution split. Keep the `dev` and `prod` environment flavors, but compile the full Cast and Wear implementations and their Play Services dependencies into the shared production APKs.

Play Services is optional at runtime. Casting stays disabled when the service is unavailable; Wear controls stay hidden, and Wear client failures must not interrupt local playback. FOSS-only dependency exclusion is retired.

## Goals

- Preserve current mobile and TV application IDs, signing identity, and the full GMS feature set.
- Include `armeabi-v7a`, `arm64-v8a`, and `x86_64` in each APK.
- Emit one canonical `-universal.apk` per mobile, TV, and Auto app.
- Let existing 0.9 GMS/FOSS installs update in place: publish identical copies of each shared APK under the former `-gms.apk` and `-foss.apk` asset names.
- Keep the Wear OS APK and desktop assets as separate release assets.
- Keep version 1.0.0 unreleased until owner authorization.

## Design

The `:app` and `:tv` modules keep only their `dev` and `prod` flavors. The existing Play Services Cast and Wear code remains in the shared build and its dependencies move to regular `implementation` configurations. Cast checks `GoogleApiAvailability`; Wear installation is available only when Play Services reports success, and Wear client lookup/await failures are caught.

`BuildConfig.NUVX_DISTRIBUTION` is `universal`. The updater selects the universal asset for its own app prefix. Phone and TV use distinct prefixes to avoid selecting one another's APKs.

The nightly workflow builds phone, TV, Auto, and Wear release APKs under the same release tag. It verifies package ID, version, signing certificate, and all three native ABIs for phone, TV, and Auto, then stages each canonical universal name. The phone and TV APKs also get byte-identical legacy distribution aliases; Auto has no split aliases. `SHA256SUMS` covers all APK assets.

## Constraints and risks

- Universal APKs are larger than arm64-only downloads; do not claim otherwise.
- Play Services libraries are bundled in both APKs. Devices without Play Services must continue local playback without crashes; Cast and Wear are unavailable there.
- The currently published 0.9.0 release still has separate GMS/FOSS assets. Its old updater selects the matching alias from the next shared build.
- The custom ONNX Runtime remains experimental until emulator inference and size checks pass.

## Validation

- Run `:app:testProdDebugUnitTest`, `:app:assembleProdDebug`, and `:tv:assembleProdDebug`.
- Inspect release APK metadata, signing, and all three ABI libraries in CI.
- Check that the canonical APK and both legacy aliases have identical SHA-256 values.
- Run the ONNX inference experiment separately; it does not promote the custom runtime by itself.
