# BitChord reference audit for nuvx (2026-11-15)

Reference only: nothing in nuvx was changed. Compared against `origin/main` @ `46e82cb`.
Method: three-way diff of BitChord 1.9.2 vs nuvx vs nuvx's fork point (`d9c7cd4`, the initial commit). Comments and branding (BitChord/nuvx, package names) were ignored, and files were mapped across the renamed layout (`app` → `app/android/mobile`, `shared` → `core/shared`, …).

## Verdict per platform

| Platform | Newer in BitChord? | Notes |
|---|---|---|
| Android phone | **Yes, ~18 features** (list below) | nuvx has heavily split big files (MainActivity 160k→5k chars, PlayerControls, SettingsSheet…), so ports must be done by hand, not by copying files. |
| Android TV | Small | TvTheme palettes (+2.5k), TvMotion, TvSongActionsDialog tweaks. The BitChord palette in `PersonalizationModels` is never called (dead code); skip it. TV spatial audio was removed by nuvx on purpose (`2f9b34d`). |
| Wear OS | No | nuvx's WearActivity/build are newer. |
| Desktop | No | The rebase zip has a KMP desktop prototype (196 files, BitChord 1.7.1). nuvx has a native Rust desktop app (405 files), so keep nuvx's. |
| iOS | n/a | Neither zip has an iOS app. |
| Web / extension | n/a | Neither zip has these. |
| Backend | Optional | BitChord ships a Go Listen Together server (`backend/`). nuvx only points at a server URL; only relevant if you want to self-host. |
| Rebase zip overall | No | Based on BitChord 1.7.1. Its unique files (USB direct audio, LocalMusicScreen, …) were dropped by BitChord itself by 1.9.2. |

## BitChord 1.9.2 features missing in nuvx (candidates)

Derived from new string keys + real code diffs; each needs a manual port into nuvx's current file layout.

1. Sleep timer fade-out (`SleepTimer.kt` + test)
2. Loudness target setting + "version cut" alignment/measuring in the audio pipeline
3. Advanced AutoMix toggle
4. Prefer lossless / lossless source labels
5. YouTube channel (brand account) switcher (`AccountChannelDialog`)
6. Listen Together activity feed (played/skipped/reordered…), profile, invite, custom-server save
7. Playlist picker search (`PlaylistFilter` + test)
8. Library grid/list toggle, total duration, song/album counts
9. Mood shelves (workout, focus, sleep, party…) + personalized recommendations toggle
10. Lyrics: hide-preview option, debug logs, LyricClock timing fixes (`LyricClockTest` +12k), KuGou/LrcLib/EmbeddedLyrics improvements
11. Option to disable Spotify Canvas; canvas repository changes
12. Undo dislike
13. Download notification channel/queued state, "converting to audio"
14. WebDAV server URL
15. Quick Settings play/pause tile (`widget/PlaybackTile.kt`)
16. YouTube thumbnail fallback for Coil (`data/YouTubeThumbnailFallback.kt`)
17. Dolby Atmos error messages
18. Player UI: AudioOutputSheet, LandscapePlayer, PlayerState additions

Skip: 20 alternate launcher icons (Liquid Glass/Neon/Pixel/Samsung) and the logo drawables, which are BitChord brand art.

## Licensing finding (independent of BitChord)

nuvx's comment-strip pass replaced full upstream headers with bare SPDX lines, dropping copyright/attribution notices, e.g.:
- `app/android/mobile/src/main/cpp/dsd/dst/decoder.c`: FFmpeg copyright (Peter Ross) + LGPL notice
- `.../cpp/bitperfect/exclusive_output.cpp`, `usb_transport.cpp`, `usb_stream.h`: Mornye attribution (zarzet)
- `.../cpp/jni/{analysis,mel,vocal,loopmix}_jni.cpp`: Orchard AGPL attribution
- `androidx/media3/decoder/ffmpeg/*.java`: Android Open Source Project (AOSP) Apache copyright headers

LGPL/Apache/AGPL require keeping those notices. Restoring them from BitChord's copies is a safe, comment-only fix. Feed this into the open licensing audit (handoff task 2).

## Not checked
- Did not build or run anything; this is a source audit.
- The 174 both-changed files were triaged by size and string keys, not read line by line.
