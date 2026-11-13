package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.ArtistPage
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.SubscriptionState
import com.bxane.nuvx.data.model.WatchTarget
import com.bxane.nuvx.data.model.shelfLayoutFor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal object InnertubeArtistHeaderParser {
    fun parseArtistPage(response: JsonObject): ArtistPage {
        val sections = response.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            .orEmpty()

        val songs = mutableListOf<Song>()
        var moreSongs: String? = null
        val shelves = mutableListOf<HomeShelf>()
        val header = response["header"]
        val credit = InnertubeParser.Credits(artistName = artistName(header))

        sections.forEach { section ->
            section.o("musicShelfRenderer")?.let { shelf ->
                shelf.a("contents").orEmpty().forEach { row ->
                    InnertubeParser.parseResponsiveListItem(row.o("musicResponsiveListItemRenderer"), credit)
                        ?.let(songs::add)
                }
                if (moreSongs == null) {
                    moreSongs = shelf.o("title").a("runs")?.firstOrNull()
                        .o("navigationEndpoint").o("browseEndpoint").s("browseId")
                }
            }
            section.o("musicCarouselShelfRenderer")?.let { carousel ->
                val header = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
                val title = header.o("title").runs()
                val items = carousel.a("contents").orEmpty().mapNotNull {
                    InnertubeParser.parseTwoRowItem(it.o("musicTwoRowItemRenderer"))
                }.filter { it.browseId != null || it.isVideo }
                val moreEndpoint = header.o("title").a("runs")?.firstNotNullOfOrNull {
                    it.o("navigationEndpoint").o("browseEndpoint")
                }
                    ?: header.o("moreContentButton").o("buttonRenderer")
                        .o("navigationEndpoint").o("browseEndpoint")
                    ?: header.o("moreContentButton").o("musicMoreContentButtonRenderer")
                        .o("navigationEndpoint").o("browseEndpoint")
                    ?: header.a("endIcons")?.firstNotNullOfOrNull {
                        it.o("musicNavigationButtonRenderer").o("clickCommand").o("browseEndpoint")
                            ?: it.o("musicNavigationButtonRenderer").o("navigationEndpoint").o("browseEndpoint")
                    }
                    ?: header.o("navigationEndpoint").o("browseEndpoint")
                val moreBrowseId = moreEndpoint.s("browseId")
                val moreParams = moreEndpoint.s("params")
                if (title.isNotBlank() && items.isNotEmpty()) {
                    shelves += HomeShelf(
                        title = title,
                        items = items,
                        moreBrowseId = moreBrowseId,
                        moreParams = moreParams,
                        layout = shelfLayoutFor(title, items, fromList = false),
                    )
                }
            }
        }
        return ArtistPage(
            songs, moreSongs, shelves,
            thumbnailUrl = artistThumbnail(header),
            name = credit.artistName,
            description = InnertubeParser.parseDescription(response),
            subscriberCountText = subscriberCount(header),
            monthlyListenerCount = monthlyListeners(header),
            subscription = subscription(header),
            radio = headerWatchTarget(header, "startRadioButton"),
            shuffle = headerWatchTarget(header, "playButton"),
        )
    }

    fun headerWatchTarget(header: JsonElement?, button: String): WatchTarget? {
        val endpoint = header.o("musicImmersiveHeaderRenderer").o(button)
            .o("buttonRenderer").o("navigationEndpoint").o("watchEndpoint")
            ?: return null
        val playlistId = endpoint.s("playlistId") ?: return null
        return WatchTarget(
            playlistId = playlistId,
            videoId = endpoint.s("videoId"),
            params = endpoint.s("params")?.let { java.net.URLDecoder.decode(it, "UTF-8") },
        )
    }

    fun subscription(header: JsonElement?): SubscriptionState? {
        val immersive = header.o("musicImmersiveHeaderRenderer") ?: return null
        return listOfNotNull(
            immersive.o("subscriptionButton2").o("subscribeButtonRenderer"),
            immersive.o("subscriptionButton").o("subscribeButtonRenderer"),
        ).firstNotNullOfOrNull { button ->
            val subscribed = (button["subscribed"] as? JsonPrimitive)?.booleanOrNull
                ?: return@firstNotNullOfOrNull null
            val channelId = button.s("channelId")
                ?: button.a("serviceEndpoints")?.firstNotNullOfOrNull { endpoint ->
                    (endpoint.o("subscribeEndpoint").a("channelIds")?.firstOrNull()
                        as? JsonPrimitive)?.contentOrNull
                }
                ?: return@firstNotNullOfOrNull null
            SubscriptionState(channelId = channelId, subscribed = subscribed)
        }
    }

    fun subscriberCount(header: JsonElement?): String? {
        val immersive = header.o("musicImmersiveHeaderRenderer") ?: return null
        val button2 = immersive.o("subscriptionButton2").o("subscribeButtonRenderer")
        val button1 = immersive.o("subscriptionButton").o("subscribeButtonRenderer")
        return button2.o("subscriberCountWithSubscribeText").firstRunText()
            ?: button1.o("longSubscriberCountText").firstRunText()
            ?: button1.o("shortSubscriberCountText").firstRunText()
    }

    fun monthlyListeners(header: JsonElement?): String? =
        header.o("musicImmersiveHeaderRenderer").o("monthlyListenerCount").firstRunText()

    fun artistName(header: JsonElement?): String? {
        val renderer = header.o("musicImmersiveHeaderRenderer")
            ?: header.o("musicVisualHeaderRenderer")
            ?: return null
        return renderer.o("title").runs().takeIf { it.isNotBlank() }
    }

    fun artistThumbnail(header: JsonElement?): String? {
        if (header == null) return null
        val immersive = header.o("musicImmersiveHeaderRenderer")
        val visual = header.o("musicVisualHeaderRenderer")
        val renderer = (
            immersive.o("thumbnail")
                ?: visual.o("foregroundThumbnail")
                ?: visual.o("thumbnail")
            ).o("musicThumbnailRenderer")
            ?: InnertubeParser.collectRenderers(header, "musicThumbnailRenderer").firstOrNull()
        return renderer.o("thumbnail").a("thumbnails").best()
    }
}
