# Toolchain

Versions were checked against the official release feeds on 2026-11-02: Maven Central and Google Maven metadata, `services.gradle.org/versions/current`, `api.adoptium.net`, `nodejs.org/dist/index.json`, and the npm registry. Only stable releases are used.

| Tool / library | Version | Source | Notes |
|---|---|---|---|
| Gradle (wrapper) | 9.8.2 | services.gradle.org | wrapper jar SHA-256 `3b8a2577…8e98` matches the published checksum |
| JDK for running Gradle | Temurin 25 LTS | adoptium.net | latest LTS |
| JDK toolchain for `:shared` | Temurin 27 | adoptium.net | **forced by eXcor**: its desktop artifact is JVM 26 bytecode, so the desktop target compiles with `jvmTarget=26`. Android bytecode stays at 17. |
| Kotlin | 2.4.21 | Maven Central | |
| Compose Multiplatform | 1.12.2 (Material3 1.9.0) | Maven Central | |
| GlyphStudio SF Symbols Compose | 1.0.0 | JitPack | KMP symbol vectors and effects used by shared navigation, library, and player UI |
| Android Gradle Plugin | 9.5.0 | Google Maven | uses the AGP 9 KMP library plugin `com.android.kotlin.multiplatform.library` |
| Android compile/target SDK | 37 | SDK Manager | min SDK 26 (eXcor's minimum) |
| kotlinx.coroutines | 1.11.0 | Maven Central | |
| kotlinx.serialization | 1.12.0 | Maven Central | |
| Ktor | 3.6.1 | Maven Central | |
| Coil | 3.6.3 | Maven Central | |
| Media3 | 1.11.1 | Google Maven | |
| InnerTubeX | v0.11.2 | JitPack | |
| eXcor | 0.2.0 | built from source → `mavenLocal` | no remote release exists yet |
| Node.js | 26 LTS (installed: 24) | nodejs.org | needed for the website and Worker |
| Wrangler | 4.156.2 | npm | not yet installed |
| Vercel CLI | 65.9.0 | npm | not yet installed |

## Local setup (Windows)

```bash
# 1. Publish eXcor locally. iOS targets can't compile on Windows, so publish only the JVM/Android/metadata publications.
git clone https://github.com/bxanedot/eXcor && cd eXcor
./gradlew publishAndroidPublicationToMavenLocal publishJvmPublicationToMavenLocal publishKotlinMultiplatformPublicationToMavenLocal
# 2. Build nuvx. Gradle auto-provisions JDK 27 through foojay; if it is already installed, point Gradle at it:
./gradlew -Porg.gradle.java.installations.paths=<path-to-jdk-27> :shared:desktopTest
```

`local.properties` needs `sdk.dir=<Android SDK path>`. It is gitignored.

## Known limits
- iOS: Kotlin/Native iOS targets and xtool need macOS or Linux. Windows can't build them (see BLOCKERS.md).
- Once eXcor publishes an LTS-compatible JVM artifact, move the desktop target back to JDK 25 / `jvmTarget=25`.
- Local recheck on 2026-11-04 at 20:04 +01:00: `local.properties` still names `C:/Users/bxane/AppData/Local/Android/Sdk`, but that directory is absent and `ANDROID_HOME` / `ANDROID_SDK_ROOT` are unset. The latest Android variant matrix could not resolve its SDK and did not execute; restore a verified existing SDK location before rerunning. No local configuration or pinned toolchain version was changed. Shared/desktop verification uses the existing nested JDK home `C:/Users/bxane/.gradle/jdks/jdk-27.0.0+35/jdk-27+35`.

## Application continuation (2026-11-02)

Desktop: vlcj 4.12.1 / vlcj-natives 4.12.0 from https://repo.maven.apache.org/maven2/uk/co/caprica/vlcj/maven-metadata.xml. VLC 3.0.24 from https://download.videolan.org/pub/videolan/vlc/3.0.24/win64/; the preparation script verifies SHA-256 fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323 and retains original licence files.

Windows commands:

```powershell
./core/scripts/prepare-windows.ps1
./gradlew.bat :shared:desktopTest :androidApp:testMobileDebugUnitTest :androidApp:assembleDebug :androidApp:bundleRelease :androidApp:lint :desktopApp:test :desktopApp:createDistributable :desktopApp:packageMsi
$env:NUVX_VLC_HOME='<portable VLC folder>'
$env:NUVX_DESKTOP_LIVE='1'
./gradlew.bat :desktopApp:test --tests '*DesktopPlaybackSmokeTest*' --rerun-tasks
```

The live desktop test exercises real search/decoding/pause/seek. It needs network and VLC and is skipped by default. AABs have no release signing configuration. The MSI and bundled runtime built on Windows; packaged-app launch was blocked by automatic approval review and installer execution is not verified.

CI pins eXcor source 170e81f1caf92b8cbd637340a50e6ebe686906d5, building it outside the app checkout. Action tags and SHAs were checked using upstream GitHub release/ref APIs on 2026-11-02. The new workflow has not run remotely. Existing warnings include Gradle DSL deprecations, an upstream lifecycle lint-registry incompatibility, and disabled iOS simulator tests on Windows; these are not suppressed or counted as successful native verification.
