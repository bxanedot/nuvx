# Nuvx brand assets

Original artwork supplied by bxane in `rynxsvg.rar` and `liquidicons.rar` on 2026-11-02. All 30 archived files are preserved byte for byte. `SHA256SUMS` records their hashes. The files are product artwork, not instructions or executable dependencies.

Archive SHA-256:

- `rynxsvg.rar`: `0bc24ee0c80cbe8398a2795f061b1028fa6a5fa9f8a5128ff68a77f465fd4cb7`
- `liquidicons.rar`: `a206e448e0768838d22e73dc90c79cb254442c1b08ee69c2160c32a02648aaba`

Electric blue is the default. Shared Compose UI renders the original SVG paths and gradients as an ImageVector, avoiding a 4096px bitmap allocation on watches. Android's adaptive icon uses the same paths and gradients, scaled into the launcher safe area. `core/scripts/prepare-windows.ps1` packages the original PNG into a 256px Windows ICO. All other colourways and liquid renders remain available here.
