# Official YouTube embedded playback research

Checked 2026-11-04 against first-party Google documentation. This is an implementation feasibility note, not runtime verification.

## What the supported player provides

The YouTube IFrame Player API embeds YouTube’s own player for a known video ID. Its documented JavaScript API can cue/load, play, pause, seek, set volume, and report player state. Modern browser support for HTML5 postMessage is required. See the [IFrame Player API reference](https://developers.google.com/youtube/iframe_api_reference).

This is a supported way to show a video in an app-owned screen, but the YouTube player remains visible and in control of its playback surface. The app must not treat the embed as a raw media URL for Media3/VLC.

## App identity and WebViews

YouTube requires an HTTP Referer identifying the API client. For app WebViews, the header is empty by default and must be supplied. The documented options include Android WebView loadUrl with a Referer header, Android loadDataWithBaseURL for a bundled HTML page, iOS WKWebView loadRequest with a Referer header, or loadHTMLString with a base URL. The Referer should use HTTPS and the stable application ID. For Nuvx, the intended Android/iOS identifier is com.bxane.nuvx; distribution identities for desktop stores must be confirmed before release. If enablejsapi=1 is used, set the origin player parameter as an additional security measure.

Google recommends operating-system WebViews where available: Android WebView or Custom Tabs, and Apple WKWebView or SFSafariViewController. SFSafariViewController cannot set Referer, so the documented alternative is the origin parameter. Windows .NET WebView2 and macOS configuration have examples; Linux and other WebView header injection details are platform-specific. Do not assume that a browser widget supplies the required identity without testing the actual request.

See [Required Minimum Functionality — identity and WebView requirements](https://developers.google.com/youtube/terms/required-minimum-functionality) and the [IFrame API origin parameter](https://developers.google.com/youtube/iframe_api_reference#Loading_a_Video_Player).

## Layout and player controls

The viewport must be at least 200 × 200 CSS pixels; where controls are shown, they must fit without reducing the viewport below that minimum. Google recommends at least 480 × 270 for a 16:9 player. A Wear OS screen is unlikely to meet this requirement, so video playback should be unavailable there unless an actual target layout proves otherwise. A full-size TV/desktop/mobile player can satisfy the size rule, subject to layout and control testing.

Keep controls enabled and the normal YouTube player functionality, branding, links, metadata, captions and ads available. Do not obscure the player or its controls with a canvas, lyrics, custom artwork, or other overlay. The API may use standard documented player parameters; it does not allow redesigning the player. With enablejsapi=1, provide origin. On iOS, playsinline=1 only provides inline rendering when the WKWebView is configured to allow inline media playback.

See [Required Minimum Functionality — player size and overlays](https://developers.google.com/youtube/terms/required-minimum-functionality) and [Embedded Players and Player Parameters](https://developers.google.com/youtube/player_parameters).

## Playback and product limits

For Nuvx, avoid autoplay on screen load. If an embed is configured to autoplay or script-starts playback, YouTube requires the player to be visible and more than half visible; only one autoplaying YouTube player may be active on a screen. Prefer leaving playback start to the visible native YouTube player control.

The app must not allow background playback of the YouTube player, offline downloads outside YouTube Premium, extraction/separation of audio and video, or modification of either component. Preserve playback context and ads. YouTube states that links within the player should open in the YouTube app when installed, otherwise the system browser. Some videos will refuse embedding or be restricted by owner, region, age, or account settings. The IFrame API reports error 101/150 when the owner disallows embedding, 100 for removed/private videos, and 153 when Referer/API-client identity is missing; report these states and preserve the player’s normal link behavior. See the [IFrame API error codes](https://developers.google.com/youtube/iframe_api_reference#onError).

The embed makes playback requests to YouTube, which share playback context data needed for rendering and policy enforcement. Any Nuvx privacy notice/consent review should cover this data flow. YouTube also requires API clients to add independent value and not recreate or obscure YouTube’s own service.

See [Complying with YouTube’s Developer Policies](https://developers.google.com/youtube/terms/developer-policies-guide) and [Required Minimum Functionality](https://developers.google.com/youtube/terms/required-minimum-functionality).

## Made for Kids precheck

YouTube policy requires checking each video's Made for Kids status through the YouTube Data API before embedding. The official guide calls `videos.list` for the video ID with at least `part=id,status`, then reads `status.madeForKids`. If the video is Made for Kids, tracking must be disabled and player-related data collection must comply with applicable law. The requirement appears in the policy revision history from 2020 and was missing from Nuvx's initial feasibility notes.

The current Nuvx source has no `videos.list` or `madeForKids` integration. A configured YouTube Data API project and a fail-closed precheck are prerequisites before any embedded player is loaded. This is in addition to the app privacy review: the embed makes playback requests to YouTube that share playback context data for rendering and policy enforcement.

See the official [Made for Kids status guide](https://developers.google.com/youtube/data/guides/made_for_kids_status), [Developer Policies](https://developers.google.com/youtube/terms/developer-policies), and [policy revision history](https://developers.google.com/youtube/terms/revision-history).

## Platform assessment

| Target | Officially documented surface | Assessment |
| --- | --- | --- |
| Android mobile/tablet | OS WebView or Custom Tabs; set app-ID Referer | Feasible for visible foreground playback. Test inline/full-screen transitions, minimum viewport, player controls, and OS navigation. |
| Android TV | Android WebView is available in principle | Potentially feasible at TV dimensions, but remote-focus/player behavior and TV policy interaction are not confirmed by the cited docs. Requires TV-device testing. |
| Wear OS | Android WebView technically exists | The required 200 × 200 viewport is unlikely to fit; treat as unsupported unless a qualifying layout is demonstrated. |
| iOS/iPadOS | WKWebView or SFSafariViewController; bundle-ID Referer/origin | Feasible for visible foreground playback. Xcode signing and actual device tests remain required. |
| macOS | Apple WebView guidance applies; app bundle ID identity | Feasible in principle; requires a native WKWebView host and correct bundle identity. |
| Windows | WebView2 example for setting Referer | Feasible in principle with a WebView2 host. Current jpackage app identity must be resolved for distribution. |
| Linux | OS WebView recommended; platform-specific Referer configuration | Feasible only after choosing and testing a supported WebView and stable app ID on each claimed distribution. |

## Nuvx recommendation

Use the embedded player only as an explicitly limited foreground video mode if Nuvx adopts the official path. It cannot replace a raw-stream engine for background playback, offline downloads, split audio/video, or layered Canvas/lyrics. Keep video unavailable on Wear OS. Before implementing, define a shared player state contract for the embedded surface and each platform host; do not report successful playback until a real public video is played on each claimed target. Current Android app ID, iOS bundle ID, and app display identity should be verified from build manifests before setting Referer.

No implementation, dependency, or product-code change was made during this research.