package com.bxane.nuvx.data.canvas

import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * nuvx's own community canvases, uploaded at canvas.nuvx.lol (the `canvas/` page, through the Listen Together
 * worker's `POST /canvas`). Each upload is `https://canvas.nuvx.lol/<videoId>.mp4`, and `index.json` maps
 * `"title|artist"` (from YouTube's oEmbed for that video) to the id, so another upload of the same song
 * (audio track vs. music video) finds it too. Keep [key] in step with `canvasKey` in worker/src/canvas.ts.
 */
object CommunityCanvas {

    private const val TAG = "CommunityCanvas"
    private const val ORIGIN = "https://canvas.nuvx.lol"
    private const val TTL_MS = 30L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var index: Map<String, String> = emptyMap()
    @Volatile private var fetchedAtMs = 0L

    /** [title] and [artist] are the track's own, uncleaned: the index is keyed on YouTube's spelling of them. */
    fun search(videoId: String, title: String, artist: String): CanvasArtwork? {
        val id = videoId.takeIf { it.isNotBlank() && exists(it) } ?: index()[key(title, artist)] ?: return null
        Log.d(TAG, "community canvas $id for '$title'")
        return CanvasArtwork(url = "$ORIGIN/$id.mp4", title = title, artist = artist)
    }

    /** Mirrors worker/src/canvas.ts canvasKey. */
    internal fun key(title: String, artist: String): String {
        fun clean(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase()
        return "${clean(title)}|${clean(artist).replace(Regex(" - topic$", RegexOption.IGNORE_CASE), "")}"
    }

    private fun exists(id: String): Boolean = runCatching {
        Http.client.newCall(Request.Builder().url("$ORIGIN/$id.mp4").head().build()).execute()
            .use { it.isSuccessful }
    }.getOrDefault(false)

    @Synchronized
    private fun index(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now - fetchedAtMs < TTL_MS) return index
        fetchedAtMs = now // a failed fetch keeps the last index rather than retrying on every track
        val body = canvasGet("$ORIGIN/index.json") ?: return index
        runCatching {
            json.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrNull()?.let { index = it }
        return index
    }
}
