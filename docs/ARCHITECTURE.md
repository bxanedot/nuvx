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

## Application targets added in the Codex continuation

The primary InnerTubeX adapter is in commonMain. An expect/actual HTTP client uses OkHttp on Android/JVM and Darwin on iOS. The eXcor adapter remains in jvmCommonMain, matching its locally published artifacts. NuvxPlayer and KeyValueStore keep native playback and persistence outside common code.

Shared Compose UI has guest onboarding, search, queue, now-playing lyrics, engine settings and About. Watch devices use a round-screen layout; TV uses the wide layout and Leanback launcher. Shared brand vectors reproduce bxane's SVG without allocating a 4096px bitmap.

Android mobile/tv/wear flavours connect a MediaController to a MediaSessionService. Logical nuvx://track/<id> media items resolve on Media3 loading threads; source URLs and headers stay in the data source. Media3 handles audio focus and headphone disconnection. The exported session admits the application and trusted/system controllers.

Desktop uses the same KMP router/UI and VLC decoding. A loopback-only bridge exposes a random per-track URL, preserving source headers and the decoder Range. It does not follow redirects; old tokens are invalidated on track changes. Source URLs never enter native player options. Native state mutations use the Swing dispatcher and extraction runs off the UI thread. The bundled runtime explicitly uses JDK 27 because eXcor requires JVM 26 bytecode.
