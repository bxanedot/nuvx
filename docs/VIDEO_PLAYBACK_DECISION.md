# Video playback path decision

Updated 2026-11-04. This is a product/architecture decision record, not evidence that YouTube video playback works in nuvx.

## Current evidence

- The Android emulator reaches video selection but InnerTubeX extraction returns `NO_PLAYABLE_STREAM`; diagnostics identify an unavailable GVS PO-token provider. No authorized, documented provider is configured.
- A fresh opt-in live recheck on 2026-11-04 again returned `NO_PLAYABLE_STREAM`. Its 27 safe diagnostic attempts included `GVS PO-token provider unavailable`; no signed media URLs or request headers were printed. The same run's diagnostic only identified legacy direct handling for audio, not a video stream.
- The current committed `774160e` backend has no native video method; `VideoCapableBackend` and `InnerTubeXBackend.video()` are uncommitted additions in the current worktree. The repository history therefore does not establish that native music-video playback worked before. Community Canvas playback is a separate feature and does not prove native music-video playback.
- InnerTubeX exposes a `TokenProvider` contract, but its README says token minting and persistence are host-owned. The optional authenticated TV discovery path is experimental and audio-only. The library contract does not supply a ready-made GVS token service. See the [InnerTubeX README](https://github.com/MetrolistGroup/innertubex).
- Dependency review on 2026-11-04 confirmed Nuvx pins InnerTubeX v0.11.2. Its [versioned README](https://github.com/MetrolistGroup/innertubex/blob/v0.11.2/README.md) says token providers and token minting/persistence are host-owned, and its optional authenticated-TV comparison is experimental and audio-only. The [v0.11.2 changelog](https://github.com/MetrolistGroup/innertubex/blob/v0.11.2/CHANGELOG.md) lists build/dependency and deciphering changes, not an authorized GVS token service. A dependency upgrade is therefore not a safe playback fix.
- YouTube's [IFrame Player API](https://developers.google.com/youtube/iframe_api_reference) is an official playback option for a known video ID. It provides player methods and playback events, but it embeds the YouTube player rather than returning a media stream for Media3 or VLC.
- The current public [nuvx privacy notice](https://nuvx.lol/privacy), checked on 2026-11-04, says app privacy behavior has not been finalized and that the notice is not a complete privacy policy for app builds. YouTube requires an app privacy policy and explains that loading its embedded player shares basic user data. Keep the embed deferred until an accurate app-specific policy and privacy review are ready. See the official [Developer Policies guide](https://developers.google.com/youtube/terms/developer-policies-guide).

## Official embedded-player constraints

- Embedded-player clients must identify themselves with an HTTP `Referer`. YouTube documents platform-specific methods and recommends the operating system's WebView on Android and Apple platforms. The app ID must match the installed/distributed app identity. See [Required Minimum Functionality](https://developers.google.com/youtube/terms/required-minimum-functionality).
- The visible player must be at least 200 by 200 pixels; 480 by 270 is recommended for a 16:9 player. Its branding, advertisements, metadata, links, controls, and normal functionality must remain available. Do not cover or obscure the player or its controls.
- Loading an embedded player sends basic user data to YouTube for rendering, playability checks, and abuse prevention. Keep autoplay off unless a user starts playback, and include this data flow in the app's privacy review. See the [Developer Policies guide](https://developers.google.com/youtube/terms/developer-policies-guide).
- YouTube's policies prohibit enabling background playback, downloading YouTube videos outside the Premium experience, or separating/promoting video and audio components. See the [Developer Policies guide](https://developers.google.com/youtube/terms/developer-policies-guide).
- Before embedding each video, YouTube policy requires checking its Made for Kids status through the YouTube Data API (`videos.list`, requesting at least `id,status` and reading `status.madeForKids`). If it is child-directed, tracking must be disabled and player-related data collection must comply with applicable law. This requirement is documented in policy history since 2020; it is newly captured in this Nuvx review, not a new 2026 rule.
- The repository has no `videos.list` / `madeForKids` implementation. The embed must remain deferred until Nuvx has a configured YouTube Data API project, performs the precheck before loading each player, and completes the app privacy review.
- The official embed therefore cannot satisfy nuvx's requested raw-stream integration, background playback, downloads, independent audio/video switching, or lyrics/Canvas layered over the embedded player. Videos may also refuse embedding when their owners disable it.
- Android, Apple, and desktop each need a platform WebView and app-identity implementation. The current iOS player is unfinished; Linux, Windows, macOS, TV, and WearOS embed behavior has not been verified. Small WearOS surfaces may not meet YouTube's minimum player size.

## Decision required before implementation

There is no option verified here that satisfies the full video specification. The official embed remains deferred until the app privacy policy and the YouTube Data API Made for Kids precheck are implemented. Choose the product path:

1. **Keep native-stream video blocked** until bxane obtains an authorized, documented GVS token/playback provider. This preserves the existing Media3/VLC design and leaves the current playback blocker in place.
2. **Adopt official embedded playback as a limited foreground mode.** This requires an architecture change to platform WebViews and the standard YouTube player. It excludes background video, downloads, separate audio/video switching, and overlays on the player. The current video feature list would need to be marked unavailable or redesigned accordingly.

Recommendation: use option 1 if the full integrated playback requirements remain mandatory. Use option 2 only if compliant, visible foreground video is valuable enough to accept the stated product limits. No embedded-player implementation has been changed pending this decision.

## Current fallback — 2026-11-04

The shared Now Playing screen now offers a user-tapped “Watch on YouTube” action after native video playback fails, provided the selected track has a valid video ID. The standard watch URL is handed to the operating system, which can open YouTube or the system browser. The action does not autoplay, change the queue, or claim in-app playback. Native extraction remains blocked; the embedded-player architecture decision above remains open for future in-app foreground playback.
