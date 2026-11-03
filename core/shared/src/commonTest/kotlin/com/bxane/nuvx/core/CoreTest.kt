package com.bxane.nuvx.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtmJsonTest {
    private fun run(text: String, pageType: String? = null) =
        """{"text":"$text"${pageType?.let { ""","navigationEndpoint":{"browseEndpoint":{"browseId":"X","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$it"}}}}""" } ?: ""}}"""

    private fun row(title: String, meta: String, extra: String) = """{"musicResponsiveListItemRenderer":{$extra
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${run(title)}]}}},
                       {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[$meta]}}}],
        "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"small"},{"url":"big"}]}}}}}"""

    @Test fun parsesSongsAndCollections() {
        val song = row(
            "Around the World",
            listOf(run("Song"), run(" • "), run("Daft Punk", "MUSIC_PAGE_TYPE_ARTIST"), run(" • "), run("Homework", "MUSIC_PAGE_TYPE_ALBUM"), run(" • "), run("7:09")).joinToString(","),
            """"playlistItemData":{"videoId":"abc123"},""",
        )
        // An artist row whose menu carries a watchEndpoint must not become a track.
        val artist = row(
            "Daft Punk", run("Artist"),
            """"navigationEndpoint":{"browseEndpoint":{"browseId":"UC1","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}},"menu":{"watchEndpoint":{"videoId":"radio"}},""",
        )
        val r = YtmJson.parseSearch("""{"contents":[$song,$artist]}""")
        assertEquals(listOf(Track("abc123", "Around the World", listOf("Daft Punk"), "Homework", 429_000, "big", artistId = "X", albumId = "X")), r.tracks)
        assertEquals(listOf(Collection(Collection.Kind.ARTIST, "UC1", "Daft Punk", "Artist", "big")), r.collections)
    }

    @Test fun topResultCardSongsInheritTheCardArtist() {
        val song = row("Placeholder Song", listOf(run("Song"), run(" • "), run("5:52")).joinToString(","), """"playlistItemData":{"videoId":"c1"},""")
        val body = """{"contents":[{"musicCardShelfRenderer":{"title":{"runs":[{"text":"Test Band"}]},
            "subtitle":{"runs":[{"text":"Artist"},{"text":" • "},{"text":"1M monthly audience"}]},"contents":[$song]}}]}"""
        val track = YtmJson.parseSearch(body).tracks.single()
        assertEquals(listOf("Test Band"), track.artists)
        assertEquals(352_000L, track.durationMs)
    }

    @Test fun parsesRadioAndContinuation() {
        val body = """{"x":[{"playlistPanelVideoRenderer":{"videoId":"v1","title":{"runs":[{"text":"One"}]},
            "longBylineText":{"runs":[${run("Artist A", "MUSIC_PAGE_TYPE_ARTIST")}]},"lengthText":{"runs":[{"text":"3:05"}]}}}],
            "continuations":[{"nextRadioContinuationData":{"continuation":"CONT"}}]}"""
        val page = YtmJson.parseRadio(body)
        assertEquals(listOf(Track("v1", "One", listOf("Artist A"), null, 185_000, null, artistId = "X")), page.tracks)
        assertEquals("CONT", page.continuation)
    }

    @Test fun clock() {
        assertEquals(3_723_000, YtmJson.parseClock("1:02:03"))
        assertNull(YtmJson.parseClock("12 views"))
        assertNull(YtmJson.parseClock("3:"))
    }
}

class BackendRouterTest {
    private class Fake(override val id: BackendId, val fail: Boolean) : MusicBackend {
        var calls = 0
        override suspend fun search(query: String, filter: SearchFilter): SearchResults {
            calls++
            if (fail) error("down")
            return SearchResults(listOf(Track(id.name, query)), emptyList())
        }
        override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
        override suspend fun stream(videoId: String): AudioStream? = null
    }

    @Test fun selectionChangesTheEngineActuallyCalled() = runTest {
        val itx = Fake(BackendId.INNERTUBEX, false)
        val ex = Fake(BackendId.EXCOR, false)
        val router = BackendRouter(mapOf(itx.id to itx, ex.id to ex), fallback = false)
        assertEquals("INNERTUBEX", router.search("q").tracks.single().videoId)
        router.select(BackendId.EXCOR)
        assertEquals("EXCOR", router.search("q").tracks.single().videoId)
        assertEquals(1, itx.calls)
        assertEquals(1, ex.calls)
    }

    @Test fun fallbackOnlyWhenEnabled() = runTest {
        val itx = Fake(BackendId.INNERTUBEX, true)
        val ex = Fake(BackendId.EXCOR, false)
        val router = BackendRouter(mapOf(itx.id to itx, ex.id to ex), fallback = true)
        assertEquals("EXCOR", router.search("q").tracks.single().videoId)
        assertEquals(false, router.health.value[BackendId.INNERTUBEX]?.ok)
        router.setFallback(false)
        assertFailsWith<BackendFailure> { router.search("q") }
        assertTrue(router.stream("x") == null)
    }
}
