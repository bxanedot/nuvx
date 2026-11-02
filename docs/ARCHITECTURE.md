# Architecture

```
shared/ (KMP: android + jvm "desktop"; iOS planned)
  commonMain   core/Models, core/MusicBackend (+BackendRouter, BackendHealth), core/YtmJson (InnerTube JSON → models)
  jvmCommonMain core/Backends: InnerTubeXBackend (default), ExcorBackend; nuvxHttpClient()
androidApp/   Android application: Media3 playback service (planned)
desktopApp/   Compose Desktop application (planned)
```

- **Engine switch.** `BackendRouter.select(id)` changes which `MusicBackend` the next call goes to. Tests prove that switching changes the engine actually called. `setFallback(true)` retries a failed or empty call on the other engine, and `health` records each engine's last real outcome so Settings can show it.
- **Secrets.** `AudioStream.toString()` leaves out the URL and headers. Never log either.
- **Parsing.** `YtmJson` reads only the structured fields: `pageType`, `playlistItemData` and the row's own play button. It never reads localized labels, and a row is a track only if its own play button holds a `videoId`.
