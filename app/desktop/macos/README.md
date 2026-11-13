# nuvx for macOS

macOS uses the same Rust/egui desktop app as Windows and Linux. Its source,
Cargo manifest, and platform-specific Rust code are in the parent directory:
[`../`](../README.md).

With Rust and the Xcode Command Line Tools installed, build and run it from
`app/desktop`:

```sh
cargo run --release
```

## Install

Use the matching Apple silicon (`aarch64`) or Intel (`x86_64`) package from a
[GitHub release](https://github.com/bxanedot/nuvx/releases), if available.
Open the DMG and drag `nuvx.app` into Applications. The portable ZIP contains
the same Rust app with a portable-storage marker. Requires macOS 15 or newer.

Builds are ad-hoc signed, not Developer ID signed or notarized. macOS may
block the first launch: after verifying the download's origin and checksum,
use **System Settings → Privacy & Security → Open Anyway** if offered.
Do not disable Gatekeeper system-wide.

## CI packages

The **Apple builds** workflow compiles/tests the Rust desktop on both Apple
silicon and Intel GitHub runners, then uploads DMG and portable ZIP artifacts
with SHA-256 checksums. It runs on relevant PRs and `main` pushes, or manually
from **Actions**. Download `macos-aarch64` or `macos-x86_64` from the completed
run; artifacts expire after 14 days.

For release uploads, run it on the ref matching an existing release tag and
set `release_tag` to that tag. The existing **Desktop release** workflow also
packages macOS alongside Windows/Linux. Both use `macos/bundle.sh`; there is
no separate SwiftUI macOS app.

The iOS app and its Apple frameworks remain in `app/ios` and `core/apple`.
