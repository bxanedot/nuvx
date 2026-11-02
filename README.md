# Nuvx

A multiplatform YouTube Music client, built with Kotlin Multiplatform and Compose Multiplatform.

> **Status: early development.** Nothing has been released yet. [docs/PROGRESS.md](docs/PROGRESS.md) lists what works and what has been verified, and [docs/BLOCKERS.md](docs/BLOCKERS.md) lists what doesn't work yet.

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
NUVX_LIVE=1 ./gradlew :shared:desktopTest --tests '*LiveBackendTest*'   # hits YouTube Music
```

## License

GPL-3.0-only (see [LICENSE](LICENSE)), because Nuvx links InnerTubeX, which is GPL-3.0. Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Nuvx is not affiliated with, endorsed by, or sponsored by YouTube or Google.
