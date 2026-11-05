# Local music and playback cache

## Music on this device

The local library screen is reachable from **Library → On this device** on Android and desktop, including the TV shell. Android requests audio access when this screen opens and queries MediaStore only after permission; the API 36 TV emulator showed the permission prompt only after this route was opened. The desktop app lets the user choose a folder and scans supported audio extensions recursively without following symbolic links. A focused desktop test covers recursion, supported extensions, sorting, and symlink exclusion where the host permits symlink creation.

The emulator permission prompt was left unanswered, so a populated MediaStore scan and local audio playback were not verified. A denied prompt returns an explanatory error and retry action instead of claiming that no audio was found. Desktop folder-picker interaction and end-to-end playback also remain unverified. Selecting a row is wired to the platform player; local track IDs and file paths are not sent to a YouTube engine or a Listen Together room.

The app does not copy local files into its own library directory or upload them. Playback depends on the platform's installed codecs and access to the file. Android library access is read-only and can be revoked in system settings. The desktop folder picker does not follow symbolic links.

## Streaming cache

Android uses a Media3 disk cache capped at 256 MiB under the app's cache directory. It caches streamed remote media to help a currently resolved stream continue through short network interruptions. Local `content:` and `file:` URIs are explicitly excluded.

This is a playback cache, not an offline download feature. Stream addresses can expire or change, so the cache does not promise that a song will be available after closing the app, restarting playback, or losing network access. The app has no YouTube audio-file download button. Desktop currently has no streaming cache.

## Provider catalog downloads

Checked 2026-11-05. Nuvx does not currently have an authorized provider flow for downloading full catalog tracks, albums, playlists, or artist catalogs. Do not infer download rights from a catalog or streaming API, and do not show download actions that cannot complete through a documented, authorized flow.

| Source | Documented status | Nuvx decision |
|---|---|---|
| YouTube / YouTube Music | YouTube's Terms allow downloads only when expressly authorized by the Service, with prior written permission, or where law permits. Nuvx has no documented audio-file download API. | Do not save or rip YouTube audio streams. The transient Android cache remains playback-only. |
| Spotify | The Web API's policy notes say Spotify content may not be downloaded and prohibit facilitating downloads or stream ripping. | No Spotify catalog download feature. |
| Apple Music | The Apple Developer Program License Agreement prohibits downloading MusicKit content except where Apple documentation permits it. MusicKit can report which library items are already downloaded, but that does not grant Nuvx a general catalog-file download flow. | Do not copy or export MusicKit content. A future native integration must use only documented MusicKit playback/library behavior. |
| Deezer | No documented public third-party full-track download flow was confirmed in this check. Catalog access alone does not establish storage permission. | No Deezer catalog download feature until Deezer documents and authorizes a supported flow. |
| TIDAL | The official Android SDK exposes offline-play hooks, and its latest player release is 0.0.74 (2026-10-26). Its published authentication guide still says client-credentials access is limited to previews, while the full-length authorization-code/device-login flows are only available to TIDAL internal apps. | Candidate only. Before implementation, Nuvx needs TIDAL to enable a public full-length/offline authorization flow and explicitly approve offline storage. Once enabled, use the SDK offline-play API; never turn a resolved streaming URL into a download. Active app work is Android only for now; Windows, Linux, macOS and iOS/iPadOS are paused pending bxane's direction. |
| User-owned local files | The local library reads files in place and does not copy or upload them. | Keep local playback separate from provider catalog downloads; no copying is added here. |

**Blocker / required action:** no eligible full-catalog download source is currently available to this project. Before work resumes on provider downloads, bxane must obtain TIDAL developer access that supports full-length playback and explicit offline-storage permission, or identify another provider with a documented authorized download API. Until then, keep provider download controls absent. See [BLOCKERS.md](BLOCKERS.md).

Sources: [YouTube Terms](https://www.youtube.com/t/terms), [Spotify Web API track reference](https://developer.spotify.com/documentation/web-api/reference/get-track), [Apple Developer Program License Agreement](https://developer.apple.com/support/terms/apple-developer-program-license-agreement/), [Apple MusicKit downloaded-content query](https://developer.apple.com/documentation/musickit/musiclibraryrequest/includeonlydownloadedcontent), [TIDAL Android playback SDK](https://tidal-music.github.io/tidal-sdk-android/player/index.html), [TIDAL Android player release notes](https://github.com/tidal-music/tidal-sdk-android/releases), [TIDAL Android player README](https://github.com/tidal-music/tidal-sdk-android/blob/main/player/README.md), and [TIDAL Android authentication guide](https://tidal-music.github.io/tidal-sdk-android/auth/index.html).

## Platform status

- Android mobile, TV, and Wear are the active product scope. They share the MediaStore reader and Media3 cache; permission timing was checked on the TV emulator, but a granted scan was not.
- Windows, Linux, macOS and iOS/iPadOS work is paused until bxane provides the next platform direction. Existing desktop local-file support is historical and is not part of current Android verification.
