# Music video and artist video experience

## User goal

Add music video playback and artist video discovery to the existing nuvx application. Music videos must use the existing playback queue and session, work with the collapsed player, and appear as real playable content rather than decorative thumbnails. Artist pages should show only video sections for which the catalog returns content. Artist background motion should be muted, lifecycle-aware, and never compete with the active song.

## Constraints

- Preserve the existing dirty canvas and mini-player work.
- Keep InnerTubeX as the only verified video extraction engine. Do not route video extraction through eXcor unless its video capability is independently verified.
- Use InnerTubeX's published Kotlin API and validate media URLs before playback. Its 0.x protocol integration is experimental and may stop working when upstream behavior changes.
- Do not use undocumented provider endpoints or credential flows. Provider-specific animated cover APIs and video download rights remain unverified; they are not prerequisites for music video playback.
- Do not add a second audio session. A song/video change replaces only the current queue entry's playback mode while retaining queue order, repeat, shuffle, and history identity.
- Do not advertise unimplemented platform capabilities. There is currently no iOS application target in the checked-out tree.
- Use nuvx's existing icon set and glass controls.

## Architecture

1. `Track` keeps its stable queue/history identity and gains a media kind plus an optional paired video ID. `playbackVideoId` selects the source currently represented by the queue item. An artist video has its own identity; a paired music video keeps the song's original identity and references the video source.
2. Add a video-capable backend contract. `InnerTubeXBackend` requests `ContentHints(wantVideo = true)` and maps the extractor's audio/video URLs, headers, codecs, dimensions, and available heights into a redacted `VideoStream`. `BackendRouter` validates both HTTPS media URLs and uses InnerTubeX directly for video; an eXcor selection does not imply video support.
3. Add conservative song/video matching. Require a normalized title match, a credited-artist match, a duration within tolerance, and matching version qualifiers. Reject live/remix/acoustic mismatches. A match is a suggestion for explicit user switching, never an automatic replacement.
4. Parse video-filter search results and watch endpoints in artist catalog shelves into video queue items. Preserve the source shelf title and map only clear categories; never label a result official based only on its title.
5. Android uses the existing MediaSession/Media3 player and merges the extractor's separate audio and video sources. The signed URLs stay in process memory and are fetched on Media3 loading threads. Attach a PlayerView only while the expanded player is visible.
6. Desktop uses the existing VLC playback instance, with separate audio/video sources joined by VLC's documented input-slave support and one embedded video surface. Keep audio-only mode unchanged.
7. Artist motion headers, quality selection, PiP, live-specific controls, full video catalog pagination, and other native integrations follow as separate implementation stages after the playback/data path is proven. Downloads remain blocked until an authorized source permits file downloads.

## UI behavior

- Search's Videos filter opens the selected result as a video queue item.
- Artist pages render populated, categorized video shelves beneath the existing music sections. Empty categories are omitted. Selecting a card starts it through the same queue/player.
- On a song, the expanded player can offer “Watch video” only after the conservative resolver finds a candidate. On a paired video, “Audio” switches back. Switching preserves queue index and current position; if the source recording duration differs materially, the UI restarts at zero rather than seeking into the wrong segment.
- The expanded player displays the platform video surface when available and keeps existing lyrics, transport, and queue controls. Reduced motion and lifecycle rules continue to govern animated layers.
- The collapsed player retains the current queue item, title, artwork/thumbnail, and progress. No background artist video is decoded in this first playback stage.

## Acceptance for the first implementation stage

- Tests cover video URL validation, eXcor non-fallback, media identity and metadata round trips, title/artist/version/duration matching, search marking, and artist video shelf parsing/category handling.
- Android and desktop builds compile with the existing supported dependencies; platform playback surfaces are integrated into the shared player UI.
- No video resolution, codec, PiP, download, official-channel, or iOS support is claimed unless the specific platform path is verified.
- Existing shared, Android, desktop, worker, and canvas tests remain intact and pass where runnable.

## Deferred acceptance

The full user specification additionally asks for artist background loops, PiP, quality persistence, live video, captions and video-specific lyric offsets, video radio, download-manager integration, mobile/TV/Wear behavior, iOS app integration, platform screenshots, and physical-device playback. These remain explicit work items; completion of the first stage does not satisfy the full definition of done. Download controls stay absent while no authorized full-video download flow is available.
