# Nightly previews

`.github/workflows/nightly.yml` builds mobile, TV, and Wear OS debug APKs in one Android job, plus native desktop packages in a Windows, Linux, and macOS matrix, every night at 02:25 UTC. A manual run can also produce the same preview packages. The workflow runs shared tests, each Android variant's unit tests, Android lint, and desktop tests before uploading 14-day workflow artifacts. Each Android APK is built once per run rather than once on every desktop runner. CI uses JDK 27 to match the shared module's JVM toolchain.

Publishing a GitHub prerelease is separately gated. A scheduled run publishes only when the repository variable `NUVX_ENABLE_NIGHTLY_RELEASES` is set to `true`; a manual run publishes only when its `publish_nightly` input is selected. Publication is limited to `main`, uses a unique `nuvx-nightly-*` tag, and marks the release as a prerelease. The default is to build artifacts without publishing anything.

To enable scheduled prereleases after this workflow has reached `main`, add the repository Actions variable `NUVX_ENABLE_NIGHTLY_RELEASES` with value `true`. To run a one-off preview, open **Actions → Nightly preview → Run workflow** and select `publish_nightly` only when a prerelease is wanted. Leave the variable unset to keep nightly builds as downloadable Actions artifacts only. No personal access token is needed; the publish job uses the run-scoped `GITHUB_TOKEN` with only Actions-read and contents-write permissions.

These packages are development previews, not stable releases:

- Android APKs are signed with the ephemeral runner's debug key. Replacing an APK signed by a different key requires uninstalling the existing app first.
- Desktop packages are versioned monotonically for upgrade-aware installers, but macOS output is unsigned and not notarized.
- The Windows build prepares its bundled VLC runtime. Linux and macOS users need a compatible system VLC installation.
- No store bundle or production signing key is created or uploaded.
- The workflow does not build an iOS IPA; Apple-hosted build/signing remains a separate blocker.

The workflow is currently uncommitted in the Codex worktree. It will not run on GitHub until the change reaches the repository's default branch. No prerelease was published while preparing it.
