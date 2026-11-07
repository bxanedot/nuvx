# Music video and artist video implementation plan

## State

Authorized by the attached 25-section user specification. Worktree: `codex/canvas-share`, based on `origin/main` `774160ef05fb353b5300a511157bc659bf3fe33f`. Existing changes are dirty and must be preserved. No commit, push, deploy, or discard.

## Plan

1. **Model and backend** — add video kind/paired identity, `VideoStream`, a video-capable backend interface, InnerTubeX extraction and strict URL validation. Keep eXcor out of video routing. Add small tests first.
2. **Matching and catalog** — mark video search results, parse artist video shelves and durations/categories, add conservative matching with version and duration safeguards, and test representative response shapes.
3. **Android playback** — route video items through the existing MediaSession, merge audio and video sources with Media3, preserve signed URLs in memory, and attach the existing player only while its surface is visible.
4. **Desktop playback** — use one VLC media player and the existing stream proxy for both source URLs; add its embedded surface to the shared player callback. Verify compilation and document if VLC's sync behavior needs device/manual confirmation.
5. **Shared UI integration** — show categorized artist video shelves; wire search results to the player; add explicit song/video switching while preserving the current queue and position; retain current collapsed mini-player behavior and accessibility labels.
6. **Focused verification** — run new core parser/resolver/metadata tests, Android/desktop compile and unit-test tasks, relevant worker tests, and `git diff --check`. Inspect all diff paths and run the repository exclusion audit.
7. **Progress and handoff** — update `docs/PROGRESS.md`, `docs/BLOCKERS.md`, external `NUVX_MASTER_SPEC.md`, `PROMPT_HISTORY.md`, `CODEX_HANDOFF.md`, and `SESSION_STATE.json` with verified status only. Keep the exact attached prompt external as already archived.

## Later stages required for full spec

Artist background video lifecycle and settings; native PiP and video controls on supported devices; quality UI/persistence; captions and video-timed lyrics; artist/video radio; local/worker video catalog submissions; iOS app target and playback surface; TV/Wear layout validation; authorized-download path; screenshots and physical-device playback. Do not expose controls before the corresponding behavior exists.

### Task 8: Artist video catalog pagination

- [x] Parse continuation tokens and additional artist video shelves from a `browse` continuation response.
- [x] Request continuation pages through InnerTubeX's public `browse` API, regardless of the selected audio backend.
- [x] Add an accessible “Load more videos” action; append real results, de-duplicate video IDs, prevent repeated continuation tokens, and allow retry after transient failure.
- [x] Test parser, merge, router, repeated-token and empty-page behavior before implementation.
- [x] Run focused shared tests and the desktop/Android build/test matrix.
- [x] Keep native video extraction failure visible and truthful; pagination does not claim that video streams resolve.
- [x] Run `git diff --check` (exit 0; only existing CRLF-to-LF notice); update progress and external handoff.
- [x] Validate the parser against the documented, live-observed continuation-response contract. The upstream renderer schema is not guaranteed stable; keep the opt-in live probe and deterministic fixture tests, and recheck after InnerTubeX upgrades. See `docs/ARTIST_VIDEO_PAGINATION_RESEARCH.md`.
