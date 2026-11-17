# Icons and motion

nuvx draws its symbols with GlyphStudio (`com.github.bxanedot.GlyphStudio:sfsymbols`, v2.0.0) on every Compose surface: Android phone, TV, Wear, Auto and the shared UI. iOS uses Apple's built-in SF Symbols through SwiftUI. Both use the same symbol names, so a meaning looks the same everywhere.

## Registry

- **Static icons:** `SfIcons` (`core/sharedUi/.../ui/icons/SfIcons.kt`).
- **Animated states:** `NuvxGlyph` and `NuvxSymbol` (`core/sharedUi/.../ui/icons/NuvxSymbols.kt`). Each entry has one meaning and one GlyphStudio symbol.

| Meaning | Symbol | Motion | Where |
|---|---|---|---|
| Play / pause | `play.fill` / `pause.fill` | Replace transition on toggle | Android mini player and floating bar; iOS Now Playing, pill, theater and detail buttons (`.nuvxSymbolReplace()`) |
| Tab reselect | the tab's symbol | Bounce, by layer | Android floating tab bar |
| Reveal | the item's symbol | Bounce, by layer, when fully shown | `RevealIcon` |

## Rules

- **Event-triggered only:** motion runs when the state changes, never as a continuous loop. The Radio screen's indicator is the one continuous effect, and it is gated.
- **Reduced motion:** every animated symbol is still with "reduce animation" on. On iOS it is also still with the system's Reduce Motion. GlyphStudio has no reduced-motion handling of its own, so the nuvx wrappers do it.
- **Weight:** Bold, sized by `REVEAL_SYMBOL_SCALE`, so animated and static icons line up.

## Tests

- **Present:** `NuvxGlyphTest` checks that every registry entry exists in GlyphStudio.
- **Missing:** visual regression tests. The repository has no screenshot tooling yet; adding Roborazzi or Paparazzi is the next step.

## Licensing

GlyphStudio's glyphs are Apple's SF Symbols. Apple licenses them for Apple platforms. Using them on Android, desktop and the web is the owner's decision, taken knowingly on 2026-11-17.
