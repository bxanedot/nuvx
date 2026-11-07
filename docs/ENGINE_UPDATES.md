# Upstream engine links and updates

Every online Gradle build checks the engines' own GitHub repositories:

- InnerTubeX: `MetrolistGroup/innertubex`, newest stable release at or above the recorded catalog version. Preview releases are ignored. Gradle resolves that release through the existing JitPack dependency.
- eXcor: `bxanedot/eXcor`, current default-branch commit. Only the library build/source/fixtures/licences are fetched into `.gradle/engines/excor-<immutable SHA>`; unrelated applications and historical engines are not imported.

Nuvx tracks the resolver, upstream identities, known pins and local build-settings template. Engine source is a generated build-cache input, not a manually maintained copy or sibling checkout. Python 3.11+ is required alongside the normal JDK/Android SDK/Gradle dependencies. Each resolution prints the selected version/SHA and records `.gradle/engines/resolved.properties`.

Online builds intentionally float with upstream: the same Nuvx commit can resolve different engines on different days. Latest does not mean tested or compatible. `--offline` reuses the last complete resolution and Gradle caches; the first build must be online. Network/API or archive-validation failures stop resolution instead of accepting partial source. Public API access can be rate-limited; CI supplies its scoped GitHub token without printing it or forwarding it to archive hosts.

## Review PRs

The daily/manual `update-engines.yml` workflow also records upstream changes as one PR on `automation/engine-updates`. It updates only `gradle/libs.versions.toml` and `core/engines/UPSTREAM.json`, runs the offline resolver tests and dispatches the full `build.yml` matrix for that branch. The PR records known versions; online builds still check the live upstream links, as requested. There is no auto-merge or installed-app code replacement.

The resolver is standard-library Python. Downloads and expanded archives are bounded; archive traversal, links and special files are rejected. Source revisions occupy separate immutable directories, preserving any older build in progress. Update proposals require a clean working tree and use staged pin replacement with rollback. Failed rollback retains recovery data for inspection.

```sh
python3 -B -m unittest discover -s core/scripts -p test_update_engines.py -v
# From a clean checkout, record a proposal without committing or pushing:
python3 -B core/scripts/update-engines.py
```

The scheduled workflow becomes active only after it reaches the default branch. Actions must be enabled and allowed to create PRs and dispatch workflows. Token-created PRs do not reliably trigger further workflows, so the updater explicitly dispatches `build.yml`; normal repository approval rules still apply. The updater does not change repository permissions, sign, merge, deploy or publish a release. Passing checks do not prove live playback or provider authorization.
