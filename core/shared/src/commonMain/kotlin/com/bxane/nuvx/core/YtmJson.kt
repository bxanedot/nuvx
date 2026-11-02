package com.bxane.nuvx.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads YouTube Music InnerTube responses (WEB_REMIX) into nuvx models. Unknown shapes are skipped, never guessed. */
object YtmJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseSearch(body: String): SearchResults {
        val root = json.parseToJsonElement(body)
        val tracks = mutableListOf<Track>()
        val collections = mutableListOf<Collection>()
        for (item in root.findAll("musicResponsiveListItemRenderer")) {
            val columns = item.arr("flexColumns").map { it.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text") }
            val title = columns.getOrNull(0).runs().firstOrNull()?.str("text") ?: continue
            val meta = columns.getOrNull(1).runs()
            val art = item.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail").bestThumb()
            val browse = item.obj("navigationEndpoint")?.obj("browseEndpoint")
            // Only the row's own play button counts; menu entries (artist radio, shuffle) also carry watchEndpoints.
            val videoId = item.obj("playlistItemData")?.str("videoId")
                ?: item.obj("overlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")
                    ?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")?.obj("watchEndpoint")?.str("videoId")
            if (browse == null && videoId != null) {
                tracks += Track(videoId, title, meta.artists(), meta.album(), meta.durationMs(), art)
            } else if (browse != null) {
                val kind = when (browse.pageType()) {
                    "MUSIC_PAGE_TYPE_ALBUM" -> Collection.Kind.ALBUM
                    "MUSIC_PAGE_TYPE_ARTIST" -> Collection.Kind.ARTIST
                    "MUSIC_PAGE_TYPE_PLAYLIST" -> Collection.Kind.PLAYLIST
                    else -> continue
                }
                val id = browse.str("browseId") ?: continue
                collections += Collection(kind, id, title, meta.joinToString("") { it.str("text").orEmpty() }.ifBlank { null }, art)
            }
        }
        return SearchResults(tracks.distinctBy { it.videoId }, collections.distinctBy { it.browseId })
    }

    fun parseRadio(body: String): RadioPage {
        val root = json.parseToJsonElement(body)
        val tracks = root.findAll("playlistPanelVideoRenderer").mapNotNull { r ->
            val id = r.str("videoId") ?: return@mapNotNull null
            val byline = r.obj("longBylineText").runs()
            Track(
                videoId = id,
                title = r.obj("title").runs().firstOrNull()?.str("text") ?: return@mapNotNull null,
                artists = byline.artists(),
                album = byline.album(),
                durationMs = r.obj("lengthText").runs().firstOrNull()?.str("text")?.let(::parseClock),
                artworkUrl = r.obj("thumbnail").bestThumb(),
            )
        }
        val continuation = (root.findAll("nextRadioContinuationData") + root.findAll("nextContinuationData"))
            .firstNotNullOfOrNull { it.str("continuation") }
        return RadioPage(tracks.distinctBy { it.videoId }, continuation)
    }

    /** "3:45" or "1:02:03" to milliseconds; null for anything else. */
    fun parseClock(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3 || parts.any { p -> p.isEmpty() || p.any { !it.isDigit() } }) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() } * 1000
    }

    private fun List<JsonObject>.artists() =
        filter { it.obj("navigationEndpoint")?.obj("browseEndpoint")?.pageType() in ARTIST_PAGES }.mapNotNull { it.str("text") }

    private fun List<JsonObject>.album() =
        firstOrNull { it.obj("navigationEndpoint")?.obj("browseEndpoint")?.pageType() == "MUSIC_PAGE_TYPE_ALBUM" }?.str("text")

    private fun List<JsonObject>.durationMs() = firstNotNullOfOrNull { it.str("text")?.let(::parseClock) }

    private val ARTIST_PAGES = setOf("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL")

    private fun JsonObject.pageType() = obj("browseEndpointContextSupportedConfigs")
        ?.obj("browseEndpointContextMusicConfig")?.str("pageType")

    private fun JsonObject?.runs(): List<JsonObject> = this?.arr("runs")?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject?.bestThumb(): String? = this?.arr("thumbnails")?.lastOrNull()?.let { it as? JsonObject }?.str("url")

    private fun JsonElement.obj(key: String) = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement.arr(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()
    private fun JsonElement.str(key: String) = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.findAll(key: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> if (k == key && v is JsonObject) out += v else walk(v) }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return out
    }
}
