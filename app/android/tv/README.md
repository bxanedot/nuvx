# nuvx TV and Auto

Android TV / Google TV and standalone Android head-unit builds of nuvx. The
Auto APK reuses the TV interface's large focus targets and landscape layout.
Android Auto projection remains available from the phone app's media service.

The TV build is a remote-first Compose-for-TV interface
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
| `src/main/AndroidManifest.xml` | Shared TV/Auto manifest (`LEANBACK_LAUNCHER`, `TvActivity`) |

## Build

```bash
./gradlew :tv:assembleProdTvDebug
./gradlew :tv:assembleProdAutoDebug
```

The module is only included when an Android SDK is available, like `:app`.
