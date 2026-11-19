# rust-anything evaluation (Task 00)

Date: 2026-11-19. Nuvx revision: `main` at the commit that adds this file. Nothing in Nuvx's product code was changed by this task.

## What was inspected

| Item | Value | How |
|---|---|---|
| Repository | https://github.com/erkamyaman/rust-anything | GitHub API (`gh api repos/erkamyaman/rust-anything`) |
| Live HEAD | `98adbe29003a69783c727146d0c3fe637b174102` (2026-11-18T21:09:56Z) | `gh api .../commits/main` |
| Pack baseline | `8a2e1d1f3725c25cea9d6cd851f12d060540b057` | 15 commits behind HEAD; the 15 are README/branding, CI action bumps, dependency bumps (json5, tree-sitter, toml_edit), a tsconfig path-alias fix and two crate-mapping notes. No change to supported languages or the record/replay safety model. |
| Latest release | `v1.5.1` (2026-11-18T09:11:21Z) | `gh api .../releases/latest` |
| Crate | `rust-anything` 1.5.1, edition 2024, `rust-version = 1.97` | `Cargo.toml` at HEAD |
| License | MIT | repo metadata and `Cargo.toml` |
| Local toolchain | `rustc 1.100.0` (meets 1.97) | `rustc --version` |

## Installation and dry run: PASS (synthetic unit only)

Installed 2026-11-19 with the owner's OK, pinned, into a scratch root (not on PATH, `setup` **not** run):
`cargo install rust-anything --version 1.5.1 --locked --root <scratch>/ra` → `rust-anything 1.5.1`.
Building WASM bridges also needed `rustup target add wasm32-unknown-unknown` and
`cargo install wasm-bindgen-cli --version 0.2.130 --locked` (same scratch root; the version must match the
crate's wasm-bindgen).

`--help` lists: analyze, bridge, refresh, record, build, diff, bench, profile, shadow, gate, app, mcp, setup.
There is **no `doctor` command** in 1.5.1.

Dry run on a made-up 4-line TypeScript function (`clampVolume`), in a throwaway folder, no Nuvx code:

| Step | Result |
|---|---|
| `analyze .` | 1 unit, readiness 100, bridge wasm-bindgen, "no tests found" |
| `bridge . src/clamp.ts` | crate with `port.rs` stub (`unimplemented!`); the port is written by hand |
| `record .` | 25 generated cases, 0 threw |
| `build .` | first failed: missing wasm32 target, then wasm-bindgen-cli; built after both |
| `diff .` | first failed: generated loader is CommonJS and the demo `package.json` had `"type": "module"`; **25/25 match** after removing it |
| `gate .` | first failed on clippy (`manual_clamp`); **Gate passed** after the fix: cargo test, no unsafe, no panics, API, 25/25 |

Lessons for Nuvx: packages with `"type": "module"` (the web app) need the generated loader renamed to `.cjs`
or a CommonJS wrapper; the gate enforces clippy `-D warnings`.

## Supported language boundaries (from the README at HEAD)

| Language | Unit | Status |
|---|---|---|
| TypeScript/JavaScript | folder or file; napi-rs or wasm-bindgen | Supported |
| Python | package/module; PyO3 | Supported (Nuvx has no product Python) |
| C | `.c` file; `extern "C"` | Supported. **Nuvx's `core/native` is C++ headers**, so not applicable. |
| Go, Ruby | package / module functions | Supported (Nuvx has none) |
| Java | `public static` methods; JNI | Supported. Nuvx's only Java is vendored Media3 FFmpeg JNI glue: not applicable. |
| PHP, C# | preview | Not relevant |
| Kotlin / KMP, Swift / SwiftUI, Compose, CSS, Slint | **No bridge** | Must use hand-written golden fixtures plus UniFFI/JNI/C-ABI bindings. Converting Kotlin to Java to fit the tool is not allowed. |

The tool proves only the cases it recorded. A passing `diff`/`gate` never replaces Nuvx's own tests, device
checks or visual parity checks.

## Side-effect isolation policy (binding for every later task)

`record` and `app record` run the original code once per generated case (`app record` twice); `diff`, `gate` and
`app replay` run them again. Therefore, before any of those commands:

1. Work in a disposable copy outside the user's data; never point at a real install, its data directory, or the
   phone.
2. No network: run with outbound traffic blocked or every HTTP dependency replaced by a local mock. No YouTube,
   Google, Spotify, Last.fm, Discord, Cloudflare or Oracle endpoints, ever.
3. No credentials in the environment: unset every token, cookie, signing key and API secret; use test values only.
4. Databases: in-memory or a throwaway file in the scratch directory (the sync server already supports `:memory:`).
5. Filesystem: a scratch `HOME`/`TMP`; the unit must not write outside it.
6. Read every generated wrapper and driver before running it.
7. Never commit recorded cases that contain personal data, tokens or listening history.
8. Any unit that cannot meet 1–5 is `NOT_APPLICABLE`; it gets hand-written fixtures instead.

## Nuvx candidates

See `TOOL_ELIGIBILITY.csv`. Summary:

- **Good:** pure TypeScript helpers in `app/web/app/src/lib/` (`artistCredits.ts`, `meshPalette.ts`) and
  `app/web/app/src/lyrics/parse.ts`; `core/worker/src/request-body.ts`. All are deterministic string or number
  functions with no I/O.
- **Prefer existing Rust over translating:** the desktop already has a Rust LRC parser (`app/desktop/src/lyrics/lrc.rs`).
  The canonical core should promote that one and use `rust-anything` only to *diff* the web parser against it,
  not to generate a second parser.
- **Whole-app only with mocks:** `core/sync/src/server.ts` (HTTP + SQLite). Its existing node tests already use an
  in-memory database; extend those as golden fixtures rather than running `app record`.
- **Not applicable:** Kotlin/KMP (`core/shared`, Android apps), Swift (`core/apple`, `app/ios`), C++ (`core/native`),
  vendored Java JNI, all UI code, the desktop (already Rust).

## Decision

`rust-anything` is a helper for a handful of pure TypeScript units. It is not the migration engine. The bulk of the
migration (Kotlin, Swift, UI) needs hand-written Rust, shared golden test vectors and FFI. Every later task checks
`TOOL_ELIGIBILITY.csv` before porting a unit.
