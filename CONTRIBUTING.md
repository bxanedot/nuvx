# Contributing to nuvx

Thanks for helping. By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Where things are

| Folder | What | Language |
|---|---|---|
| `app/android/mobile`, `tv`, `wearos` | Android phone/tablet, Android TV and Auto, Wear OS | Kotlin, Compose |
| `app/desktop` | Windows, Linux and macOS app | Rust, egui |
| `app/ios`, `core/apple` | iPhone and iPad | Swift, SwiftUI, plus the Rust audio engine in `app/ios/native-core` |
| `app/web` | Web player and the Chrome and Firefox extensions | TypeScript, React |
| `core/rust/nuvx-core` | The shared Rust core: rules every app must agree on (release order, stream recovery, lyrics parsing, LAN Sync crypto, equalizer, loudness, playlist files) | Rust |
| `core/contracts` | Golden cases shared by the Rust core and the Kotlin apps | JSON |
| `core/shared`, `core/sharedUi` | Shared Kotlin models and UI | Kotlin Multiplatform |
| `core/worker`, `core/sync`, `core/discord-bot` | Listen Together worker (Cloudflare), Sync server, Discord bot | TypeScript, JavaScript |
| `docs` | Architecture, release and testing notes; the Rust migration plan is in `docs/rust-migration` | Markdown |

`CHANGELOG.md` lists every release; unreleased work goes under **Unreleased**.

## Issues

- Search open issues first.
- For a bug, include your device or computer, the OS version, the nuvx version and the steps to reproduce it. Add logs or a screen recording if you can. On the desktop, **Settings → Your data → Copy diagnostics** and on Android **Settings → Your data → Copy diagnostics** copy what a report needs, without personal data.
- Before reporting a security problem, read [Security](#security).

## Pull requests

1. Fork the repo and branch from `main`.
2. Keep each PR to one change. Open an issue first to discuss anything large.
3. Build what you touched, and run the tests for it (below).
4. Follow the style of the code around your change. Kotlin uses the official code style; Rust is formatted with `cargo fmt`.
5. Say what changed and why. Add screenshots for UI changes. **Don't change how an existing screen looks or behaves unless that is the point of the PR.**
6. Add a line to **Unreleased** in `CHANGELOG.md` for anything a user would notice.

Never commit secrets, keystores, `local.properties` or `keystore.properties`.

## Building and testing

Each command runs from the repository root unless it says otherwise.

- **Android** (needs the Android SDK; set `ANDROID_HOME` if there is no `local.properties`):
  `./gradlew :app:assembleDevDebug` builds the phone app (`com.licht.nuvx.dev`, which installs next to the real one),
  `./gradlew :app:testDevDebugUnitTest` runs its unit tests. TV and Wear: `:tv:compileDevTvDebugKotlin`, `:wearos:compileDevDebugKotlin`.
- **Desktop**: `cd app/desktop && cargo test`. Run a test copy with its own data folder so it never touches your real settings: set `NUVX_DATA_DIR` to an empty folder first.
- **Shared Rust core**: `cd core/rust/nuvx-core && cargo test && cargo clippy --all-targets -- -D warnings`.
- **iOS engine**: `cd app/ios/native-core && cargo test --lib` (needs CMake for the Opus codec). The iPhone app itself builds in the *Apple builds* workflow.
- **Web**: see [app/web/README.md](app/web/README.md#checks).
- **Worker and Sync server**: `npm test` in `core/worker` and in `core/sync`.

CI runs the matching checks on pull requests. See [docs/QUALITY_AND_TESTING.md](docs/QUALITY_AND_TESTING.md) for what is and isn't covered.

## Shared rules and golden cases

When the same rule lives in more than one app (which release is newer, when a failed song is retried or skipped, how loudness is normalized), the rule is in `core/rust/nuvx-core` and its expected results are in `core/contracts/*.json`. Both the Rust tests and the Kotlin tests read those files. If you change a rule, change the cases, and both sides must pass. Don't add a second copy of a rule to another app.

## Releases

Releases are cut by the maintainer by pushing a `v*` tag; please don't tag or publish. A **nightly** build is made every night when `main` changed (named *nightly 1, 2, …* after each stable release). Apps only offer nightlies when *Nightly updates* is on.

## License

Contributions follow the SPDX license declared by the file or component being changed. New files without another file- or component-level license use [GPL-3.0](LICENSE). Preserve existing SPDX identifiers and third-party notices. The Rust crates in `core/rust` are GPL-3.0-only like the desktop app.

## Security

Don't open a public issue for a vulnerability. Email licht on Guns.lol instead.
