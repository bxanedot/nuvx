# Claude changes for 1.1.0

Work done on top of Codex's 1.1.0 commits (55eba12, 0263d3c, 4064c4e, 07815c2). Start point: 07815c2; rebased onto origin/main ed51d1d (which now includes the restore-license-headers work) before pushing.

## 1. Web/extension JioSaavn + Radio Javan fallback
- What: `source.fetch` bridge method (capability `sources`), validated in `shared/src/bridge/sources.ts` and again in `extension/lib/sourceFetch.ts` (provider, https, host, path, `__call`, query keys, no credentials/port, `redirect: error`). Optional host permissions for the two hosts, granted through the existing grant page. App: `playback/sources/{match,providers,settings}.ts` mirror desktop `sources.rs` and `matching.rs`; toggles in Settings (both off by default); `state/player.ts` tries enabled sources after a YouTube load failure (JioSaavn, then Radio Javan) and plays a seekable file with real duration and media session, separate from live radio (`deck.live`).
- Files: `app/web/shared/src/bridge/*`, `app/web/extension/{entrypoints/background.ts,lib/*,wxt.config.ts}`, `app/web/app/src/{playback/sources,state/player.ts,pages/IntegrationSettings.tsx,pages/SettingsPage.tsx}`.
- Commit: "Add JioSaavn and Radio Javan fallback to the web player" (see `git log`).
- Verified after rebase: `pnpm test` (shared 2, app 240 passing; synthetic tests only, no live calls). `pnpm typecheck` passes for shared and extension; the app has one error in `src/state/player.test.ts(220)` that already exists on origin/main (not touched here). Not verified: real extension in a browser, real provider responses, mid-stream YouTube failure (only load failures fall back).

## 2. Source defaults on Apple/iOS
- What: the legacy `jiosaavn_enabled` flag defaulted to on with no UI, so JioSaavn search results and resolution ignored the source switch. It now defaults off and `SourceRegistry.publish` mirrors the JioSaavn switch into it.
- Files: `app/ios/shared/.../SourceRegistry.kt`, `.../AppSettings.kt`, `core/apple/src/UI/SearchView.swift`, `core/apple/src/PlaybackSession/PlaybackController+SourceResolution.swift`.
- Verified: not built or tested (no Xcode/Kotlin-native here). Behavior change: installs that never touched the switch lose JioSaavn until they enable it.
- Not changed: eXcor/Hi-Res/add-on defaults. Android/KMP add-ons are enabled when the user adds them (an explicit action); desktop `hires_source` defaults empty. Radio Javan/JioSaavn are off by default on Android/KMP/desktop.

## 3. Audits (no code change)
- Playlist artwork: Android (remote, library, offline, local, picker for any PLAYLIST), Apple, desktop, web (override by id, covers YouTube playlists) all have it from adb1e75. Read-only audit; not run on devices. TV has no artwork picker (no gallery on TV; nothing added).
- Device/offline playlists: Android phone shelf done (4064c4e); TV lists "Downloaded" and "On This Device"; desktop has a local "Saved on this device" collection; web has browser-saved playlists only (a browser has no device files, nothing added). iOS not exercised.
- Discord: all clients use `https://nuvx.lol/open`; `core/website/open.js` tries `nuvx://open-player`, with a nuvx.lol fallback message. Android phone and Apple handle `open-player`; desktop's registered scheme launches the app (its parser only acts on `party`); TV has no handler (not needed).
- Desktop: `cargo test` 210 passed, 27 ignored before the rebase (needed libgtk-3/webkit2gtk dev packages in the container); not re-run after it, and my changes don't touch desktop.

## Not done
- Android unit tests/Gradle not run. `androidVersionCode` was not located in the files I checked; versionCode 11 and no release or tag were not touched.
- Nothing pushed to `claude/restore-license-headers` or merged from it.
