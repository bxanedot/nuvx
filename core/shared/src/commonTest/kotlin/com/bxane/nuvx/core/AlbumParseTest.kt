package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumParseTest {
    private fun runs(vararg t: String) = t.joinToString(",", "[", "]") { """{"text":"$it"}""" }
    private fun row(id: String, title: String, length: String) = """{"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":"$id"},
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":${runs(title)}}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{}}}],
        "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":${runs(length)}}}}]}}"""

    @Test fun headerFillsArtistArtworkAndAlbum() {
        val body = """{"header":{"musicResponsiveHeaderRenderer":{
            "title":{"runs":${runs("Placeholder Album")}},"straplineTextOne":{"runs":${runs("Test Band")}},
            "subtitle":{"runs":${runs("Album", " • ", "2001")}},
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"s"},{"url":"cover"}]}}}}},
            "contents":[${row("t1", "First", "2:17")},${row("t2", "Second", "3:05")},${row("t1", "First", "2:17")}]}"""
        val album = YtmJson.parseAlbum("MPREb_x", body)
        assertEquals("Placeholder Album", album.title)
        assertEquals("Test Band", album.artist)
        assertEquals("2001", album.year)
        assertEquals("cover", album.artworkUrl)
        assertEquals(
            listOf(
                Track("t1", "First", listOf("Test Band"), "Placeholder Album", 137_000, "cover"),
                Track("t2", "Second", listOf("Test Band"), "Placeholder Album", 185_000, "cover"),
            ),
            album.tracks,
        )
    }
}
