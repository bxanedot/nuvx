package com.bxane.nuvx.data.canvas

import com.bxane.nuvx.data.DebugLog as Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

object TidalCanvas {

    private const val TAG = "TidalCanvas"
    private const val SEARCH = "https://api.tidal.com/v1/search"

    private const val EMBED_TOKEN = "vNVdglQOjFJJGG2U"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val countryCode: String by lazy {
        Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"
    }

    fun search(title: String, artist: String, album: String?): CanvasArtwork? {
        val query = if (album.isNullOrBlank()) "$artist $title" else "$album $artist $title"
        val url = SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", "10")
            .addQueryParameter("types", "TRACKS")
            .addQueryParameter("countryCode", countryCode)
            .build()
            .toString()

        val body = canvasGet(url, mapOf("X-Tidal-Token" to EMBED_TOKEN, "User-Agent" to CANVAS_UA))
            ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

        val items = root["tracks"]?.jsonObject?.get("items")?.jsonArray ?: return null

        for (item in items) {
            val track = item as? JsonObject ?: continue
            val trackTitle = track["title"]?.jsonPrimitive?.contentOrNull ?: continue

            val artists = track["artists"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()

            if (!isMatch(trackTitle, artists, title, artist)) continue

            val albumObj = track["album"]?.jsonObject
            val videoCover = albumObj?.get("videoCover")?.jsonPrimitive?.contentOrNull
            if (videoCover.isNullOrBlank()) continue
            val videoUrl = coverUrl(videoCover) ?: continue

            Log.d(TAG, "video cover for '$trackTitle' by ${artists.joinToString()}")
            return CanvasArtwork(
                url = videoUrl,
                title = trackTitle,
                artist = artists.joinToString(", ").ifBlank { null },
                album = albumObj["title"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return null
    }

    fun searchAlbum(album: String, artist: String): CanvasArtwork? {
        val url = SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("query", "$album $artist")
            .addQueryParameter("limit", "10")
            .addQueryParameter("types", "ALBUMS")
            .addQueryParameter("countryCode", countryCode)
            .build()
            .toString()

        val body = canvasGet(url, mapOf("X-Tidal-Token" to EMBED_TOKEN, "User-Agent" to CANVAS_UA))
            ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val items = root["albums"]?.jsonObject?.get("items")?.jsonArray ?: return null

        for (item in items) {
            val record = item as? JsonObject ?: continue
            val recordTitle = record["title"]?.jsonPrimitive?.contentOrNull ?: continue
            val artists = record["artists"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()

            if (!isMatch(recordTitle, artists, album, artist)) continue

            val videoCover = record["videoCover"]?.jsonPrimitive?.contentOrNull
            if (videoCover.isNullOrBlank()) continue
            val videoUrl = coverUrl(videoCover) ?: continue

            Log.d(TAG, "video cover for album '$recordTitle' by ${artists.joinToString()}")
            return CanvasArtwork(
                url = videoUrl,
                title = recordTitle,
                artist = artists.joinToString(", ").ifBlank { null },
                album = recordTitle,
            )
        }
        return null
    }

    private fun isMatch(
        gotName: String,
        gotArtists: List<String>,
        wantName: String,
        wantArtist: String,
    ): Boolean {
        if (gotName.normalizeForMatch() != wantName.normalizeForMatch()) return false
        val wanted = splitArtists(wantArtist)
        val credited = gotArtists.map { it.normalizeForMatch() }.filter { it.isNotBlank() }
        if (wanted.isEmpty() || credited.isEmpty()) return false
        return wanted.all { want -> credited.any { it == want } }
    }

    internal fun coverUrl(id: String): String? {
        val parts = id.split("-")
        if (parts.size != 5) return null
        return "https://resources.tidal.com/videos/${parts.joinToString("/")}/1280x1280.mp4"
    }
}
