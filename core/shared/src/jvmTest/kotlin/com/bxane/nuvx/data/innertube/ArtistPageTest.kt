package com.bxane.nuvx.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtistPageTest {
    @Test
    fun parsesArtistHeaderAndSongShelf() {
        val response = Json.parseToJsonElement(
            """
            {
              "header": {"musicImmersiveHeaderRenderer": {
                "title": {"runs": [{"text": "Example Artist"}]},
                "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://image"}]}}},
                "monthlyListenerCount": {"runs": [{"text": "2.4M monthly listeners"}]},
                "subscriptionButton2": {"subscribeButtonRenderer": {
                  "subscribed": true,
                  "channelId": "UC-artist",
                  "subscriberCountWithSubscribeText": {"runs": [{"text": "900K subscribers"}]}
                }},
                "startRadioButton": {"buttonRenderer": {"navigationEndpoint": {"watchEndpoint": {"playlistId": "RDAMVMradio"}}}},
                "playButton": {"buttonRenderer": {"navigationEndpoint": {"watchEndpoint": {"playlistId": "OLAK5uy-shuffle", "videoId": "song1", "params": "shuffle"}}}}
              }},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
                {"musicShelfRenderer": {
                  "contents": [{"musicResponsiveListItemRenderer": {
                    "playlistItemData": {"videoId": "song1"},
                    "flexColumns": [
                      {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track One"}]}}},
                      {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Example Artist"}]}}}
                    ]
                  }}],
                  "title": {"runs": [{"text": "Songs", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPLY-more"}}}]}
                }}
              ]}}}}]}}
            }
            """.trimIndent(),
        ).jsonObject

        val page = InnertubeParser.parseArtistPage(response)

        assertEquals("Example Artist", page.name)
        assertEquals("https://image", page.thumbnailUrl)
        assertEquals("2.4M monthly listeners", page.monthlyListenerCount)
        assertEquals("900K subscribers", page.subscriberCountText)
        assertEquals("UC-artist", page.subscription?.channelId)
        assertEquals(true, page.subscription?.subscribed)
        assertEquals("RDAMVMradio", page.radio?.playlistId)
        assertEquals("OLAK5uy-shuffle", page.shuffle?.playlistId)
        assertEquals("MPLY-more", page.moreSongsBrowseId)
        assertEquals(listOf("Track One"), page.songs.map { it.title })
    }
}
