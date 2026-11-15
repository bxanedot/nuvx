# Android APK smoke test

Date: 2026-11-15

## Setup

- Runner: [ApkStart](https://github.com/RoutineMiku/apkstart), v0.4.1, checkout `928a477`.
- Device: Android Emulator 37.2.13, API 36, x86_64 Google Play image, WHPX, 1080×2400.
- APKs: local signed debug outputs. These are install/launch smoke checks, not the signed release artifacts.
- The APKStart source checkout and emulator are scratch files under the local ignored `work/` directory; they are not part of this repository.

## Results

| APK | ApkStart result | Follow-up and scope |
| --- | --- | --- |
| Phone (`app-prod-debug.apk`) | 13/13 checks passed: runtime, shared folder, APK parse, install, foreground launch, screenshot | Screenshot saved, but the captured activity surface was black. Visual UI rendering is unverified. |
| TV (`tv-prod-tv-debug.apk`) | 12 checks passed; the 4-second foreground check failed | Android later logged the activity as displayed after 12.4 seconds and it was resumed. The screenshot showed setup content with a black overlay; visual UI rendering is unverified. |
| Auto (`tv-prod-auto-debug.apk`) | 12 checks passed; the 4-second foreground check failed | Follow-up showed the Auto activity resumed with a live process. The screenshot did not verify a usable UI. |
| Wear (`NuvxWear-prod-debug.apk`) | 13/13 checks passed on install/launch path | This used a phone emulator, not Wear OS. It verifies package installation on that image only; watch UI, relay, and playback behavior remain unverified. |

## Limits

- ApkStart waits four seconds before its foreground assertion. The TV and Auto apps take longer to reach the resumed state on this cold API 36 emulator, so their runner failures are timing failures rather than evidence of an install failure.
- ApkStart's screenshot check only verifies that screenshot bytes were returned. The captured app surfaces did not establish visual correctness for phone, TV, or Auto.
- This emulator is not Android TV, Wear OS, or an Android Auto head unit. No remote navigation, projection, vehicle audio, or physical-device behavior was tested.
- Android release builds and package metadata were verified separately, but these ApkStart runs used debug APKs. CI signing and release-asset validation remain pending.

The ApkStart checkout's own `npm test` suite passed 115/115 tests. That validates ApkStart's test code, not Nuvx UI or playback behavior.
