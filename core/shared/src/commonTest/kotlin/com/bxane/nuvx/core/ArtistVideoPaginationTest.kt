package com.bxane.nuvx.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistVideoPaginationTest {
    @Test fun videoOnlyArtistIsNotReplacedByFallbackOrReportedEmpty() = runTest {
        val videos = ArtistVideoShelf("Music videos", ArtistVideoCategory.MUSIC_VIDEO,
            listOf(Track("abcdefghijk", "Test video", listOf("Test artist"), mediaKind = MediaKind.VIDEO)))
        val expected = Artist("UC1", "Test artist", null, emptyList(), emptyList(), listOf(videos))
        var fallbackCalls = 0
        fun backend(backendId: BackendId) = object : MusicBackend {
            override val id = backendId
            override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
            override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
            override suspend fun stream(videoId: String): AudioStream? = null
            override suspend fun artist(browseId: String): Artist = when (backendId) {
                BackendId.INNERTUBEX -> expected
                BackendId.EXCOR -> {
                    fallbackCalls++
                    expected.copy(topSongs = listOf(Track("lmnopqrstuv", "Fallback song", listOf("Test artist"))), videoShelves = emptyList())
                }
            }
        }
        val router = BackendRouter(BackendId.entries.associateWith(::backend))

        assertEquals(expected, router.artist("UC1"))
        assertEquals(0, fallbackCalls, "a populated video catalog is a successful artist result")
        assertTrue(router.health.value.getValue(BackendId.INNERTUBEX).ok)
    }

    @Test fun initialArtistPageCapturesContinuationPerVideoShelf() {
        val first = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Signal"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}}}"""
        val body = """{"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Nova"}]}}},"contents":[{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Music videos"}]}}},"contents":[$first],"continuations":[{"nextContinuationData":{"continuation":"page-1"}}]}}]}"""

        val shelf = YtmJson.parseArtist("UC1", body).videoShelves.single()
        assertEquals("page-1", shelf.continuation)
        assertEquals("UC1", shelf.continuationBrowseId)
    }

    @Test fun initialArtistPageCapturesTheVideoShelfMoreBrowseTarget() {
        val first = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Signal"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}}}"""
        val body = """{"contents":[{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Music videos"}]},"moreContentButton":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"browse-page","params":"shelf-params"}}}}}},"contents":[$first]}}]}"""

        val shelf = YtmJson.parseArtist("UC1", body).videoShelves.single()

        assertEquals("browse-page", shelf.moreBrowseId)
        assertEquals("shelf-params", shelf.moreBrowseParams)
    }

    @Test fun continuationPageRetainsShelfIdentityAndParsesVideosAndNextToken() {
        val video = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Signal (Official Music Video)"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}},"thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"thumb"}]}}},"thumbnailOverlay":{"musicThumbnailOverlayRenderer":{"content":{"musicThumbnailOverlayTimeStatusRenderer":{"text":{"runs":[{"text":"4:12"}]}}}}}}}"""
        val body = """{"continuationContents":{"musicCarouselShelfContinuation":{"contents":[$video],"continuations":[{"nextContinuationData":{"continuation":"page-2"}}]}}}"""

        val page = YtmJson.parseArtistVideoPage("UC1", "Nova", "Music videos", body)

        assertEquals("Music videos", page.title)
        assertEquals(ArtistVideoCategory.MUSIC_VIDEO, page.category)
        assertEquals(listOf("abcdefghijk"), page.items.map { it.videoId })
        assertEquals(listOf("Nova"), page.items.single().artists)
        assertEquals(252_000L, page.items.single().durationMs)
        assertEquals("page-2", page.continuation)
    }

    @Test fun moreBrowsePageParsesResponsiveVideoRows() {
        val row = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Signal Official Video"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Nova"}]}}}],"playlistItemData":{"videoId":"abcdefghijk"}}}"""
        val next = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}"""
        val body = """{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$row,$next]}},{"otherShelf":{"continuations":[{"nextContinuationData":{"continuation":"wrong-page"}}]}}]}"""

        val page = YtmJson.parseArtistVideoPage("UC1", "Nova", "Music videos", body, pageBrowseId = "browse-page")

        assertEquals(listOf("abcdefghijk"), page.items.map { it.videoId })
        assertEquals(listOf("Nova"), page.items.single().artists)
        assertEquals(MediaKind.VIDEO, page.items.single().mediaKind)
        assertEquals("next-page", page.continuation)
        assertEquals("browse-page", page.continuationBrowseId)
    }

    @Test fun moreBrowsePageScopesItemsAndContinuationToItsPlaylistShelf() {
        val row = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Signal Official Video"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Nova"}]}}}],"playlistItemData":{"videoId":"abcdefghijk"}}}"""
        val unrelatedRow = row.replace("abcdefghijk", "lmnopqrstuv").replace("Signal Official Video", "Unrelated video")
        val next = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}"""
        val unrelatedNext = next.replace("next-page", "wrong-page")
        val body = """{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$row,$next]}},{"musicPlaylistShelfRenderer":{"contents":[$unrelatedRow,$unrelatedNext]}}]}"""

        val page = YtmJson.parseArtistVideoPage("UC1", "Nova", "Music videos", body, pageBrowseId = "browse-page")

        assertEquals(listOf("abcdefghijk"), page.items.map { it.videoId })
        assertEquals("next-page", page.continuation)
    }

    @Test fun emptyContinuationPageReturnsNoVideosOrNextToken() {
        val page = YtmJson.parseArtistVideoPage(
            "UC1", "Nova", "Music videos",
            """{"continuationContents":{"musicCarouselShelfContinuation":{"contents":[]}}}""",
        )

        assertEquals(emptyList(), page.items)
        assertNull(page.continuation)
    }

    @Test fun mixedContinuationResponseKeepsCarouselAndPlaylistRows() {
        val carousel = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Carousel video"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"carousel001"}}}}"""
        val row = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Playlist video"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Nova"}]}}}],"playlistItemData":{"videoId":"playlist001"}}}"""
        val unrelated = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Unrelated shelf video"}]}}}],"playlistItemData":{"videoId":"unrelated01"}}}"""
        val body = """{"continuationContents":{"musicCarouselShelfContinuation":{"contents":[$carousel] }},"contents":[{"musicPlaylistShelfRenderer":{"contents":[$row]}},{"otherShelf":{"contents":[$unrelated]}}]}"""

        val page = YtmJson.parseArtistVideoPage("UC1", "Nova", "Music videos", body)

        assertEquals(listOf("carousel001", "playlist001"), page.items.map { it.videoId })
    }

    @Test fun artistVideoPageDoesNotReuseUnrelatedContinuationToken() {
        val page = YtmJson.parseArtistVideoPage(
            "UC1", "Nova", "Music videos",
            """{"continuationContents":{"musicCarouselShelfContinuation":{"contents":[]},"otherShelf":{"continuations":[{"nextContinuationData":{"continuation":"wrong-page"}}]}}}""",
        )

        assertNull(page.continuation)
    }

    @Test fun appendDeduplicatesVideosAndStopsConsumedOrRepeatedTokens() {
        val first = Track("abcdefghijk", "First", listOf("Nova"), mediaKind = MediaKind.VIDEO)
        val second = Track("lmnopqrstuv", "Second", listOf("Nova"), mediaKind = MediaKind.VIDEO)
        val current = ArtistVideoShelf("Music videos", ArtistVideoCategory.MUSIC_VIDEO, listOf(first), "page-1", "browse-page", "browse-params")
        val page = ArtistVideoShelf("Music videos", ArtistVideoCategory.MUSIC_VIDEO, listOf(first, second), "page-2")

        val appended = appendArtistVideoPage(current, page, setOf("page-1"))
        val repeated = appendArtistVideoPage(appended, page.copy(continuation = "page-1"), setOf("page-1", "page-2"))

        assertEquals(listOf("abcdefghijk", "lmnopqrstuv"), appended.items.map { it.videoId })
        assertEquals("page-2", appended.continuation)
        assertEquals(null, appended.continuationBrowseId)
        assertEquals(null, appended.moreBrowseId)
        assertEquals(null, appended.moreBrowseParams)
        assertEquals(null, repeated.continuation)
        assertEquals(listOf("abcdefghijk", "lmnopqrstuv"), repeated.items.map { it.videoId })
    }

    @Test fun artistVideoPagesAlwaysUsePrimaryCatalogBackend() = runTest {
        val expected = ArtistVideoShelf("Music videos", ArtistVideoCategory.MUSIC_VIDEO, emptyList(), null)
        val requestShelf = ArtistVideoShelf("Music videos", ArtistVideoCategory.MUSIC_VIDEO, emptyList(), moreBrowseId = "shelf-page", moreBrowseParams = "shelf-params")
        var primaryCalls = 0
        var requestArgs: Triple<String, String, ArtistVideoShelf>? = null
        val primary = object : MusicBackend {
            override val id = BackendId.INNERTUBEX
            override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
            override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
            override suspend fun stream(videoId: String): AudioStream? = null
            override suspend fun artistVideoPage(browseId: String, artistName: String, shelf: ArtistVideoShelf): ArtistVideoShelf {
                primaryCalls++
                requestArgs = Triple(browseId, artistName, shelf)
                return expected
            }
        }
        val alternative = object : MusicBackend {
            override val id = BackendId.EXCOR
            override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
            override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
            override suspend fun stream(videoId: String): AudioStream? = null
            override suspend fun artistVideoPage(browseId: String, artistName: String, shelf: ArtistVideoShelf): ArtistVideoShelf =
                error("Artist video pagination must use the primary catalog backend")
        }
        val router = BackendRouter(mapOf(primary.id to primary, alternative.id to alternative), selected = BackendId.EXCOR)

        assertEquals(expected, router.artistVideoPage("UC1", "Nova", requestShelf))
        assertEquals(1, primaryCalls)
        assertEquals(Triple("UC1", "Nova", requestShelf), requestArgs)
    }
}
