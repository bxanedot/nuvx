# nuvx brand assets

bxane's nuvx mark. It is monochrome and has no background. There are two variants (more will be added later):

| File | Use |
|---|---|
| `mark/nuvx_mark_white.svg` / `.png` | **Default on every device**: the app, Android launcher (phone, TV, Wear, on black), desktop icons, dark surfaces |
| `mark/nuvx_mark_black.svg` / `.png` | Light surfaces: the light website theme, documents, README in light mode |

- The SVGs are vector outlines traced from bxane's supplied artwork (`source/nuvx_mark_source.png`). A raster check against the source gives 97% pixel overlap; the differences are edge anti-aliasing only.
- The SVGs use a 1024×1024 canvas with the mark centred, spanning 880 units. The PNGs are 1024px renders of the same path with a transparent background.
- In-app, `ui/Brand.kt` draws the same path as a Compose `ImageVector` (no bitmap), and Android's adaptive icon (`res/drawable/nuvx_mark.xml`) scales it into the 66dp safe zone on a black background.
- `core/scripts/prepare-windows.ps1` packages the white PNG into the Windows ICO.
- `SHA256SUMS` lists the hashes of every file here.

The earlier lightning-bolt colourways were retired on 2026-11-03 at bxane's request. They remain in git history.
