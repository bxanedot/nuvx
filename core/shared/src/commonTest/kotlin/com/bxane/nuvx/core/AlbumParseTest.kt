package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumParseTest {
    private fun runs(vararg t: String) = t.joinToString(",", "[", "]") { """{"text":"$it"}""" }
    private fun row(id: String, title: String, length: String) = """{"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":"$id"},
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":${runs(title)}}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{}}}],
        "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":${runs(length)}}}}]}}"""

    @Test fun playlistRowsKeepTheirOwnArtistAlbumAndArtwork() {
        val artistRun = """{"text":"Row Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}"""
        val albumRun = """{"text":"Row Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE1","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}"""
        val row = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"p1"},
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"row-art"}]}}},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":${runs("Placeholder Song")}}}},
                           {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[$artistRun]}}},
                           {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[$albumRun]}}}],
            "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":${runs("2:41")}}}}]}}"""
        val plain = row("p2", "No Album Song", "1:00")
        val body = """{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":${runs("Placeholder Mix")}},
            "subtitle":{"runs":${runs("Playlist", " • ", "2022")}},
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"cover"}]}}}}},"contents":[$row,$plain]}"""
        val page = YtmJson.parseAlbum("VLPLx", body)
        assertEquals(Track("p1", "Placeholder Song", listOf("Row Artist"), "Row Album", 161_000, "row-art"), page.tracks[0])
        assertEquals(null, page.tracks[1].album, "a playlist title is not an album name")
        assertEquals("cover", page.tracks[1].artworkUrl)
    }

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
