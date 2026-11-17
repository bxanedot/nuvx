# Claude handoff: Licht 1.0.0 candidate

## Workspace and safety

- Repository: `lylicht/nuvx` in this local checkout.
- Branch: `codex/nuvx-licht-1.0.0-candidate` (local only; changes are uncommitted).
- The owner said **do not release yet**. Do not push, publish, move tags, rename releases, or create a release until asked. The current public `v1.0.0` is the old app with Android version code 10; preserve its commit and assets. The proposed archive tag is `old-nuvx-v1.0.0`.
- Keep `keystore.properties` and `work/` out of Git. `work/release-signing/licht-release.jks` is a local private key; never print or commit it.

## Requested candidate

- Treat this as the first release of the `licht` identity: version `1.0.0`, Android version code `1`, across platforms.
- Android target IDs: phone/Wear `com.licht.nuvx`, TV `com.licht.nuvx.tv`, Auto `com.licht.nuvx.auto`.
- Produce one plain APK per Android target (phone, TV, Auto, Wear); no GMS/FOSS copies.
- A new signing certificate with subject `CN=licht` is configured for GitHub Actions. Its SHA-256 fingerprint is `833677fbdff79698cae61cca10e09622e3732cc452d4800262cc118230e0fb9c`.
- The Android package ID and signing key changed, so installs under the old package ID cannot update in place; they install as a separate app.

## Work already present on the branch

- Rebranded app-owned packages, paths, IDs, app groups, URL schemes, JNI exports, and references from the previous identity to `licht`.
- Set Android, iOS, desktop, and browser-extension release metadata to `1.0.0`; Android defaults to code `1`.
- Changed Android release staging/update selection and website download routing to use one plain APK per target while keeping old-asset compatibility paths.
- Updated release workflow version/signature checks and prevented normal pushes to `main` from preparing another nightly release.
- Fixed the release-start crash found during emulator smoke testing: `PrivacyNetwork.install()` was called in `Application.attachBaseContext()`, where `applicationContext` was not ready. It now runs first in `onCreate()`, before other app network clients.
- Earlier task work repaired `canvas.nuvx.lol` HTTPS/DNS and deployed the `nuvx-listen` Cloudflare Worker at `https://nuvx-listen.lylicht.workers.dev`.

## Verification so far

- `node --test core/website/api/download.test.js`: 49 passed.
- `:app:testProdDebugUnitTest`: passed after the startup-order fix.
- Before the fix, the signed phone APK installed on Android 16/API 36 emulator `emulator-5556`, then crashed on launch with `NullPointerException` in `PrivacyNetwork.install()` while reading preferences.
- A new four-target release build completed successfully after the startup fix. The final phone APK's signer fingerprint matches the release workflow pin. Re-check metadata/signatures across all four rebuilt packages and relaunch the fixed phone APK before claiming stable.
- iOS signing/build cannot be checked on this Windows host. Do not call the all-platform candidate fully validated until Apple CI/build evidence is available.
- Community Canvas upload has not been end-to-end tested. The owner will set their fine-grained GitHub token for `lylicht/nuvx` as the Cloudflare Worker secret `CANVAS_GITHUB_TOKEN`; do not ask them to paste it into chat.

## Verification 2026-11-17 (Claude)

Final APK check with build-tools 37.0.0 (`aapt2 dump badging`, `apksigner verify --print-certs`). These are the existing build outputs; nothing was rebuilt.

| Target | APK (built 2026-11-17) | Package | versionName / versionCode | Signer DN | Signer SHA-256 |
|---|---|---|---|---|---|
| Phone | `app/android/mobile/build/outputs/apk/prod/release/app-prod-release.apk` (03:37) | `com.licht.nuvx` | `1.0.0` / `1` | `CN=licht` | `833677fbdff79698cae61cca10e09622e3732cc452d4800262cc118230e0fb9c` |
| TV | `app/android/tv/build/outputs/apk/prodTv/release/tv-prod-tv-release.apk` (03:39) | `com.licht.nuvx.tv` | `1.0.0` / `1` | `CN=licht` | `833677fbdff79698cae61cca10e09622e3732cc452d4800262cc118230e0fb9c` |
| Auto | `app/android/tv/build/outputs/apk/prodAuto/release/tv-prod-auto-release.apk` (03:40) | `com.licht.nuvx.auto` | `1.0.0` / `1` | `CN=licht` | `833677fbdff79698cae61cca10e09622e3732cc452d4800262cc118230e0fb9c` |
| Wear | `app/android/wearos/build/outputs/apk/prod/release/NuvxWear-prod-release.apk` (03:26) | `com.licht.nuvx` (`android.hardware.type.watch`) | `1.0.0` / `1` | `CN=licht` | `833677fbdff79698cae61cca10e09622e3732cc452d4800262cc118230e0fb9c` |

- All four APKs have one signer and verify with APK Signature Scheme v2. All four digests match the workflow pin.
- Every APK ships native code for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. License/legal entries: 23 each for phone, TV and Auto, 16 for Wear. These counts come from a filename match; the contents were not reviewed.
- The Wear APK was built at 03:26, before the phone/TV/Auto rebuild. Its metadata and signer are correct. Rebuild it only if the startup fix also needs to reach the Wear module.

Emulator smoke test of the fixed phone APK:
- `emulator-5556` runs AVD `nuvx-check-android-36b` (Android 16 / API 36) from `work/android-check-home/avd`. It was restarted headless; the log is at `work/android-check/emulator-5556-relaunch.log`.
- `adb install -r` succeeded. The device reports `com.licht.nuvx` versionCode=1, versionName=1.0.0, minSdk 26, targetSdk 36. The old-package-ID app is still installed alongside it as a separate app, as expected.
- After a launcher start, `.LauncherDefault` was displayed in about 4.3 s. The process stayed alive with the same PID (3271) and remained `topResumedActivity` 30 s and 60 s after launch, and was still alive later. The onboarding screen rendered; screenshot at `work/android-check/licht-1.0.0-launch.png`.
- Logcat, cleared before launch and saved to `work/android-check/logcat-licht-1.0.0-relaunch.txt`: 0 `FATAL EXCEPTION`, 0 ANR, 0 `AndroidRuntime` errors. `/data/anr` is empty.
- Non-fatal noise:
  - The app logs one error at startup: no JNI implementation was found for ONNX Runtime's `ai.onnxruntime.telemetry.HttpClient.isDeviceIdCollectionEnabled()`. It did not crash, but check it if ONNX telemetry or ProGuard rules matter.
  - `com.google.android.apps.wellbeing` logged errors because it could not open the stale APK path after the `install -r` reinstall. That is a system app, not ours.

Git hygiene: `keystore.properties` and `work/release-signing/licht-release.jks` are ignored. `/work/` was later added to `.gitignore` because the AVD files under `work/android-check-home/` were showing as untracked. `work/` no longer appears in `git status`. Nothing was staged or committed. No push, tag, or release change was made.

Still open: iOS build/signing evidence, the Community Canvas end-to-end test, the release workflow dry run (exactly four staged APKs), and a check of the legacy download routes.

## Next steps

1. Confirm the final four APKs have the intended package IDs, version name/code, matching `CN=licht` fingerprint, and expected native ABIs/legal assets. Rebuild if needed with `:app:assembleProdRelease :tv:assembleProdTvRelease :tv:assembleProdAutoRelease :wearos:assembleProdRelease` and `-Pnuvx.androidVersionCode=1 -Pnuvx.androidVersionName=1.0.0 -Pnuvx.releaseTag=v1.0.0`.
2. Install the final phone APK to `emulator-5556`, launch it, confirm the foreground activity/process stays alive, and check for `FATAL EXCEPTION`/ANR in logcat.
3. Update this handoff and `docs/superpowers/plans/2026-11-17-licht-android-1.0.0.md` with actual results. Leave remote tags/releases untouched.
4. Give the owner the GitHub setup steps for `CANVAS_GITHUB_TOKEN` when handing back; the Worker is `nuvx-listen`.
