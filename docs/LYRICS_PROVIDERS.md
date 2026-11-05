# Lyrics providers

All enabled clients and parsers live in `core/shared/src/commonMain`, so the same sources work on Android, desktop, and iOS. The app requests lyrics for the active track only; it does not crawl pages, retry rate-limited requests, send local-track IDs to lyrics services, or log lyric bodies. Fixtures contain fictional placeholder text.

## Status

| Provider | Status | Use and limits |
|---|---|---|
| [lrc.red](https://lrc.red/api) | Enabled; default | Public search → ISRC → TTML API. Requests identify nuvx and include its site and contact email in User-Agent. The player links the displayed source credit to lrc.red. No automatic retries or bulk requests. |
| [LRCLIB](https://lrclib.net/docs) | Enabled | Public API; identify nuvx with a User-Agent. Requests are sequential and failures/rate limits are not retried by the provider chain. Honor `Retry-After`; do not bulk-scan libraries. |
| [Better Lyrics API](https://docs.betterlyrics.org/) | Enabled | Documented TTML API. Song, artist, album, and duration are sent to improve matching and cache use. Cached results need no key; uncached requests may require a key that is not currently issued. The client observes HTTP failures and does not retry 429 responses. Credit links to the Better Lyrics project. |
| [Unison](https://docs.betterlyrics.org/unison) | Enabled | Public read API; no key or sign-in; 120 requests/minute/IP. Queries use the current YouTube video ID. Unsupported formats and non-success responses are ignored; no automatic retries. |
| BiniLyrics | Enabled via lrc.red | The lrc.red project identifies itself as the successor to BiniLyrics. It is one service integration, not a separate endpoint. |
| [Musixmatch](https://developer.musixmatch.com/) | Needs key and approved license | Disabled stub only. No key or lyrics request is included. Enable only after nuvx has an approved developer credential and display/cache rights for the intended platforms. |
| [Genius](https://docs.genius.com/) | Not supported | The documented API is for song/artist/annotation data; no permitted full-lyrics endpoint was verified. No page scraping. See [Genius terms](https://genius.com/static/terms). |
| [KuGou](https://open.kugou.com/) | Not supported directly | The official open platform documents licensed music/player integrations, but no public third-party lyrics API was verified. Its player SDK is a separate paid/usage-based product with platform and privacy requirements; it is not a KMP lyrics endpoint. |
| [Megalobiz](https://www.megalobiz.com/) | Not supported | No official public lyrics API and terms for third-party clients were verified. A website scraper is not used. |
| [LyricsPlus / YouLyPlus](https://github.com/ibratabian17/lyricsplus) | Not supported | Public endpoint notes exist, but no first-party third-party-use terms were verified. No rotating mirrors or private endpoints are called. |
| [SimpMusic](https://www.simpmusic.org/docs/guide/introduction) | Not supported | Its product documentation lists its own lyrics sources, but a public third-party lyrics API and use terms were not verified. |
| [PaxSenix](https://api.paxsenix.org/) | Not supported | Its API description says some endpoints depend on scraping and compatibility reverse engineering. That does not meet this project's documented-API-only requirement. |

“Enabled” means the client uses a documented public endpoint; it does not claim that every lyric is licensed for every distribution or territory. Keep source attribution visible where required and obtain any separate content licenses needed before release.

The default order is lrc.red, LRCLIB, Better Lyrics, then Unison. In the player lyrics view, users can select any enabled provider. The selected provider is tried first, and the displayed credit names the source that actually returned the lyrics. If it has no result, the remaining providers are fallbacks. With word timing preferred, a later word-synced result can replace an earlier fallback; a line-synced result replaces plain text when no selected provider already supplied the result. With word timing disabled, the first synchronized result wins over earlier plain text.

Successful results are cached in memory per provider and track request for up to six hours, with a 128-entry limit. Concurrent lookups for the same provider/request share one request. Misses and failures are not cached, so a temporary provider outage or an early lookup without lyrics does not suppress a later retry. The cache is session-only and never persists lyric text to disk.

## Timing and matching

`Ttml.parse` accepts only absolute clock times (`HH:MM:SS.f` or `M:SS.f`) and source-provided line/span times. It rejects external entities, malformed timing, and documents over 1 MiB. Plain LRC and enhanced LRC use the existing `Lrc.parse`. No word timestamp is synthesized from a line timestamp.

The player exposes a user timing adjustment from −30s to +30s in 100ms increments. It is stored per playback media ID, so a paired music-video version can use its own correction. Line and source-provided word timestamps shift together; tapping a lyric seeks to the adjusted line time. The control never creates word-level timing.

The normal suite uses only invented placeholder lyrics. `LiveLyricsProvidersTest` runs only when `NUVX_LIVE=1`; it checks a result and its shape without printing lyric content. Normal tests never contact a lyrics service.
