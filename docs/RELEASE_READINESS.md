# Release readiness

How a nuvx release is made, as done for 1.0.0 on 2026-11-17.

## Stable release

1. Merge the release's pull request into `main`. An ordinary push to `main` does not start a release.
2. Tag the merge commit `vX.Y.Z` and push the tag. That runs "Nuvx releases" (`android-nightly.yml`):
   - `prepare` checks that the tag matches the version in `build.gradle.kts`, `app/desktop/Cargo.toml` and `app/web/extension/package.json`, then creates a draft with the CHANGELOG section `## X.Y.Z`;
   - Android, Windows, Linux, macOS (both architectures, joined into one universal `.dmg`) and the browser extensions build in parallel;
   - each APK must match the expected package, version name and code, and be signed with the pinned `CN=licht` certificate. The check names the key it found;
   - `publish` makes the release Latest only if every build passed. Otherwise it deletes the draft.
3. Attach the iPhone build: run "Apple builds" manually on the tag's ref with `release_tag = vX.Y.Z`. It refuses unless the ref is the tag's commit.

## Signing

The Android key lives in four repository secrets: `NUVX_KEYSTORE_BASE64`, `NUVX_KEYSTORE_PASSWORD`, `NUVX_KEY_ALIAS` and `NUVX_KEY_PASSWORD`. They come from `work/release-signing/licht-release.jks` on the owner's PC; the certificate's SHA-256 is `833677fb…0fb9c`. Desktop builds are not code-signed; the macOS `.dmg` is not notarized.

## Updates

- Updaters only offer releases newer than the installed one, and skip anything named "old" and pre-releases that are not nightlies.
- Nightlies are offered only with Settings → Miscellaneous → Nightly updates on.
- The desktop checks each download against GitHub's SHA-256 digest.

## Known issues

- **Mac runners:** "Apple builds" and the macOS release jobs wait for GitHub's macOS runners. A job left queued too long can be cancelled, and the stable build then fails safely; run it again.
- **iPhone builds:** not attached automatically (step 3).

## Before the next release

- Merge PR 312.
- Redeploy the Worker if `core/worker` changed.
- Write the CHANGELOG section `## X.Y.Z`.
