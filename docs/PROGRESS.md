# Progress

Legend: ✅ verified · 🟡 built, not verified end-to-end · ⬜ not started

| Feature | Status | Evidence |
|---|---|---|
| Toolchain (Gradle 9.8.2, Kotlin 2.4.21, CMP 1.12.2, AGP 9.5.0) | ✅ | `:shared:compileKotlinDesktop` passes |
| `MusicBackend` + `BackendRouter` (real engine switch + fallback + health) | ✅ | `BackendRouterTest` |
| InnerTubeX search / radio / stream | ✅ live | `LiveBackendTest`, 2026-11-02: 14 tracks, 50-track radio, audio/webm 185 kbps, audio bytes fetched |
| eXcor search / radio | ✅ live | `LiveBackendTest`: 8 tracks, 50-track radio |
| eXcor stream | ❌ refused by YouTube | BLOCKERS #1 |
| YT Music JSON parser | ✅ | `YtmJsonTest` |
| Android app + Media3 playback | ⬜ | |
| Desktop app + playback | ⬜ | |
| Compose UI / design system / onboarding | ⬜ | |
| Lyrics, radio UI, internet radio, EQ, Discord, Listen Together, OAuth | ⬜ | |
| Website, CI, packaging | ⬜ | |
