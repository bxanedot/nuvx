# QuackJS

Source: https://github.com/MetrolistGroup/quackjs
Pinned revision: d867d98f9f2562009399cdfecd67e626a086f878 (0.1.4)

The JavaScript interpreter `vendor/innertubex` runs YouTube player scripts in.
It is vendored because upstream publishes no macOS arm64 artifact.

`src/commonMain` retains upstream behavior; source notices have been relocated
to the root `THIRD_PARTY_LICENSES/` directory along with the standalone texts.
Upstream tests and player fixtures are not copied. The local `build.gradle.kts`
declares only the Apple targets nuvx builds.
