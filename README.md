# nuvx

An open-source YouTube Music client for Android phones, Android TV, Wear OS and the web, built with Kotlin Multiplatform and Compose Multiplatform.

> **nuvx 0.2.0 is out.** Download the Android phone, Android TV, Windows, Linux and macOS apps from the [latest release](https://github.com/bxanedot/nuvx/releases/latest), or use the web player at https://web.nuvx.lol.

- Website: https://www.nuvx.lol
- Community: https://discord.gg/h4Wsu824Uf

## What's in here

| Path | What it is |
|---|---|
| `app/android/mobile` | The phone app (`:app`) |
| `app/android/tv` | Android TV / Google TV app (`:tv`), see its [README](app/android/tv/README.md) |
| `app/android/wearos` | Wear OS remote and speaker (`:wearos`), see its [README](app/android/wearos/README.md) |
| `app/web` | Web player and browser extension, see its [README](app/web/README.md) |
| `core/shared`, `core/sharedUi` | Shared Kotlin code and Compose UI |
| `core/native` | Native audio code (analyzer, FFmpeg, loop mix) |
| `core/worker` | Listen Together Cloudflare Worker, see its [README](core/worker/README.md) |
| `core/canvas` | Community canvases, see its [README](core/canvas/README.md) |
| `core/website` | nuvx.lol |
| `core/brand` | Logo and brand assets |
| `core/third_party` | Prebuilt libraries not yet published elsewhere |

## Build

You need JDK 25 to run Gradle and the Android SDK (API 37). Point Gradle at the SDK with `sdk.dir` in `local.properties`, or with `ANDROID_HOME`.

```bash
./gradlew :app:assembleDevDebug     # phone, installs beside a prod build
./gradlew :tv:assembleProdDebug     # TV
./gradlew :wearos:assembleDevDebug  # watch
```

Release builds are signed only when `keystore.properties` and its keystore exist. See `keystore.properties.example`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

[GPL-3.0](LICENSE). Bundled third-party code keeps its own license. For example, the DST decoder in `app/android/mobile/src/main/cpp/dsd/dst` is under LGPL-2.1.
