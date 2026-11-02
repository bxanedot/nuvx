# Design review: TV and local music library

Reviewed on 2026-11-03. Scope: the shared Compose TV shell, local-library screen, and their Library navigation wiring. The review uses the Apple Design skill's general principles for accessibility, layout, focus, and privacy. Its extracted guidance omits tvOS-specific sections, so Android TV input behavior remains subject to Android TV verification. The Watch screen was not changed in this work and is not reviewed here.

## Summary

**Good.** The screens keep music content central on a quiet black canvas, with bxane's silver-white mark and blue accent providing the Nuvx identity. The TV shell keeps navigation separate from content, and the local-library route explains why audio access is needed. This assessment is based on source review and headless API 36 emulator screenshots, not physical-device testing.

## What works

- The seven TV destinations are labeled and act as navigation. The selected tab and focused tab have distinct visual treatments. The top bar is scrollable when it cannot fit. This follows `tab-bars.md › Best practices`, which recommends visible, labeled navigation tabs.
- TV tabs and the back control have a 48 dp minimum target; focus is shown with a high-contrast white outline. The back control now has the same explicit treatment as the top tabs. This applies `cross-platform.md › Conventions to check` and the focus visibility guidance in `focus-and-selection.md › Best practices`.
- Local tracks are presented as a scrollable list with title and artist, an established pattern for scanning text-heavy content (`lists-and-tables.md › Best practices`). Loading, empty, and error states have visible text.
- Android asks for media permission in the local-library flow, and its purpose is stated on the screen. The permission dialog was observed only after entering that route, consistent with `privacy.md › Requesting permission`.
- If a person denies access, the screen now shows a retry action and a clear explanation without also claiming that the device has no audio files.
- Glass styling is confined to the floating TV navigation control; the local-library action card and track content use opaque surfaces. This respects the control/content separation in `layout.md › Visual hierarchy` and `liquid-glass.md › Review checklist`.
- Text uses scalable `sp` units and content lists can scroll. The code keeps focus and selection visible without relying on color alone (`accessibility.md › Vision` and `color.md › Inclusive color`).

## Improvements

- **Low — Test larger text and narrower TV widths.** The Compose text uses scalable units and the main controls have minimum heights, but this review did not run the local-library screen at 1.6x font scale or on a smaller TV viewport. Verify those conditions before claiming full accessibility coverage (`typography.md › Supporting Dynamic Type`, `layout.md › Adaptability`).
- **Low — Verify all TV focus paths.** The screenshot confirms visible focus on the top tab, and the new back control has an explicit focus state. D-pad traversal through every library row and local track row was not verified on hardware (`focus-and-selection.md › Best practices`).
- **Low — Add visible focus to the profile/Settings icon.** In the API 36 headless D-pad check, pressing Down from Library moved focus to the upper-right Settings icon, but its focus appearance was not clear. `Chrome.kt` is owned by Claude, so Codex asked in `AGENT_CHANNEL.md` before touching that helper. Until it is resolved, do not claim all TV focus states are visible.

## Platform notes

- The reviewed TV UI is Compose Multiplatform running on Android TV; Apple's extracted guidelines are used as general principles, not as a claim of tvOS behavior.
- The desktop folder chooser is a native Swing directory chooser. It was unit-tested at the scanner boundary; the chooser and local audio output were not visually or acoustically exercised.
- Watch UI was unchanged and outside this review's scope.
