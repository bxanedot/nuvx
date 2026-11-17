# Quality and testing

How to run every test suite, and what passed on 2026-11-17. Run each command from the repository root unless a folder is shown.

| Suite | Command | Result |
|---|---|---|
| Android phone (JVM unit) | `ANDROID_HOME=… sh gradlew :app:testDevDebugUnitTest` | 1,369 passed, 1 skipped |
| Android TV, Wear (compile) | `sh gradlew :tv:compileProdTvDebugKotlin :wearos:compileDevDebugKotlin` | Compile |
| Shared UI (JVM, includes icon render tests) | `sh gradlew :sharedUi:jvmTest` | 53 passed |
| Desktop | `cd app/desktop && cargo test --locked` | 230 passed, 27 ignored (they need YouTube) |
| Web app and extension | `cd app/web && pnpm -r test` | 241 and 2 passed |
| Web type-check | `cd app/web/app && pnpm exec tsc --noEmit -p .` | Clean |
| Listen Together Worker | `cd core/worker && npm test && npx tsc --noEmit` | 29 passed |
| Sync server | `cd core/sync && npm test` | 9 passed |
| Website | `node --test core/website/*.test.js core/website/api/*.test.js` | 62 passed |
| iOS source checks | `python3 app/ios/scripts/check-nuvx-port.py && python3 -m unittest discover -s app/ios/tests` | Pass |
| iOS and Mac Swift build | GitHub Actions "Apple builds" on macOS runners | CI only |

## Where tests run

- **CI on every pull request:** Worker, Sync, Apple builds (iOS, macOS), Vercel preview. No Android or desktop test job runs on pull requests; those run locally, as above, and in the release workflow's builds.
- **Release workflow:** builds every platform, verifies the APK package, version and signing certificate, and publishes only if every build passes. A failed stable build deletes its draft.

## Known gaps

- No on-device tests. `androidTest` exists but needs an emulator.
- `CrossfadeController` drives ExoPlayer and has no unit tests; its timing math (`CrossfadeGeometry`) is pinned by characterization tests.
- No pixel-golden screenshots. The icon registry is covered by render and animation-state tests instead.
- The live YouTube stream tests (`opus_stream_live` and others) are `#[ignore]` and need network access.
