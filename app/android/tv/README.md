# nuvx TV

Android TV / Google TV build of nuvx: a remote-first Compose-for-TV interface
(home, library, search, settings, a full-screen now-playing view with synced
lyrics, display refresh-rate switching, spatial audio, and QR-code sign-in).

This module holds only what differs from the phone app. Everything else — the
data and playback layers, resources, native code — comes from the
[phone module](../mobile), together with the shared `:shared` and `:sharedUi`
modules.

| Path | What it is |
| --- | --- |
| `src/main/java/.../ui/tv/**`, `TvActivity.kt` | The TV interface |
| `src/main/java/...` (same path as a file under `mobile/`) | A phone-app class TV patches; it replaces the `mobile/` one at build time |
| `src/main/res` | TV-only resources: banner, fonts, TV theme |
| `src/main/AndroidManifest.xml` | TV's own manifest (`LEANBACK_LAUNCHER`, `TvActivity`) |

## Build

```bash
./gradlew :tv:assembleProdDebug
```

The module is only included when an Android SDK is available, like `:app`.
