# Mini-player Progress Indicator Implementation Plan

> **For agentic workers:** Execute the steps in order and preserve the existing uncommitted worktree changes.

**Goal:** Show accurate playback progress in the collapsed mini-player on every Compose target.

**Architecture:** Derive a clamped progress fraction from the existing `PlayerState` position and duration. Render a thin, accessible progress indicator inside the existing glass mini-player; keep all playback and gesture state in the existing player.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, existing `PlayerState`.

**Spec:** `C:/Users/bxane/Downloads/Claude/handover/NUVX_MASTER_SPEC.md`, 2026-11-03 UI addendum, collapsed mini-player requirements.

## Global Constraints

- Use shared KMP UI and existing player state; add no dependency.
- Keep the app name `nuvx` lowercase in user-facing text.
- Preserve accessible controls and existing swipe behavior.
- Show no fabricated progress when duration is unknown or invalid.
- Preserve original Nuvx branding and existing worktree changes.

## Review Focus

- A zero or negative duration must not divide or render a false active fraction; cover with a unit test.
- Negative positions and positions beyond duration must clamp to the valid range; cover with unit tests.
- The progress layer must not block mini-player taps or swipes; verify through composable modifier structure and a runtime smoke check if the emulator remains available.
- The indicator must remain visible against the existing glass surface and retain a screen-reader progress value.

---

### Task 1: Render current playback progress in the mini-player

**Files:**
- Modify: `core/shared/src/commonMain/kotlin/com/bxane/nuvx/ui/Chrome.kt`
- Test: `core/shared/src/commonTest/kotlin/com/bxane/nuvx/ui/MiniPlayerProgressTest.kt`

**Interfaces:**
- Consumes: `PlayerState.positionMs` and `PlayerState.durationMs`.
- Produces: `miniPlayerProgressFraction(positionMs: Long, durationMs: Long): Float` and an accessible visual progress line in `MiniPlayerPill`.

- [x] Write tests for unknown duration, start, midpoint, negative position, and past-end position.
- [x] Run the shared desktop test task to verify the tests fail before implementation.
- [x] Implement the fraction helper and thin progress indicator without changing playback state or gestures.
- [x] Run shared desktop and Android compile/test tasks; run `git diff --check`.
- [x] Update progress and external handoff with exact verification results. Do not commit or push without direct authorization.
