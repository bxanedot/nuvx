# Icons and motion

nuvx draws its symbols with GlyphStudio (`com.github.bxanedot.GlyphStudio:sfsymbols`, v2.0.0) on every Compose surface: Android phone, TV, Wear, Auto and the shared UI. iOS uses Apple's built-in SF Symbols through SwiftUI. Both use the same symbol names, so a meaning looks the same everywhere.

## Registry

- **Static icons:** `SfIcons` (`core/sharedUi/.../ui/icons/SfIcons.kt`).
- **Animated states:** `NuvxGlyph` and `NuvxSymbol` (`core/sharedUi/.../ui/icons/NuvxSymbols.kt`). Each entry has one meaning and one GlyphStudio symbol.

| Meaning | Symbol | Motion | Where |
|---|---|---|---|
| Play / pause | `play.fill` / `pause.fill` | Replace transition on toggle | Android Now Playing (phone and TV share it), mini player and floating bar; iOS Now Playing, pill, theater and detail buttons (`.playPauseMorph()`) |
| Downloading / downloaded / failed | `arrow.down` / `arrow.down.circle.fill` / `exclamationmark.circle` | Replace transition when the state changes | Android download status pill |
| Device / connected | `airplay.video` / `airplay.video.circle.fill` | Replace transition on connect and disconnect | Android cast device list |
| Tab reselect | the tab's symbol | Bounce, by layer | Android floating tab bar |
| Reveal | the item's symbol | Bounce, by layer, when fully shown | `RevealIcon` |

## Desktop and web

GlyphStudio is Compose-only, so the Rust desktop uses equivalents with the same meanings: its own SVG glyphs (`app/desktop/src/ui/glyphs.rs`). Play/pause cross-morph and the favorite star pops, both turned off by its "Reduce animation" setting (test `reduced_motion_disables_glyph_animation`). The web player draws the same SF-style paths (`components/icons/Transport.tsx`); `PlayPauseIcon` cross-morphs play and pause in the player bar, full-screen player, mobile bar and track list, with no transition under `prefers-reduced-motion`.

## Rules

- **Event-triggered only:** motion runs when the state changes, never as a continuous loop. The Radio screen's indicator is the one continuous effect, and it is gated.
- **Reduced motion:** every animated symbol is still with "reduce animation" on. On iOS it is also still with the system's Reduce Motion. GlyphStudio has no reduced-motion handling of its own, so the nuvx wrappers do it.
- **Weight:** Bold, sized by `REVEAL_SYMBOL_SCALE`, so animated and static icons line up.

## Tests

- **Present:** `NuvxGlyphTest` checks that every registry entry exists in GlyphStudio.
- **Missing:** visual regression tests. The repository has no screenshot tooling yet; adding Roborazzi or Paparazzi is the next step.

## Licensing

- The desktop bundles Apple's SF Pro Display font files (`app/desktop/assets/fonts/SFProDisplay-*.otf`, the "SF Pro Display" font preset). Apple's font license limits SF Pro to Apple platforms and forbids redistributing the files, so shipping them in the Windows and Linux installers is a separate, clearer risk than the symbols. Owner decision pending.

GlyphStudio's glyphs are Apple's SF Symbols. Apple licenses them for Apple platforms. Using them on Android, desktop and the web is the owner's decision, taken knowingly on 2026-11-17.
