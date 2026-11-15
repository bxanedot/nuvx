# InnerTubeX Apple integration

Source: https://github.com/MetrolistGroup/innertubex
Pinned revision: e4fb0003ce092beb71f3de464c661bf9f3f54c7f (v0.11.7)

`src/` contains upstream's `commonMain`, `nativeMain`, `commonTest` and
`nativeTest` at that revision, with the v0.11.7 audio-token preference merged
into the existing local source. The original vendored tree also has local
comment cleanup; preserve it when updating instead of replacing `src/` blindly.
Upstream's Android, JVM and Windows source sets are not copied. `LICENSE`,
`README.md`, `SECURITY.md` and `CHANGELOG.md` are upstream's.

The local `build.gradle.kts` is the only adapter. It adds macOS arm64 alongside
upstream's iOS targets, leaves out the Android/JVM targets and publishing
plugins, and takes QuackJS from `vendor/quackjs` because upstream publishes no
macOS arm64 artifact of it.

Player-script work (signature and `n` transforms) runs in QuackJS, a
pure-Kotlin interpreter, on upstream's own large-stack native threads
(`CipherDispatcher.native.kt`). The JavaScriptCore adapter this directory
carried for the earlier EJS solver is gone with that solver.

nuvx supplies upstream's visitor-bound player-request token and video-bound
streaming-data token through an isolated WKWebView. Challenge HTTP uses a public,
cookie-free client; web rendering blocks network loads, matching upstream.

To update: review upstream's `src/` changes and merge them into the vendored
tree, update the four upstream documents from the new tag, move the pin above,
match Ktor and kotlinx versions to upstream's
`gradle/libs.versions.toml` here and in `shared/build.gradle.kts`, check
whether the QuackJS pin moved, then run `:innertubex:macosArm64Test` and
`:shared:macosArm64Test`.
