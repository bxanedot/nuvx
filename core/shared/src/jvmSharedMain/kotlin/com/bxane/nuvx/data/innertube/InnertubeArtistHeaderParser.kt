package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.SubscriptionState
import com.bxane.nuvx.data.model.WatchTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal object InnertubeArtistHeaderParser {
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
