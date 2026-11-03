# Design review: nuvx phone UI (2026-11-03)

Method: the `apple-design` skill (Apple HIG references, pinned at dickwu/apple-design-skill@ff4ebdd), applied to the Compose Multiplatform code in `core/shared/.../ui` and emulator screenshots. nuvx runs on Android today, so Apple's principles and foundations apply (accessibility, colour, type, layout, writing). iOS conventions apply once the iOS app exists. Contrast figures are computed from the hex and alpha values in the code.

## Summary
Good. The thesis: play YouTube Music behind a content-first, artwork-driven player whose signature element is the full-screen lyrics view. The review found one Critical issue (placeholder contrast) and two High (touch targets, text scaling). All three are fixed in this commit.

## Fixed
| Finding | Measured | Rule | Fix |
|---|---|---|---|
| **Critical:** placeholder and hint text (17sp) used white at 38% | 3.4–3.6:1 on black, glass and card | `accessibility.md`: text up to 17pt needs 4.5:1 | `NuvxColors.Tertiary` raised to 56%, giving 5.9–6.5:1. Disabled icons keep their own 30% tint (exempt) |
| **High:** like/more chips 40dp; clear/remove ✕ buttons 32dp | below 44pt / 48dp | `buttons.md › Make buttons easy for people to use`: "a hit region of at least 44x44 pt" | chips 44dp; ✕ hit areas 44dp (icons unchanged) |
| **High:** fixed heights on the tab bar, mini player, filter control, fields and buttons clipped scaled text | verified at font scale 1.6 | `typography.md` (Dynamic Type / larger text) | `heightIn(min = …)` (+ `IntrinsicSize.Min` where children fill height) |
| **Low:** "Settings & About" was title-style while other labels are sentence case | — | `writing.md`: "Choose a style for each UI element type and use it consistently" | "Settings and about" |

## Open (not changed, with reasons)
- **Medium:** the top-right avatar opens Settings although there are no accounts yet. The avatar matches the reference UX and becomes correct once sign-in exists. Revisit then.
- **Medium:** the accent blue means several things (current track, links, room code, album artist line, "Host"). Consider keeping accent for "now playing" and links only (`liquid-glass.md`: "Apply your app's accent color judiciously").
- **Platform note:** dark appearance only, matching the reference app. The HIG expects light and dark without an in-app switch. Decide this before the iOS release.
- **Lyrics:** inactive lines are 32sp extra-bold at 38% (3.4:1 on black, passing the 3:1 large/bold rule). Over bright artwork the backdrop is darkened by a gradient scrim; not measured per artwork.

## What works
- Glass is used only on the floating functional layer (mini player, tab bar, search controls), never in content (`liquid-glass.md`, content vs functional layer).
- The player sheet has a grabber, swipe-to-dismiss, a tap-to-close handle and back-button support (`sheets.md`).
- Every icon-only control has a screen-reader label; the seek bar exposes progress and a set-progress action; reduced motion is honoured.
- One signature element (the lyrics view); everything around it stays quiet.

---

# Design review: TV and local music library (Codex, 2026-11-03)

Reviewed on 2026-11-03. Scope: the shared Compose TV shell, local-library screen, and their Library navigation wiring. The review uses the Apple Design skill's general principles for accessibility, layout, focus, and privacy. Its extracted guidance omits tvOS-specific sections, so Android TV input behavior remains subject to Android TV verification. The Watch screen was not changed in this work and is not reviewed here.

## Summary

**Good.** The screens keep music content central on a quiet black canvas, with bxane's white mark and blue accent providing the nuvx identity. The TV shell keeps navigation separate from content, and the local-library route explains why audio access is needed. This assessment is based on source review and headless API 36 emulator screenshots, not physical-device testing.

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

# Design review: website brand mark (Codex, 2026-11-03)

## Summary

**Good.** The website uses the supplied nuvx swoosh mark as a quiet identifier beside the lowercase wordmark, with an enlarged instance in the hero. The mark has strong contrast in both themes; the compact horizontal silhouette remains recognizable when the favicon crops the unused canvas margins.

## What works

- The light theme uses the black mark on `#fcfcfa` (about **20.5:1** contrast); the dark theme uses the white mark on `#101114` (about **18.4:1**). Both exceed WCAG's 3:1 non-text contrast threshold. The theme-specific logo files avoid depending on color alone or a CSS recoloring filter.
- The favicon SVG uses a tight viewBox around the supplied vector artwork. At 16px its mark occupies about 15 � 6px, keeping the main silhouette visible without adding a background or altering the artwork. This is suitable for a simple monochrome mark; browser UI may still display favicons at smaller effective sizes.
- The logo appears in the site header and footer, with one larger hero instance. It does not displace page content or repeat throughout the documentation.
- These are web design findings using Apple's general clarity and consistency guidance: `app-icons.md � Design` says to keep icons simple because fine details can be hard to discern at small sizes; `branding.md � Best practices` says to keep branding unobtrusive and avoid repeating logos without context.

## Platform notes

This review covers source assets, CSS theme selection, SVG geometry and computed color contrast. It did not verify favicon rendering in browser chrome or third-party social-card crawlers. The OG image is a static light-surface preview; it is not theme-dependent because social preview crawlers request one shared image.
