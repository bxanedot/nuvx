# Android playback and bxane brand integration

User-authorized continuation of the existing mission; implement inline without another approval round.

1. Preserve the four unfinished core files in this isolated worktree. Verify existing desktop tests.
2. Import original icon variants from the supplied archives into assets/brand, record SHA-256 provenance; use the electric-blue PNG in shared UI and Android launcher. No replacement artwork.
3. Add common UI with Search, Queue, Now Playing, engine Settings and About. Use existing BackendRouter, Track and NuvxPlayer contracts; expose errors and cancellation, never fabricate catalogue data.
4. Add Android application and Media3 service, connected through MediaController. Resolve logical track URIs at load time through the router on loading threads, preserve per-stream headers, validate HTTPS streams, respect audio focus and headset removal. Add tests for stream validation before implementing it.
5. Build APK and AAB, run shared tests and Android lint, inspect packaged branding. Use an emulator/device if available; otherwise record runtime verification as outstanding.
6. Update progress/blockers and external handover with exact commands and results. Never claim release readiness from compilation alone.

Review focus: untrusted stream URLs; headers leaking between tracks; controller authorization; service lifecycle; errors showing sensitive request data. Existing core/backend code is preserved. Full remaining product scope stays in ../handover/NUVX_MASTER_SPEC.md of the source checkout.

## Execution ledger

Continued inline in a managed isolated worktree. Source checkout was left untouched; its four unfinished core files were copied and preserved. Added Android mobile/TV/Wear layouts and manifests, shared primary engine/iOS networking targets, desktop VLC playback with tested header/range bridge, pinned Windows runtime preparation and app image/MSI, CI/Dependabot. Independent review found one final-track replay bug; regression failed [rewind, play] vs [pause], then passed after the fix. Stream validation and proxy tests also went red before implementation and green afterward. Remote CI, Apple builds and packaged EXE execution are not claimed verified.
