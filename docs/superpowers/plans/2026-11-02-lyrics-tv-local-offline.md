# Lyrics, TV Navigation, and Local Playback Implementation Plan

> **For agentic workers:** Native implementation in this session; execute tasks in order with test-first changes.

**Goal:** Add compliant shared lyrics providers, a D-pad-friendly TV shell, and local music playback with a bounded Android playback cache.

**Architecture:** Common Kotlin owns provider parsing, lyric HTTP clients, the local-track identity, and the shared local-library UI. Android and desktop supply their own discovery and playback URI handling. The existing player and navigation APIs remain the integration points; no new dependency is planned.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Ktor, Android Media3, JVM/VLC.

**Spec:** ../handover/AGENT_CHANNEL.md request D/E/F; strict project constraints in the user-issued specification.

## Global Constraints

- Use service-owned public docs and terms; independently write every implementation.
- Do not scrape pages, use private endpoints, borrow tokens, or invent lyric timestamps.
- Lyrics fixtures contain fictional placeholder text only; live tests are opt-in.
- Lyrics.kt, LrcLib, and Claude-owned phone/screen files stay untouched.
- Local identifiers never reach YouTube resolution; YouTube audio is never saved as a file.
- Product display name remains lowercase nuvx; no new dependencies unless the platform APIs cannot meet the requirements.

## Review Focus

- Malformed or oversized TTML: parse only supported timed spans; reject unsafe/unsupported input.
- Missing/negative/invalid timing: never fabricate timestamps or return false word-sync.
- HTTP 429/key-gated provider response: stop without retrying or bypassing service limits.
- Forged or stale local URI: never pass a local identity to the streaming backend.
- Android permission denial: request only after opening local music and show no false empty-library result.
- TV remote focus: focused navigation control is visible and D-pad selection changes the active screen.

### Task 1: Permitted lyrics sources and TTML parsing

**Files:** Create common lyrics parser/provider files, common offline tests, and docs/LYRICS_PROVIDERS.md; modify only LyricsProviders.kt to register enabled providers.

**Interfaces:** Ttml.parse(text: String, offsetMs: Long = 0): Lyrics.Synced?; providers implement the existing LyricsProvider.

**Steps:** Test the TTML line/word timing and malformed-input behavior first; implement minimal supported TTML parsing. Add offline HTTP fixture tests with invented text for permitted services. Register only services with documented third-party use; keep LRCLIB unchanged. Document terms, status, and provider-specific rate-limit behavior for every requested service. Add an environment-gated live test that asserts result shape only.

### Task 2: TV-only navigation shell

**Files:** Create TvShell.kt; edit only the tv parameter/delegation in NuvxApp.kt; set tv for the Android TV variant in MainActivity.kt; add narrow UI/focus tests if the current test harness supports them.

**Interfaces:** NuvxApp(..., tv: Boolean = false) delegates TV rendering to TvShell; the shell reuses public home, explore, library, search, and player composables.

**Steps:** Add a TV navigation/focus behavior test where available; implement a centered pill bar, horizontal content shelves, logical D-pad order, and a clear focus treatment. Build/install the TV variant and capture navigation, playback, player-sheet, and Together evidence on a headless emulator.

### Task 3: Local music and bounded playback cache

**Files:** Add common local identity/catalog/composable and tests; modify common model/player/resolution integration; add Android MediaStore/runtime permission and Media3 cache handling; add desktop folder discovery and local URI playback; write docs/OFFLINE.md.

**Interfaces:** Local tracks use a local: ID; LocalMusicCatalog exposes locally discovered tracks and refresh/open state; local playback bypasses BackendRouter.resolveAudio.

**Steps:** Test local-ID recognition and non-routing first; implement the common catalog contract. Add Android discovery that requests audio permission only after open, desktop directory selection, and direct native playback for trusted local URIs. Add a size-capped SimpleCache to Android remote playback; keep local media and cached stream paths behavior distinct. Add focused tests for platform URI handling and record platform limitations.

### Final verification

Run shared desktop tests, Android unit tests/lint and TV/Wear builds, desktop tests, then the headless TV flow and screenshots. Run git diff --check and an in-repository prohibited-reference audit; re-fetch/merge origin/main, rerun relevant tests, commit, push the task branch, and post DONE with exact evidence and unverified items.