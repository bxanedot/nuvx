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
