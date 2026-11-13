# Android parity notes

The iOS app is in `app/ios`; Windows, Linux, and macOS use the Rust desktop app
in `app/desktop`. Shared Apple code lives in `core/apple/src`. Android-specific
JVM code is not copied into Apple targets when it cannot be shared by the
native targets.

## Features checked against Android

| Feature | Apple status |
| --- | --- |
| Podcasts | The navigation destination loads the Podcasts Home chip feed, renders its shelves, and uses the existing shelf play/detail actions. It is separate from followed shows in Library. |
| Radio | The screen searches stations, lists popular stations by exact country code, prefers the device country, deduplicates stream URLs, records station clicks, and retries across Radio Browser hosts. |
| Home visibility | Hide Top Picks, Hide Home Moods, Hide Home Videos, and Hide Recents preferences are present. |
| Feature introductions | First visits to Radio and Podcasts offer keep/hide actions, then explain the Settings > Miscellaneous controls; completion and visibility persist. |
| Support prompts | Launches 8, 20, 32, and so on alternate Ko-fi and GitHub prompts. “Don’t show again” persists across launches. |
| Discord invite | Shown after onboarding/update notices; “Not Now” dismisses for this run and “Join Server” persists suppression. |

Radio shares Android's Radio Browser endpoints and request limits without introducing another data layer. SwiftUI presentation and the playback engine remain platform-specific.

## Known differences

- Android builds Top Picks with its taste-based `TopPicks` algorithm and listener-scoped hourly cache. Apple currently selects distinct playable cards from the Home feed instead. The builder lives in Android/JVM-only shared code, so copying it into the Apple KMP module would duplicate the algorithm.
- Android's Radio screen caches country and station results for the process lifetime. Apple currently reloads them when its screen is recreated.
- Find Your Mood uses different presentation and selection behavior on Apple; its tiles open the real category feed but do not reproduce Android's artwork and styling.
- The checked-in eXcor artifact is Android-only. iOS keeps its existing native/KMP stream resolver; no nonfunctional eXcor toggle is exposed.

These are source-level notes, not a claim of visual or runtime parity. The Apple
builds workflow supplies macOS/Xcode compilation without local Apple hardware.
Sideloading, visual checks, and device playback still require an iPhone or iPad.
