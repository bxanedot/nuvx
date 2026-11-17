# Licht Android 1.0.0 Candidate Plan

> **For agentic workers:** Execute inline with `superpowers:executing-plans`; do not publish while the owner says to hold the release.

**Goal:** Prepare a Nuvx candidate across platforms with the `licht` identity, version `1.0.0`, and Android version code `1` for each Android target.

**Architecture:** Keep the Android app, TV, Auto, and Wear build variants on the shared `com.licht.nuvx` namespace and release signer. Stage one plain `.apk` per target; keep the update checker and download endpoint able to read existing release assets. Give the old version-code-10 release its own archived tag before restoring `v1.0.0` to the new build.

**Tech Stack:** Kotlin, Gradle, Android SDK, GitHub Actions, Node.js.

**Spec:** The owner's instructions in the current Codex thread.

## Global Constraints

- Android phone, TV, Auto, and Wear packages use version name `1.0.0` and version code `1`.
- Desktop, iOS, and browser extension release metadata use version `1.0.0`.
- Each Android target produces one APK asset with no GMS/FOSS copies and no user-facing variant label.
- App identity/package references use `licht`; release APKs use a signing certificate with subject `CN=licht`.
- Preserve existing published tags/releases until the owner explicitly resumes release work.
- Do not push, retag, rename a GitHub release, or publish while the owner says to hold.
- Archive the previous Android version-code-10 `v1.0.0` release/tag as `old-nuvx-v1.0.0`, retaining its commit and assets.

## Review Focus

- Confirm the APK package IDs and code on all four targets with `aapt2`.
- Confirm the release APK signature matches the new pinned certificate.
- Install and launch the phone APK in the Android emulator; check logs for fatal errors.
- Confirm the release workflow stages exactly four APKs and no distribution aliases.
- Confirm legacy download routes still find assets from old stable releases.
- Apple bundle/group identifiers were also changed; Apple signing/build availability remains unverified on this Windows host.
- Existing desktop installs already at `1.0.0` may need a manual update because their previous and new stable release tags share the same version.

---

### Task 1: Android identity and package rename

**Files:** Android Gradle modules, Kotlin/native source package paths, app manifests, shared Apple package references, and their package assertions.

- [x] Rename source and package references from the previous identity to `licht`.
- [x] Set Android app IDs and shared distribution mode for one package.

### Task 2: Single APK release and download selection

**Files:** `.github/workflows/android-nightly.yml`, `app/android/mobile/src/main/java/com/licht/nuvx/data/AppUpdateChecker.kt`, updater tests, `core/website/api/download.js`, website tests, `README.md`, `CHANGELOG.md`.

- [x] Stage one plain APK for phone, TV, Auto, and Wear.
- [x] Select plain current assets while retaining old release-name fallbacks.
- [x] Update download links and Android release notes.

### Task 3: `licht` signing identity

**Files:** `.github/workflows/android-nightly.yml`; GitHub Actions secrets; local ignored keystore used only for candidate verification.

- [x] Create a release certificate with subject `CN=licht` and update the repository signing secrets.
- [x] Pin the new certificate fingerprint in release verification.

### Task 4: Version `1.0.0`, code `1`, and candidate verification

**Files:** release metadata, Android build outputs, and emulator evidence under ignored `work/`.

- [x] Build all Android release targets with name `1.0.0` and code `1`.
- [x] Verify package metadata, signer, native libraries, and legal assets for each APK. All four: `1.0.0`/`1`, `com.licht.nuvx`/`.tv`/`.auto`/Wear `com.licht.nuvx`, `CN=licht` SHA-256 `833677fb…0fb9c`, 4 ABIs. Details are in `docs/CLAUDE_HANDOFF_LICHT_1.0.0.md`.
- [x] Install and launch the signed phone APK in the Android emulator. On `emulator-5556` (API 36) the process stayed alive and in the foreground for 60 s or more, with no `FATAL EXCEPTION` and no ANR.
- [ ] Keep all GitHub tag/release changes pending the owner's instruction to resume publication.
