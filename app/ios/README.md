# iOS app

The iOS app target is in [`mobile/`](mobile), parallel to
[`app/android/mobile`](../android/mobile). Shared Apple playback and UI sources are in
[`core/apple/src`](../../core/apple/src). macOS uses the Rust desktop app in
[`app/desktop`](../desktop/README.md), alongside Windows and Linux.

## Install on iPhone or iPad

Requires **iOS/iPadOS 18 or later**. This is a source port: Apple compilation,
signing, and device playback have not been verified. An IPA is not promised for
every release. Check the [GitHub release assets](https://github.com/bxanedot/nuvx/releases)
for `Nuvx-<version>-build<number>-ios-unsigned.ipa`. If there is no `.ipa`,
someone with a Mac must build one first; the source ZIP, APK, and desktop
packages cannot be installed on iOS.

Sign-in sessions are stored in device-only Keychain items, not published to
iCloud Keychain or adopted automatically from another device.

An **unsigned IPA cannot be installed by opening it in Files or Safari**.
A sideloading tool must sign it with your own Apple Account and install it.
No jailbreak is required, and a paid developer account is not required for
basic sideloading. You need an iPhone or iPad to install and test the app.

### From Windows: Sideloadly

1. Download [Sideloadly from its official site](https://sideloadly.io/).
   Follow its Windows prerequisites for Apple's device drivers, iTunes, and
   iCloud; its current instructions require Apple's web installers rather
   than the Microsoft Store versions.
2. Download the nuvx iOS `.ipa` from the release assets, if available.
3. Connect an unlocked iPhone or iPad by USB, choose **Trust This Computer**
   on the device, and select it in Sideloadly.
4. Drag the IPA into Sideloadly, enter the Apple Account you want to use for
   signing, and choose **Start**. Complete Apple's two-factor authentication
   if prompted. Use only the official tool; do not share account credentials
   with the nuvx project or third-party signing sites.
5. On the device, trust the developer profile under **Settings → General →
   VPN & Device Management** if requested. Enable **Settings → Privacy &
   Security → Developer Mode**, restart, and confirm if prompted.
6. Open nuvx. Keep Sideloadly's refresh service available, or re-sign and
   reinstall before the signature expires.

### Alternatives: AltStore Classic or SideStore

- **AltStore Classic:** follow the official [Windows installation guide](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows)
  or [macOS installation guide](https://faq.altstore.io/altstore-classic/how-to-install-altstore-macos).
  Use **My Apps → +** in AltStore to select the downloaded nuvx IPA. AltServer
  must be reachable for refreshes. AltStore PAL is a different distribution
  method and is not a substitute for importing an arbitrary unsigned IPA.
- **SideStore:** follow its official [setup guide](https://docs.sidestore.io/docs/installation/install)
  and prerequisites on a supported computer, then import the nuvx IPA from
  **My Apps → +**. Follow SideStore's current local VPN and refresh instructions;
  initial setup requires a computer, but later refreshes can run on the device.

### Signing limits, updates, and troubleshooting

- Free Apple Account signatures normally expire after **7 days**. Refresh
  before expiry or the app will stop opening. Free accounts normally allow
  **3 active sideloaded apps**; the signing tool itself may use one slot, and
  app extensions consume additional App IDs. Follow the tool's displayed limits.
- Paid Apple Developer memberships generally permit longer provisioning,
  often up to a year, subject to certificate and profile validity.
- Re-sign updates with the **same account and bundle identifier** to preserve
  app data. Do not uninstall as the first troubleshooting step: uninstalling
  can delete downloads and settings. Back up anything important first.
- Signing tools may remove unsupported entitlements or extensions. Widget,
  Live Activity, App Group, and iCloud behavior depends on the provisioning
  profile; these features are not guaranteed with free-account signing.
  Do not buy a developer membership expecting this unverified port to work.
- “Untrusted Developer” usually means the profile needs to be trusted.
  “App is no longer available” often means signing expired. For signing or
  device-detection errors, check the signing tool's official troubleshooting
  guide before changing the app or removing data.

## Build from source on a Mac

On an Apple Silicon Mac with a current Xcode SDK, Rust, CMake, a JDK,
and XcodeGen installed, run from `app/ios`:

```sh
scripts/bootstrap-apple.sh
```

That checks the port, builds the native and shared frameworks, and generates
the iOS Xcode project. Open `mobile/Nuvx.xcodeproj` and select
the **Nuvx** scheme. Select your own signing team for the app and widget before
installing on a connected device. If you change bundle identifiers, update
the matching extension and App Group identifiers too. A free team may require
disabling unsupported capabilities. The bootstrap script does not sign or
publish a release.

To package an unsigned IPA after bootstrapping, from the repository root:

```sh
bash app/ios/scripts/package-ios-ipa.sh 0.9.0
```

Use the version in `mobile/project.yml`. The file is written to
`app/ios/build/release/` and still needs signing with one of the tools above.
Building requires macOS/Xcode; installing an already-built IPA can be done
from Windows.

## GitHub Actions builds

The **Apple builds** workflow builds the iOS port and the universal Rust macOS app on
GitHub-hosted Macs for Apple-related pull requests, pushes to `main`, and
manual runs. You do not need to own a Mac to trigger it.

1. Open **Actions → Apple builds → Run workflow** after the workflow reaches
   `main`, choose the source branch, and leave `release_tag` blank for a test build.
2. After a successful run, download **ios-unsigned** from the run's
   **Artifacts** section (GitHub login required), unzip it, and use the `.ipa`
   with one of the signing tools above. CI artifacts expire after 14 days.
3. For release uploads, select the ref matching an **existing** GitHub release
   tag, enter that tag in `release_tag`, and run manually. The workflow verifies
   the tag, commit, and app version before uploading packages and checksums.

CI never receives your Apple Account credentials and does not sign the IPA for
installation. A successful build is not a device playback or sideloading test.
macOS artifacts are ad-hoc signed, not Developer ID signed or notarized.

See `docs/ANDROID_PARITY.md` for audited platform differences.
