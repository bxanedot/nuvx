package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtistParseTest {
    private fun runs(vararg t: String) = t.joinToString(",", "[", "]") { """{"text":"$it"}""" }
    private fun browse(id: String, type: String) =
        """{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$type"}}}}"""
    private fun card(id: String, type: String, title: String, sub: String) = """{"musicTwoRowItemRenderer":{
        "title":{"runs":${runs(title)}},"subtitle":{"runs":${runs(sub)}},"navigationEndpoint":${browse(id, type)},
        "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"art-$id"}]}}}}}"""
    private fun shelf(title: String, vararg cards: String) =
        """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":${runs(title)}}}},"contents":[${cards.joinToString(",")}]}}"""

    @Test fun headerTopSongsAndBrowsableShelves() {
        val song = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"s1"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":${runs("Placeholder Hit")}}}}]}}"""
        val video = """{"musicTwoRowItemRenderer":{"title":{"runs":${runs("A Video")}},"navigationEndpoint":{"watchEndpoint":{"videoId":"v1"}}}}"""
        val body = """{"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":${runs("Test Band")}},
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"portrait"}]}}}}},
            "contents":[{"musicShelfRenderer":{"contents":[$song]}},
              ${shelf("Albums", card("MPRE1", "MUSIC_PAGE_TYPE_ALBUM", "First Record", "Album • 2001"))},
              ${shelf("Videos", video)},
              ${shelf("Fans might also like", card("UC2", "MUSIC_PAGE_TYPE_ARTIST", "Other Band", "1M monthly"))}]}"""
        val artist = YtmJson.parseArtist("UC1", body)
        assertEquals("Test Band", artist.name)
        assertEquals("portrait", artist.artworkUrl)
        assertEquals(listOf("s1"), artist.topSongs.map { it.videoId })
        assertEquals(listOf("Albums", "Fans might also like"), artist.shelves.map { it.title }, "video-only shelves are skipped")
        assertEquals(Collection(Collection.Kind.ALBUM, "MPRE1", "First Record", "Album • 2001", "art-MPRE1"), artist.shelves[0].items.single())
        assertEquals(Collection.Kind.ARTIST, artist.shelves[1].items.single().kind)
    }
}
