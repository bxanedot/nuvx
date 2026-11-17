# Nuvx master plan

The one handoff document for this programme. Update it in the same pull request as the work it describes. Last updated 2026-11-17. Read-only references and the per-issue matrix live in [`audits/REFERENCE_REPOS_AUDIT.md`](audits/REFERENCE_REPOS_AUDIT.md).

## Open pull requests

Merged on 2026-11-17: 283, 284, 285, 286, 288, 289, 301, 303, and the web and Worker dependency updates 291, 296, 299.

| Branch | What | Evidence |
|---|---|---|
| `claude/brave-bohr-qzzh9m` | Licht 1.0.0: the candidate branch, current `main`, the Nightly updates switch and update chain (from 302), one universal macOS image, the contact email, the launch-crash fix and the desktop/Android dependency updates (from 304). Held: merging to `main` starts a release. | see the PR description |

PRs 287, 302 and 304 are folded into that branch.

## Baseline on `main` at `e1cafdb`

Desktop 219 pass, 27 ignored. Android phone 1,343 pass, 1 skipped. Web 240 and 2 pass. Worker 27 pass. Shared Kotlin suites pass. CI: the Swift app builds on Mac runners; the "Apple builds" workflow can show red when the Apple silicon runner waits out its 15 minute limit; nightly release run 200 failed at the macOS installer upload with "release not found" and its root cause is not established.

## Waiting on the owner

1. **Oracle server preparation** (admin, once): enable lingering for `nuvxdeploy`, install Caddy, open ports 80 and 443. Steps in `ORACLE_DEPLOYMENT.md` (PR 284). Then the workflow's `check` action.
2. **Sync host name**, and whether off-server backups may run on a schedule. The `backup` action needs a `NUVX_BACKUP_PASSPHRASE` secret.
3. **Redeploy the Cloudflare Worker** after PRs 283 and 288 merge. Neither fix is live until then.
4. **Rename leftovers:** the new Worker address (16 references), profile links, contact email.
5. **GlyphStudio dependency**, see below.

## GlyphStudio (read 2026-11-16)

- The repository is now `lylicht/GlyphStudio`, Apache-2.0, with a Compose Multiplatform library (`com.composables.sfsymbols`) for Android, desktop, iOS and web. Latest release is `v2.0.0`. Nuvx pins `v1.0.0` in `core/sharedUi/build.gradle.kts` under the repository's old JitPack group. Whether JitPack serves the group under the new owner name is unchecked.
- The library has no reduced-motion handling, so every call site must gate itself. Nuvx has three animated call sites on Android: tab icon bounce, reveal bounce (already gated), and the two Radio screen indicators. The tab bounce and the Radio indicators were not gated; the next PR fixes that.
- **Legal question for the maintainer, not decided:** the library states that SF Symbols are Apple's property, to be used under Apple's terms and not in app icons or logos. Android and desktop builds ship those glyphs. Apple's current terms need a read to confirm that is permitted, or those platforms need a different icon set.
- Not started: the central icon registry, other-platform adapters, visual and animation-state tests.

## Next, in order

1. Read the "Investigate" rows of the audit: download durability, gapless transitions, desktop WebView reuse, MPRIS, PipeWire sample rate.
2. First sync client, off by default: operation kinds and schemas for playlists, favorites and settings, with one fixture every client replays.
3. Nuvx Connect on top of QR pairing: one playback owner, remote control, handoff.
4. Run Ponytail's repository audit per subsystem and record it. Ponytail 5.3.3 is installed and its rules were applied to the changes above, but the full audit has not been run. Size for planning: web about 590k lines including vendored code, desktop 187k, iOS 140k, Android 138k, Apple core 78k.
5. Playlist import and export, history and restore, Smart Offline, audio profiles, diagnostics export.

## Decisions that need evidence and approval first

- **macOS: SwiftUI shell or the current Rust app.** `core/apple` already holds SwiftUI Mac views while the shipped `.dmg` is the Rust app. Measuring start-up, memory and media keys needs a Mac, which the cloud container is not.
- **Shared Rust core versus Kotlin Multiplatform versus React Native.** No prototype yet. Kotlin Multiplatform already carries shared code on Android and iOS.
- **tvOS, Android Automotive, CarPlay.** Not started. CarPlay needs an Apple entitlement nuvx does not hold.
- **Design system and per-device-class specifications.** Not started; representative screens need the maintainer's sign-off first.

No major migration is started without approval.

## Not started

The repository-wide refactor, performance baselines, visual regression tests, SBOM and release signing, and every feature in the brief not listed above.
