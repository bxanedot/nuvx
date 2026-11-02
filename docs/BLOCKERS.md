# Blockers and Honest Limitations

| # | Area | Status | Detail / next step |
|---|---|---|---|
| 1 | eXcor streams | **Refused by YouTube** | Live test, 2026-11-02: `Sign in to confirm you're not a bot`. eXcor doesn't mint PO tokens; that is the host's job (`TokenProvider`). eXcor search and radio work. With fallback on, the router routes streams to InnerTubeX. |
| 2 | iOS / iPadOS | Not buildable on this host | The dev machine runs Windows 11. Kotlin/Native iOS and xtool need macOS or Linux, plus an Apple developer account for signing. |
| 3 | macOS packages | Need a macOS runner | Planned for CI on `macos-latest`. Signing and notarisation need Apple credentials (none configured). |
| 4 | rynx | Repository not found | `bxanedot/rynx` doesn't resolve on GitHub. Nothing has been integrated. |
| 5 | rynxRPC | No LICENSE file | The repo is owned by bxane but has no licence. Add one before distributing it. |
| 6 | Convx | Ambiguous | Several repos share the name. The closest is `cosmictaserdev-creator/Convx`, a GPL-3.0 YT Music player. It has not been confirmed as the intended one, so none of its code is used. |
| 7 | SF Symbols / GlyphStudio | Licence restriction | Apple's SF Symbols licence doesn't allow use on non-Apple platforms. nuvx uses bxane's own brand icons and original or Apache-licensed UI glyphs instead. |
| 8 | Dolby Atmos | Not offered | No licensed decoder or Atmos source path. The app must never show an Atmos toggle. |
| 9 | Custom domain for Listen Together | Using workers.dev | The worker is live at https://nuvx-listen.bxane.workers.dev. nuvx.lol is on Vercel, not Cloudflare, so `listen.nuvx.lol` isn't set up. |
| 10 | Local library runtime verification | Partially verified | Android/TV/desktop entry points are wired. The API 36 TV emulator confirmed the permission prompt appears after opening the local library. A granted MediaStore scan, real local playback, and desktop chooser/audio playback still need runtime verification. |

## Continuation limitations

- iOS framework targets and networking exist, but the native player/app/IPA remain unfinished and unverified on this Windows host.
- Windows app image/MSI build and native player smoke test passed. Automatic approval review rejected packaged EXE launch with "blocked by policy". No bypass attempted; launcher and installer execution remain unverified.
- Linux/macOS builds and VLC runtime bundling remain host-specific release work.
- Android AABs are unsigned. Store accounts/signing, policy review and physical device tests remain required.
- Cross-platform CI was added and YAML-parsed, but has not run remotely.
