# Nightly previews

`.github/workflows/nightly.yml` builds Android mobile, TV, and Wear OS debug APKs every night at 02:25 UTC. A manual run can also produce the same preview packages. The workflow runs shared and Android variant checks plus Android lint before uploading 14-day workflow artifacts. CI uses JDK 27 to match the shared module's JVM toolchain. Desktop and iOS/iPadOS packages are outside the current active scope.

Publishing a GitHub prerelease is separately gated. A scheduled run publishes only when the repository variable `NUVX_ENABLE_NIGHTLY_RELEASES` is set to `true`; a manual run publishes only when its `publish_nightly` input is selected. Publication is limited to `main`, uses a unique `nuvx-nightly-*` tag, and marks the release as a prerelease. The default is to build artifacts without publishing anything.

To enable scheduled prereleases after this workflow has reached `main`, add the repository Actions variable `NUVX_ENABLE_NIGHTLY_RELEASES` with value `true`. To run a one-off preview, open **Actions → Nightly preview → Run workflow** and select `publish_nightly` only when a prerelease is wanted. Leave the variable unset to keep nightly builds as downloadable Actions artifacts only. No personal access token is needed; the publish job uses the run-scoped `GITHUB_TOKEN` with only Actions-read and contents-write permissions.

These packages are Android development previews, not stable releases:

- Android APKs are signed with the ephemeral runner's debug key. Replacing an APK signed by a different key requires uninstalling the existing app first.
- No store bundle or production signing key is created or uploaded.
- The workflow does not build desktop installers or an iOS IPA.

The workflow change is in draft PR #4 and will run from the default branch after merge. No prerelease has been published.
