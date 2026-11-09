<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="core/brand/mark/nuvx_mark_white.png">
    <img src="core/brand/mark/nuvx_mark_black.png" width="116" height="116" alt="">
  </picture>
</p>

<h1 align="center">nuvx</h1>
<p align="center">An open-source YouTube Music player for your phone, TV, wrist, desktop and browser.</p>

<p align="center">
  <a href="https://github.com/bxanedot/nuvx/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/bxanedot/nuvx?style=for-the-badge&amp;labelColor=0d1117&amp;color=355f8a"></a>
  <a href="LICENSE"><img alt="GPL-3.0 license" src="https://img.shields.io/badge/license-GPL--3.0-4b5563?style=for-the-badge&amp;labelColor=0d1117"></a>
  <a href="https://github.com/bxanedot/nuvx/releases"><img alt="GitHub downloads" src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fnuvx-listen.bxane.workers.dev%2Fapi%2Fstats%2Fdownloads&amp;query=%24.downloads&amp;style=for-the-badge&amp;labelColor=0d1117&amp;label=GitHub%20downloads&amp;color=6b4f99"></a>
  <a href="https://nuvx-listen.bxane.workers.dev/api/stats/live"><img alt="Online clients" src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fnuvx-listen.bxane.workers.dev%2Fapi%2Fstats%2Flive&amp;query=%24.online&amp;style=for-the-badge&amp;labelColor=0d1117&amp;label=Online%20clients&amp;color=397458"></a>
  <a href="https://nuvx-listen.bxane.workers.dev/api/stats/live"><img alt="Listening Together" src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fnuvx-listen.bxane.workers.dev%2Fapi%2Fstats%2Flive&amp;query=%24.listening&amp;style=for-the-badge&amp;labelColor=0d1117&amp;label=Listening%20Together&amp;color=87563c"></a>
</p>

<p align="center">
  <a href="#download"><strong>Download</strong></a> ·
  <a href="#features">Features</a> ·
  <a href="#screenshots">Screenshots</a> ·
  <a href="#build-from-source">Build</a> ·
  <a href="https://web.nuvx.lol">Web player</a> ·
  <a href="https://discord.gg/h4Wsu824Uf">Discord</a>
</p>

---

![Music in full color. nuvx Now Playing framed by cobalt and copper panels.](docs/nuvx-banner-now-playing.png)

<p align="center">
  <a href="docs/nuvx-banner-amber.png"><img src="docs/nuvx-banner-amber.png" width="49%" alt="Follow every word. Synced lyrics in nuvx, with a copper record-groove backdrop."></a>
  <a href="docs/nuvx-banner-lyrics-motion.png"><img src="docs/nuvx-banner-lyrics-motion.png" width="49%" alt="Lyrics in motion. An illustration of the active lyric line lighting up in nuvx."></a>
</p>

## Desktop

<p align="center">
  <a href="docs/nuvx-banner-desktop.png"><img src="docs/nuvx-banner-desktop.png" width="49%" alt="Make room for music. nuvx Desktop showing Home with the lyrics panel open."></a>
  <a href="docs/nuvx-banner-desktop-lyrics.png"><img src="docs/nuvx-banner-desktop-lyrics.png" width="49%" alt="Lyrics, full screen. nuvx Desktop with red album artwork beside synced lyrics."></a>
</p>


### Now Playing and lyrics

<p align="center">
  <a href="docs/screenshots/android-now-playing.png"><img src="docs/screenshots/android-now-playing.png" width="180" alt="Android Now Playing with Espresso album artwork and a synced lyric preview"></a>
  <a href="docs/screenshots/android-lyrics-out-of-the-woods.png"><img src="docs/screenshots/android-lyrics-out-of-the-woods.png" width="180" alt="Full-screen synced lyrics for Out Of The Woods"></a>
  <a href="docs/screenshots/android-lyrics-espresso.png"><img src="docs/screenshots/android-lyrics-espresso.png" width="180" alt="Full-screen synced lyrics for Espresso"></a>
</p>

## Features

- **Listen Together.** Share an invite and keep the queue and playback in sync with friends.
- **Synced lyrics.** Follow the words in the full-screen player.
- **Music discovery.** Browse new releases, explore by mood, and manage your library and queue.
- **Community Canvases.** Add motion artwork to Now Playing on supported players. [Browse the project](core/canvas/README.md)
- **Controls for each screen.** Navigate with a TV remote, control the phone app from Wear OS, or keep the desktop mini player close by.

## Download

| Device | Requirements | Where to start |
| --- | --- | --- |
| **Android phone or tablet** | Android 8.0+ | [Latest release](https://github.com/bxanedot/nuvx/releases/latest) · `nuvx-v*.apk` |
| **Android TV or Google TV** | Android 8.0+ | [Latest release](https://github.com/bxanedot/nuvx/releases/latest) · `NuvxTV-v*.apk` |
| **Wear OS** | Wear OS 3+ and a paired Android phone running nuvx | Open **Settings → Wear OS** in the phone app. [Wear OS guide](app/android/wearos/README.md) |
| **Windows** | Windows 10+, x64 | [Latest release](https://github.com/bxanedot/nuvx/releases/latest) · setup `.exe` or portable `.zip` |
| **macOS** | macOS 11+, Apple silicon or Intel | [Latest release](https://github.com/bxanedot/nuvx/releases/latest) · `.dmg` or portable `.zip` |
| **Linux** | x86-64 | [Latest release](https://github.com/bxanedot/nuvx/releases/latest) · install or portable `.tar.gz` |
| **Web** | Chrome, Edge, Brave or Firefox on desktop, with the companion extension for full playback | [Web player](https://web.nuvx.lol) · [Extension setup](https://web.nuvx.lol/guide) |

For Android, download the APK for your device and allow your browser or file manager to install it when prompted. The release notes list desktop packages by architecture and include signing details.

## How the counters work

- **GitHub downloads** totals installers and portable app packages across public GitHub releases. Updates and repeat downloads count too. Web visits and extension downloads from the web player are separate. [Download count](https://nuvx-listen.bxane.workers.dev/api/stats/downloads)
- **Online clients** counts apps and browsers that recently checked in to the official Listen Together server. The server deduplicates check-ins by a random client ID.
- **Listening Together** counts connected devices in parties with playback running on the official server. Paused parties are excluded. One person can use more than one device.

GitHub and badge caches can delay updates. [Current server counts](https://nuvx-listen.bxane.workers.dev/api/stats/live)

## Build from source

Android builds use Kotlin and Compose. Install JDK 25 and Android SDK Platform 37, then set `ANDROID_HOME` or add `sdk.dir` to `local.properties`.

```bash
./gradlew :app:assembleDevDebug     # phone and tablet
./gradlew :tv:assembleProdDebug     # Android TV and Google TV
./gradlew :wearos:assembleDevDebug  # watch companion
```

On Windows, use `gradlew.bat`. For signing release builds, see [keystore.properties.example](keystore.properties.example).

| Project | Source and setup |
| --- | --- |
| Android apps | [Phone](app/android/mobile) · [TV](app/android/tv/README.md) · [Wear OS](app/android/wearos/README.md) |
| Desktop player | [Rust and egui](app/desktop/README.md) |
| Web player and extension | [React, TypeScript and WXT](app/web/README.md) |
| Shared code | [Kotlin](core/shared) · [Compose UI](core/sharedUi) · [Native audio](core/native) |
| Listen Together server | [Cloudflare Worker](core/worker/README.md) |
| Project assets | [Canvases](core/canvas/README.md) · [Website](core/website) · [Brand](core/brand/README.md) · [Third-party libraries](core/third_party/README.md) |

## Contribute

Found a bug or have an idea? [Open an issue](https://github.com/bxanedot/nuvx/issues) or join the [Discord](https://discord.gg/h4Wsu824Uf). See [Contributing](CONTRIBUTING.md), the [Code of Conduct](CODE_OF_CONDUCT.md), or the [nuvx website](https://www.nuvx.lol).

## License

nuvx is licensed under [GPL-3.0](LICENSE). Bundled third-party code keeps its own licenses. The [DST decoder](app/android/mobile/src/main/cpp/dsd/dst), for example, is under LGPL-2.1.
