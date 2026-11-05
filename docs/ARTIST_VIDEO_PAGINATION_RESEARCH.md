# InnerTubeX artist-video pagination contract

Reviewed 2026-11-04 against the pinned InnerTubeX `v0.11.2` tag (commit `c863ddf15be01a1abfa4630329bdc0a1e95f1531`).

## Findings

- The README describes standard browse/search/account/playlist/player operations, but does not specify an artist-video shelf or its pagination contract. [Pinned README](https://github.com/MetrolistGroup/innertubex/blob/v0.11.2/README.md#L168-L178)
- The public `InnerTube.browse` method accepts optional `browseId`, `params`, and opaque `continuation` strings, and returns an `HttpResponse`. It passes the continuation back in the generic `BrowseBody`; it does not parse artist-video pages. [Pinned `InnerTube.browse`](https://github.com/MetrolistGroup/innertubex/blob/v0.11.2/src/commonMain/kotlin/com/metrolist/innertubex/InnerTube.kt#L640-L681)
- `BrowseResponse` models generic continuation containers (`sectionListContinuation`, `musicPlaylistShelfContinuation`, `gridContinuation`, `musicShelfContinuation`) as nullable raw `JsonObject`s. It defines no artist-video item/shelf type or artist-video continuation parser. [Pinned `BrowseResponse`](https://github.com/MetrolistGroup/innertubex/blob/v0.11.2/src/commonMain/kotlin/com/metrolist/innertubex/models/response/BrowseResponse.kt#L8-L34)
- `YouTubeDataPage<T>` is only a generic `items` plus optional `continuation` data class; the pinned source has no callers that bind it to artist-video browse responses. [Pinned `YouTubeDataPage`](https://github.com/MetrolistGroup/innertubex/blob/v0.11.2/src/commonMain/kotlin/com/metrolist/innertubex/models/YouTubeDataPage.kt#L6-L9)

## Conclusion

The pinned library supports generic browse and continuation transport, but does not provide typed artist-video shelf models. The current catalog response exposes a public `moreContentButton` browse target on artist video shelves. Its browse page uses responsive list rows under `musicPlaylistShelfRenderer`; the next token is in a `continuationItemRenderer` nested under that shelf. Nuvx reads these values from the response and sends them through the library's public `InnerTube.browse` method. Keep the renderer parser covered by fixtures and live checks because the upstream library does not promise this artist-video shape.

## Current worktree status — 2026-11-04

The artist page's “Load more videos” action now supports both an artist shelf continuation and the shelf's `moreContentButton` browse target. Browse-target results are parsed as responsive video rows; any following continuation is scoped to the page's `musicPlaylistShelfRenderer` and sent with that browse target's ID. The implementation uses the pinned InnerTubeX public `browse` API and adds no private endpoint, external library, or custom token flow.

## Continuation token scope hardening — 2026-11-04 08:41 +01:00

The parser now accepts a next-page token only from the parsed `musicCarouselShelfContinuation` node. A regression fixture with an unrelated sibling shelf token confirms that it is ignored. The test failed against the old root-wide fallback, then the full `:shared:desktopTest` suite passed (132 tests, 7 skipped). This prevents an unrelated token from being sent with the artist-video browse request; it does not validate the assumed continuation renderer shape. A documented contract or stable captured response is still needed before claiming pagination works against live artist pages.

## Live catalog probe — 2026-11-04 10:51 +01:00

The opt-in `LiveArtistVideoPaginationTest` queried five public artist results (Daft Punk, Taylor Swift, Billie Eilish, The Weeknd, and BTS) through InnerTubeX. The current parser returned 10 video shelves with items and zero shelves with a continuation. The probe therefore skipped the continuation request; it did not pass a live pagination assertion. `ArtistVideoPaginationTest` then passed all 6 deterministic parser/router fixtures. This is evidence that the current catalog sample exposes no continuation recognized by the current parser, not proof that the raw responses contain no “see all” browse action. This status was superseded by the 11:20 live browse-and-continue verification below: the shelf browse path returned 100 + 16 current live results.

## Live browse-and-continue verification — 2026-11-04 11:20 +01:00

The opt-in test inspected the public browse response for the first candidate artist (Daft Punk). It found two populated artist-video shelves with more-content browse targets. The selected browse target returned 100 responsive rows and a continuation item nested under `musicPlaylistShelfRenderer`; the Nuvx parser and actual `InnerTubeXBackend.artistVideoPage` route each returned 100 videos. Sending the shelf-scoped continuation through the same browse target returned 16 more videos with no further page. Across five artist results, 10 video shelves had items and none exposed an initial continuation; the shelf browse action is the path currently exercised.

`LiveArtistVideoPaginationTest` passed (1 test, 0 skipped/failures). `ArtistVideoPaginationTest` passed all 8 deterministic cases. The full verification run passed 138 shared tests (9 skipped, 0 failed), desktop app tests, and desktop/mobile/TV/Wear Kotlin compilation. This validates the current public response on this run, not a stable upstream guarantee or playable video streams. The test logs counts and renderer-key paths only; it never logs browse IDs, tokens, media URLs, or response bodies.

## Contract revalidated after playlist-shelf isolation — 2026-11-04 15:13 +01:00

The opt-in `LiveArtistVideoPaginationTest` (`NUVX_LIVE=1`) passed against current public catalog responses using the pinned InnerTubeX `browse` API. The observed browse-target response has 100 responsive rows and a `continuationItemRenderer` at `contents.singleColumnBrowseResultsRenderer.tabs.tabRenderer.content.sectionListRenderer.contents.musicPlaylistShelfRenderer.contents`; the shelf-scoped continuation returned 16 additional videos and no further page. The test reports counts and structural key paths only; it does not log browse identifiers, continuation values, media URLs, or response bodies. This documents the currently observed contract, not a stable upstream guarantee. The parser now scopes both rows and continuation to the first populated playlist shelf; deterministic tests cover a second unrelated playlist shelf.
