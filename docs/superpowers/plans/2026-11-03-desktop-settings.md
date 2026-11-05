# Desktop Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement persisted supported settings and adapt Nuvx's complete existing route set to the approved desktop composition.

**Architecture:** Store a small `LyricsProviderSettings` value through the existing `KeyValueStore`, pass it through `NuvxApp` to Settings and Now Playing, and let `LyricsChain` resolve only configured enabled providers. Keep one shared player and route state, with a desktop-only three-column frame using the Nuvx-owned icon set.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx.coroutines, existing `KeyValueStore` and `LyricsChain`.

**Spec:** `docs/superpowers/specs/2026-11-03-desktop-settings.md`

## Global Constraints

- Nuvx uses independently implemented application code and its own brand/icon assets.
- InnerTubeX remains the default engine; eXcor remains an optional alternative.
- lrc.red is the fresh-install first/default lyrics provider.
- Do not expose fake or unauthorized controls.
- Preserve all existing uncommitted work; do not commit, push, deploy, or discard.

## Review Focus

- Empty, duplicated, or unknown names in stored lyrics preferences normalize safely.
- Disabling the selected lyrics source picks the first enabled source.
- A provider failure does not prevent later enabled sources from being tried.
- Source-supplied word timing wins over earlier line-only lyrics when requested.
- Turning Canvas off prevents lookup/rendering without interrupting audio.

---

### Task 1: Persisted lyric provider settings

**Files:**
- Create: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/core/LyricsProviderSettings.kt`
- Test: `core/shared/src/commonTest/kotlin/com/bxane/nuvx/core/LyricsProviderSettingsTest.kt`

**Interfaces:**
- `LyricsProviderSettings.load(store: KeyValueStore, providerNames: List<String>)`
- `LyricsProviderSettings.save(store: KeyValueStore)`
- Fields: `order: List<String>`, `disabled: Set<String>`, `preferredSource: String?`, `preferWordSynced: Boolean`
- `enabled(providerNames: List<String>): List<String>` returns known names in saved order, with omitted registered names appended.

- [x] **Step 1: Write tests** for fresh defaults (`lrc.red` first/default, word timing preferred), unknown/duplicate normalization, disabled-default fallback, and save/load round-trip.
- [x] **Step 2: Run** `:shared:desktopTest --tests com.bxane.nuvx.core.LyricsProviderSettingsTest`; confirm the missing type/helper causes the expected failure.
- [x] **Step 3: Implement** newline-delimited persistence with the existing `KeyValueStore`; retain only known names and preserve provider registration order for missing entries.
- [x] **Step 4: Re-run** the focused test and the full `:shared:desktopTest` suite.

### Task 2: Configurable and word-timing-aware lyrics lookup

**Files:**
- Modify: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/core/Lyrics.kt`
- Test: `core/shared/src/commonTest/kotlin/com/bxane/nuvx/core/LyricsChainTest.kt`

**Interfaces:**
- Extend `LyricsChain.find` with defaulted `providerOrder`, `disabledSources`, and `preferWordSynced` arguments so existing callers keep their behavior.

- [x] **Step 1: Write tests** for configured order, disabled-source skipping, fallback after a selected provider returns nothing, and word-timed preference over line-only lyrics.
- [x] **Step 2: Run** `:shared:desktopTest --tests com.bxane.nuvx.core.LyricsChainTest`; confirm expected failures.
- [x] **Step 3: Implement** ordered active providers and timing preference; keep cancellation propagation and existing error fallback.
- [x] **Step 4: Re-run** the focused test and full shared tests, including a selected line-only provider regression.

### Task 3: Settings and player wiring

**Files:**
- Modify: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/ui/NuvxApp.kt`
- Modify: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/ui/NowPlayingSheet.kt`
- Modify: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/ui/NuvxApp.kt` settings composables

**Interfaces:**
- `NuvxApp` owns one persisted `LyricsProviderSettings` value and passes it with an update callback to Settings and Now Playing.
- Settings exposes default source, provider enablement/order, word-timing preference, current engine/fallback, full-bleed art, community Canvas, Listen Together, library routes, and About.
- Now Playing shows only enabled lyric providers, writes the selected source back to the shared preference, and uses the configured fallback order.

- [x] **Step 1: Implement** the settings rows using existing Nuvx grouped-row, switch, and icon components; persist each change immediately.
- [x] **Step 2: Gate** the community Canvas lookup/render path with the persisted setting while retaining the current default-on behavior.
- [x] **Step 3: Wire** the in-player provider chooser to the same preference state and show an empty state if no provider is registered.
- [x] **Step 4: Run** shared desktop tests, desktop app tests, Android mobile/TV/Wear assemble and unit tests; run `git diff --check`.

### Task 4: Desktop shell

**Files:**
- Create: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/ui/DesktopAppFrame.kt`
- Modify: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/ui/NuvxApp.kt`, `Chrome.kt`, and existing route screens

- [x] Keep shared Home/Search/Explore/Library/detail routes in the center area.
- [x] Add left navigation, responsive Queue/Lyrics inspector, persistent seek/transport/volume bar, and use current app data only.
- [x] Keep route content accessible and remove mobile chrome padding from desktop screens.
- [x] Verify shared and Android compile/test matrix, Windows app image, and whitespace checks. Windows Settings and the volume preference are visually verified; whole-route/gesture inspection remains pending.

### Task 5: State and handoff

- [x] Update `docs/PROGRESS.md` and the external handoff with verified controls, files, and commands.
- [x] Leave all work uncommitted and preserve every pre-existing dirty file.

### Task 6: Desktop volume-bar preference

**Files:**
- Create: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/core/DesktopPlaybackSettings.kt`
- Create: `core/shared/src/commonTest/kotlin/com/bxane/nuvx/core/DesktopPlaybackSettingsTest.kt`
- Modify: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/ui/NuvxApp.kt`
- Modify: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/ui/DesktopAppFrame.kt`

**Interfaces:**
- Persist whether the desktop player bar hides its volume slider through the existing `KeyValueStore`.
- Expose the option only in desktop Settings; keep the slider visible by default.

- [x] Write a test proving the setting defaults to visible and the hidden value round-trips through the store; run it and observe the expected missing-setting failure.
- [x] Implement the minimal persisted setting and wire its Settings switch to the existing player bar.
- [x] Run the full `:shared:desktopTest` suite, `:desktopApp:test`, rebuild the Windows distribution, and run `git diff --check`.
- [x] Visually confirm the Settings switch hides and restores the desktop volume slider, persists across restart, and return it to the default visible state.

### Task 7: Match the latest visible desktop layout

- [x] Move transport, seek, repeat, shuffle, and volume into a compact top bar; open the Queue/Lyrics inspector only on request.
- [x] Add a History sidebar route backed by the existing local playback history and Nuvx-owned icon set.
- [x] Run `:shared:desktopTest`, `:desktopApp:test`, and `git diff --check`.
- [x] Rebuild and visually inspect the Windows app image. Home, Settings, Search, Explore, and Library were checked at 966×695; expanded player/inspector and remaining routes are still pending.
- [x] Add persisted tray and keep-playing preferences plus the native system-tray menu, using the existing Nuvx mark.
- [ ] Implement localization, repeat-avoidance for actual autoplay, and complete decoder statistics only after their behavior/data is available.

### Task 8: Tray playback and close-to-tray

- [x] Add persisted, normalized tray/keep-playing preferences and tests for their defaults, round-trip and dependency.
- [x] Add an AWT tray menu with Nuvx's own mark and play/pause/previous/next/open/exit actions; hide the window to tray only when both settings are enabled.
- [ ] Build a fresh Windows app image and verify tray open, transport actions, close-to-tray and exit. This is blocked while the currently running Nuvx image is open.
- [ ] Run Linux and macOS host builds/runtime checks; system-tray support is conditional on `SystemTray.isSupported()`.

### Task 9: Persisted radio repeat avoidance

**Files:**
- Create: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/core/RadioSettings.kt`
- Test: `core/shared/src/commonTest/kotlin/com/bxane/nuvx/core/RadioSettingsTest.kt`
- Modify: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/core/Radio.kt`, `ui/NowPlayingSheet.kt`, `ui/NuvxApp.kt`, and `ui/TvShell.kt`
- Test: `core/shared/src/commonTest/kotlin/com/bxane/nuvx/core/LyricsRadioTest.kt`

**Behavior:**
- Default to avoiding repeats in a radio session and persist the user choice through `KeyValueStore`.
- “Start radio from this song” starts the existing `RadioStation`; subsequent player queue/index changes request refills near the end.
- The Settings switch applies immediately to the active station and survives app recreation on desktop, Android, and TV.
- A queue replaced by unrelated playback stops the old station; a late refill from an older station must not append to a newer queue.

- [x] Write tests for default/persisted preference, off-mode repeats while tracking seen IDs, and replacing the active session queue.
- [x] Run the focused preference and radio tests; the initial compile failed on the missing preference and avoidance API as expected.
- [x] Implement the preference, station lifecycle guards, and shared player/settings wiring using existing interfaces only.
- [x] Run focused shared tests, the shared/desktop/Android verification matrix, and `git diff --check`.
- [x] Update progress and external handoff with verified behavior; leave all work uncommitted.

### Task 10: Player diagnostics

- [x] Add a scrollable Settings diagnostics dialog from state already available to the shared app.
- [x] Allowlist operation labels and omit raw errors, backend details, tokens, and signed media addresses.
- [x] Add a shared regression test proving useful state is shown without exposing private error/address values.
- [x] Run the focused test and full shared/desktop plus Android mobile/TV/Wear build/unit-test matrix.
- [ ] Open the dialog in a refreshed Windows app image and inspect it visually; current Nuvx processes hold the output image.
