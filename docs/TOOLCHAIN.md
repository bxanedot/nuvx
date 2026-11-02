# Toolchain

Versions were checked against the official release feeds on 2026-11-02: Maven Central and Google Maven metadata, `services.gradle.org/versions/current`, `api.adoptium.net`, `nodejs.org/dist/index.json`, and the npm registry. Only stable releases are used.

| Tool / library | Version | Source | Notes |
|---|---|---|---|
| Gradle (wrapper) | 9.8.2 | services.gradle.org | wrapper jar SHA-256 `3b8a2577…8e98` matches the published checksum |
| JDK for running Gradle | Temurin 25 LTS | adoptium.net | latest LTS |
| JDK toolchain for `:shared` | Temurin 27 | adoptium.net | **forced by eXcor**: its desktop artifact is JVM 26 bytecode, so the desktop target compiles with `jvmTarget=26`. Android bytecode stays at 17. |
| Kotlin | 2.4.21 | Maven Central | |
| Compose Multiplatform | 1.12.2 (Material3 1.9.0) | Maven Central | |
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
# 2. Build Nuvx. Gradle auto-provisions JDK 27 through foojay; if it is already installed, point Gradle at it:
./gradlew -Porg.gradle.java.installations.paths=<path-to-jdk-27> :shared:desktopTest
```

`local.properties` needs `sdk.dir=<Android SDK path>`. It is gitignored.

## Known limits
- iOS: Kotlin/Native iOS targets and xtool need macOS or Linux. Windows can't build them (see BLOCKERS.md).
- Once eXcor publishes an LTS-compatible JVM artifact, move the desktop target back to JDK 25 / `jvmTarget=25`.
