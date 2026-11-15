# nuvx ecosystem: one Rust core for every device (plan, 2026-11-15)

Status: proposal, nothing built yet.

## Problem

The same audio logic is written four times, in four languages:

| Device | Analysis / AutoMix / loudness lives in |
|---|---|
| Android phone, TV, Auto | C++ (`core/native/analyzer`, `core/native/loopmix`) via JNI, plus Kotlin |
| iOS / macOS | Rust `app/ios/native-core` (UniFFI) |
| Desktop (Win/Linux/mac) | a second Rust engine in `app/desktop/src/audio` |
| Web app + extension | TypeScript (`app/web/app/src/playback/automix`) |

Every fix or feature (loudness target, sleep fade, AutoMix tweaks) has to be made 4 times and drifts.

## Language: Rust

Asked for "most powerful but least resource used": Rust. It has no GC, C-level speed and memory use, compiles to every target nuvx ships (Android `.so`, iOS/macOS static lib, Windows/Linux, WebAssembly), and nuvx already has 76k lines of it. Native UIs (Compose, SwiftUI, egui, React) stay: rewriting 300k lines of UI buys nothing and risks everything.

## Target shape

```
core/rust/nuvx-core      one crate: decode, analysis, AutoMix, loudness (EBU R128), EQ, spatial, lyrics timing
   ├─ UniFFI → Swift     iOS / macOS (what native-core already does)
   ├─ UniFFI → Kotlin    Android phone / TV / Auto (replaces the C++ JNI analyzer, step by step)
   ├─ rlib               desktop app links it directly (drops its duplicate engine)
   └─ wasm-bindgen       web app + extension (replaces the TS analyzer)
```

## Steps (each ships on its own, smallest first)

1. Move `app/ios/native-core` to `core/rust/nuvx-core` as a Cargo workspace member; iOS keeps working unchanged.
2. Desktop: link `nuvx-core` for loudness + EQ, delete the duplicated code in `app/desktop/src/audio`.
3. Android: generate Kotlin bindings with UniFFI, switch analysis from C++ to `nuvx-core` behind the existing `TrackAnalyzer` interface, then delete the C++ analyzer.
4. Web: build `nuvx-core` to wasm, swap `analyzer.ts` for it (the worker interface stays).
5. New features land once in `nuvx-core` (loudness target, lossless preference, lyrics timing) and reach every device.

## What to take from the six repos

| Repo | License | Take | Skip / why |
|---|---|---|---|
| demixr/demixr-app | MIT | **Idea:** stem separation (vocals/drums/bass) for a karaoke / sing-along mode, Demucs v4 via ONNX. nuvx already ships onnxruntime on Android. Write it fresh in `nuvx-core`. | Dart/Flutter code itself. The model is heavy (~80 MB, slow on low-end phones), so start desktop-first. |
| anomalyco/rune | MIT | **Idea:** headless rendering to PNG + element-tree dumps so agents can test UI without a screen. | Replacing desktop egui with Rune: pre-release, no mobile, pin-a-rev only. Revisit when it's stable. |
| expo/exact | MIT | **Idea:** one test contract run on every platform. Apply it as shared test cases for `nuvx-core`. | Rewriting UI in Exact: Android/Windows "early, in progress"; nuvx already has native UIs. |
| RoutineMiku/apkstart | MIT | Already used for APK smoke tests on Windows (`docs/ANDROID_APK_SMOKE_TEST.md`). Keep it. | Nothing new. |
| ruvnet/ruflo | MIT | Dev-side agent harness only (its predecessor claude-flow is already set up in your eXcor folder). Optional. | No app value. |
| Rohit-554/NStream | **none** | Nothing. | No license, so it can't be reused; it's a KMP template nuvx already surpasses. |

## Second batch: tooling and website repos

| Repo | License | Take | Skip / why |
|---|---|---|---|
| heydamir/unslop | CC-BY-4.0 | **Use as a design pass on nuvx.lol.** The current hero (pink-purple gradient, italic serif, glow) matches the "AI-looking" patterns it removes. Install as a plugin; don't vendor it. | — |
| Motionsites-AI/claude-skill | MIT (skill); examples/media © MotionSites | Optional second design skill for a cinematic landing section. | Its example prompts/media can't be redistributed; don't copy them into the repo. |
| adrianhajdin/award-winning-website | **none** | Idea only: scroll-driven animation (GSAP-style) for one showcase section. | No license, so no code reuse. |
| xtool-org/xtool | MIT | Later: build/sign the iOS app from Windows via WSL. | nuvx iOS uses an XcodeGen `project.yml`, not SwiftPM; converting is real work, and CI (macOS runners) already builds iOS. |
| storytold/craft-anything | MIT/Apache-2.0 | Nothing needed. | Early-alpha "rebuild any app" engine; nuvx isn't cloning anything. |
| mattpocock/skills, affaan-m/ECC, muratcankoylan/Agent-Skills-for-Context-Engineering | MIT | Optional dev-side skills for coding agents; pick one pack at most. | They overlap each other and Ponytail/superpowers you already use; no app value. |
| FlorianBruniaux/claude-code-ultimate-guide | CC-BY-SA-4.0 | Reading material for agent workflows. | Docs, not code; ShareAlike would bind anything copied. |

## Website (nuvx.lol, `core/website`)

Keep it: static HTML/CSS/JS on Vercel, fast, already covers every device, release channels, nightlies, web player, extension, Canvas, community and docs. Ultra-minimal changes:

1. Add one **Ecosystem** section: a single diagram of phone / TV / Auto / Wear / desktop / web / extension sharing one account, library, Listen Together and (later) the Rust core, each linking to its download.
2. Run an unslop design pass on the hero and cards: replace the generic gradient + italic serif with nuvx's own type and colour (signature mark, waveform motif), keep layout and copy.
3. No framework migration, no rebuild.

## Blockers

- No Rust toolchain on this Windows machine (`cargo` missing), so Rust can't be built locally until rustup is installed. iOS/macOS builds still need CI (no Xcode here).
- Codex is editing the same repo; this work stays on its own branch.

## Not decided

- Whether Android's C++ analyzer is replaced fully or kept as a fallback during the switch.
- Whether stems/karaoke is wanted at all, given model size.
