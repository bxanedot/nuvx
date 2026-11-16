# Desktop and iOS feature port (branch `claude/restore-license-headers`)

## Desktop (Rust/egui, `app/desktop`)

### Added (one commit each)
- **Loudness target**: Off or -20..-10 LUFS in Settings > Playback. Gain dB = clamp(target + 14 - loudnessDb, -15, +3). The old on/off setting migrates (on becomes -14). Test: `normalization_follows_the_target`.
- **Undo dislike**: the "Song disliked" toast has an Undo link that sends remove-like.
- **Add-to-playlist search**: search field in the dialog. Test: `playlist_search_ignores_case_and_blank_queries`.
- **Library grid/list toggle**: Playlists and Albums, persisted as `lib_list`.
- **YouTube artwork fallback**: a failed maxresdefault/sddefault/hq720 thumbnail retries as `hqdefault.jpg`. Test: `large_ytimg_thumbnails_fall_back_to_hqdefault`.
- **Sleep timer**: 15/30/45/60 min in Settings > Playback. It fades over the last 10 s, pauses, then restores volume. The fade is a separate gain, so the volume slider is not touched. Tests: `fades_only_in_the_last_seconds_then_finishes`, `cancel_stops_the_countdown`, `sleep_fade_scales_the_slider_gain`.
- **Listen Together activity**: server activity messages were only logged. They now show as a notice.

### Skipped
- **Lyrics clock**: desktop's `Clock` keeps no learned playback rate and the app has no playback-speed control, so there is nothing to reset.
- **Advanced AutoMix toggle**: desktop already has AutoMix vs plain crossfade.
- **Channel switcher**: already present.

### Verified
- `cargo check` and `cargo test` pass (213 passed, 27 ignored).
- Cloud setup: I installed the GTK/WebKit system libraries (the packages from the desktop-release workflow) to build.

### Not verified
- UI was not run or screenshotted: toast Undo, dropdowns, list view, sleep fade audio.
- Undo and the sleep tick are not unit tested because they need a full `App`.

## iOS (`core/apple`, `app/ios/shared`)

### Added
- **Playlist picker search**: `.searchable` filter in `PlaylistPickerView` (`ActionMenus.swift`).

### Skipped (already present or not usable)
- **Sleep timer**: already exists, with a fade.
- **Undo dislike**: the menu already toggles "Undo Dislike".
- **Library grid/list**: iOS already has a library grid view; the list variant was not added.
- **Loudness target**: iOS has Off/Track/Album modes, with gain applied in the native audio engine. I did not change that without being able to build or test it.
- **Artwork fallback**: not added (image loading is spread across views).
- **Quick Settings tile**: no iOS equivalent.
- **Lossless sources**: not applicable.

### Verified
- `app/ios/scripts/check-menus.sh` passes.

### Not verified
- No Xcode in the cloud, so the Swift change was not compiled.
- The KMP `commonMain` compile was not run (nothing changed there).
