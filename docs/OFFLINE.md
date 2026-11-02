# Local music and playback cache

## Music on this device

The local library screen is reachable from **Library → On this device** on Android and desktop, including the TV shell. Android requests audio access when this screen opens and queries MediaStore only after permission; the API 36 TV emulator showed the permission prompt only after this route was opened. The desktop app lets the user choose a folder and scans supported audio extensions recursively without following symbolic links. A focused desktop test covers recursion, supported extensions, sorting, and symlink exclusion where the host permits symlink creation.

The emulator permission prompt was left unanswered, so a populated MediaStore scan and local audio playback were not verified. A denied prompt returns an explanatory error and retry action instead of claiming that no audio was found. Desktop folder-picker interaction and end-to-end playback also remain unverified. Selecting a row is wired to the platform player; local track IDs and file paths are not sent to a YouTube engine or a Listen Together room.

The app does not copy local files into its own library directory or upload them. Playback depends on the platform's installed codecs and access to the file. Android library access is read-only and can be revoked in system settings. The desktop folder picker does not follow symbolic links.

## Streaming cache

Android uses a Media3 disk cache capped at 256 MiB under the app's cache directory. It caches streamed remote media to help a currently resolved stream continue through short network interruptions. Local `content:` and `file:` URIs are explicitly excluded.

This is a playback cache, not an offline download feature. Stream addresses can expire or change, so the cache does not promise that a song will be available after closing the app, restarting playback, or losing network access. The app has no YouTube audio-file download button. Desktop currently has no streaming cache.

## Platform status

- Android mobile, TV, and Wear share the MediaStore reader and Media3 cache; permission timing was checked on the TV emulator, but a granted scan was not.
- Windows, Linux, and macOS desktop use the folder picker and direct local-file playback; recursive scanner behavior has a unit test, but picker/audio runtime behavior was not checked.
- iOS/iPadOS local-library access and offline playback are not implemented by this phase.
