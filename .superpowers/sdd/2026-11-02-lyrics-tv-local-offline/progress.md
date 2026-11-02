# SDD ledger — plan: `docs/superpowers/plans/2026-11-02-lyrics-tv-local-offline.md`

Updated 2026-11-03. Fresh worktree `codex/lyrics-tv-local-offline-v5`, based on `origin/main` `ed38e2654d64f31c31f188fe553f99c474845f38`. D/E/F implementation is complete in the worktree; final checkpoint/push and channel handoff remain.

## D — lyrics providers and timing

- Implemented permitted documented providers in common code, a registration chain, bounded TTML parsing, and offline tests with placeholder text only. Opt-in live checks assert result shape only and are not represented as run.
- `:shared:desktopTest` and the full verification command pass on this Windows host.
- Public API availability/terms and unsupported/key-required providers are documented in `docs/LYRICS_PROVIDERS.md`.

## E — Android TV shell

- Added a TV-only shell and navigation state, connected through the explicitly approved TV flag/delegation, and set Android's Leanback feature detection.
- API 36 headless TV D-pad verified top-tab focus, Home→Explore movement, Library routing, and local-library route. Screenshots are in the Codex outputs folder: `nuvx-tv-explore-headless.png`, `nuvx-tv-library-tab.png`, and `nuvx-tv-local-library-screen.png`.
- The Settings/profile icon is in Claude-owned `Chrome.kt`; a focus treatment question was posted to `AGENT_CHANNEL.md` at 02:00. It is not changed by Codex. Full focus traversal and physical remote behavior remain unverified.

## F — local library and bounded cache

- Added local track identity/metadata, Android MediaStore and desktop folder-scanner adapters, shared “On this device” routing, and direct local playback paths. Local IDs are excluded from remote radio and Listen Together track sharing. The bounded Android cache applies to HTTP(S) streams, not local file URIs.
- Desktop scanner and playback routing have unit coverage. On API 36 TV, entering the route triggers the Android permission prompt; permission was denied, so MediaStore listing and local audio output were not tested. Desktop folder picker and real audio output were not exercised.
- Denial now shows a retry action and a clear reason, without simultaneously reporting an empty library. Screenshot: `nuvx-tv-local-permission.png`.

## Verification

- Latest full command on this branch passed: `:shared:desktopTest :androidApp:testMobileDebugUnitTest :androidApp:assembleTvDebug :androidApp:assembleWearDebug :androidApp:lint :desktopApp:test`.
- Targeted `:desktopApp:test --tests com.bxane.nuvx.desktop.DesktopLocalMusicTest` passed.
- Current worktree changes are uncommitted. Before pushing: inspect Claude channel, fetch and merge any newer `origin/main`, rerun relevant checks, then commit and push this branch only.
- Release claims remain out of scope: live provider availability, iOS/macOS builds, physical TV/watch validation, permission-granted MediaStore scan, and real local playback are not certified.

## Design decisions retained

- Use `Dispatchers.IO` for desktop directory scanning; folder selection stays on the caller's UI thread and StateFlow supports thread-safe updates.
- Route Media3 `content:`/`file:` sources directly, and HTTP(S) through the streaming cache. New remote schemes require an explicit route update.
- Reject `local:` radio seeds while preserving existing nonlocal station/backend ID behavior.
