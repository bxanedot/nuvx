# Nuvx

<img src="core/assets/brand/flat/03_electric_blue.svg" width="96" alt="Nuvx lightning mark by bxane" />

A multiplatform YouTube Music client, built with Kotlin Multiplatform and Compose Multiplatform.

> **Status: early development.** Nothing has been released yet. [docs/PROGRESS.md](docs/PROGRESS.md) lists what works and what has been verified, and [docs/BLOCKERS.md](docs/BLOCKERS.md) lists what doesn't work yet.

Targets: Windows, Linux, macOS; Android mobile/tablet, TV and Wear OS; iPhone/iPad. [Platform verification](docs/PLATFORMS.md) distinguishes built applications from unfinished targets. No IPA is available yet.

## Links

| | |
|---|---|
| **Nuvx — Official Website** | [nuvx.lol](https://nuvx.lol) |
| **Developed by bxane** | [github.com/bxanedot](https://github.com/bxanedot) |
| **Developer Portfolio** | [bxane.lol](https://bxane.lol) |
| **Alternative Developer Portfolio** | [guns.lol/bxane](https://guns.lol/bxane) |
| **Community** | [Discord](https://discord.gg/h4Wsu824Uf) |
| **Contact** | [bxanee@gmail.com](mailto:bxanee@gmail.com) |

## Streaming engines

Nuvx talks to YouTube Music through a `MusicBackend` interface, and you choose the engine in Settings:

- **InnerTubeX** (default) handles search, radio and audio streams. Verified live (see `LiveBackendTest`).
- **eXcor** (alternative) handles search and radio. Its stream extraction is currently refused by YouTube because eXcor does not mint PO tokens. With automatic fallback turned on, Nuvx retries on the other engine and shows which engine answered.

## Build

You need JDK 25 to run Gradle and JDK 27 for the shared desktop target (eXcor ships JVM 26 bytecode), plus the Android SDK (API 37). [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md) has the details.

```bash
./gradlew :shared:desktopTest          # offline unit tests
./gradlew :androidApp:assembleDebug :androidApp:bundleRelease :androidApp:lint
./gradlew :androidApp:testMobileDebugUnitTest :desktopApp:test
NUVX_LIVE=1 ./gradlew :shared:desktopTest --tests '*LiveBackendTest*'   # hits YouTube Music
```

## Application packages

Android APK/AAB outputs are separated into mobile, tv and wear flavours. Release AABs are unsigned.

For Windows, run `./core/scripts/prepare-windows.ps1`, then `./gradlew.bat :desktopApp:createDistributable`. The preparation script verifies and bundles official VLC 3.0.24, its notices and bxane's icon. Linux/macOS need VLC 3 installed, or `NUVX_VLC_HOME` pointing to its native library folder; runtime bundling remains release work. Run from source with `./gradlew :desktopApp:run`. Native desktop tests are opt-in with `NUVX_DESKTOP_LIVE=1` and a discoverable VLC 3 installation.

## License

GPL-3.0-only (see [LICENSE](LICENSE)), because Nuvx links InnerTubeX, which is GPL-3.0. Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Nuvx is not affiliated with, endorsed by, or sponsored by YouTube or Google.
