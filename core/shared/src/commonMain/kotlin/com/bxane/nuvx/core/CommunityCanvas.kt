package com.bxane.nuvx.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * Community canvases: looping cover videos uploaded at canvas.nuvx.lol (paste the song link, drop the MP4) into the repo's
 * canvas/ folder as `<YouTube video id>.mp4`. Exact video IDs are preferred, with a normalized title/artist fallback.
 */
class CommunityCanvas(
    private val http: HttpClient = nuvxHttpClient(),
    private val folder: String = FOLDER,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val known = mutableMapOf<String, String>()
    private val notFoundAt = mutableMapOf<String, Long>()
    private var sharedIndex: Map<String, String>? = null
    private var sharedIndexCheckedAt: Long? = null

    /** The canvas URL for [videoId], or null when there is none or it can't be checked right now. */
    suspend fun find(videoId: String): String? {
        if (!VIDEO_ID.matches(videoId)) return null
        known[videoId]?.let { return it }
        val now = nowMillis()
        notFoundAt[videoId]?.let { checkedAt ->
            if (now >= checkedAt && now - checkedAt < NOT_FOUND_TTL_MS) return null
            notFoundAt.remove(videoId)
        }
        val url = "$folder$videoId.mp4"
        val status = try { http.head(url).status } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        return when (status) {
            HttpStatusCode.OK -> url.also { known[videoId] = it }
            HttpStatusCode.NotFound -> null.also { notFoundAt[videoId] = nowMillis() }
            else -> null
        }
    }

    /** Exact video match first, then the uploader's title and primary artist index. */
    suspend fun find(track: Track): String? {
        find(track.videoId)?.let { return it }
        val artist = track.artists.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val title = track.title.takeIf { it.isNotBlank() } ?: return null
        if (VERSIONED_TITLE.containsMatchIn(title)) return null
        val cached = sharedIndex
        val now = nowMillis()
        val fresh = cached?.takeIf {
            sharedIndexCheckedAt?.let { checkedAt -> now >= checkedAt && now - checkedAt < SHARED_INDEX_TTL_MS } == true
        }
        val refreshed = if (fresh != null) null else try {
            val response = http.get("${folder}index.json")
            sharedIndexCheckedAt = nowMillis()
            if (response.status != HttpStatusCode.OK) null else {
                Json.parseToJsonElement(response.body<String>()).jsonObject.mapNotNull { (key, value) ->
                    runCatching { key to value.jsonPrimitive.content }.getOrNull()
                }.toMap().also { sharedIndex = it }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            sharedIndexCheckedAt = nowMillis()
            null
        }
        val index = fresh ?: refreshed ?: cached ?: return null
        val id = index[canvasKey(title, artist)]?.takeIf { VIDEO_ID.matches(it) && it != track.videoId } ?: return null
        return find(id)
    }

    private fun canvasKey(title: String, artist: String): String {
        fun clean(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase()
        val normalizedArtist = clean(artist).replace(Regex(" - topic$", RegexOption.IGNORE_CASE), "")
        return "${clean(title)}|$normalizedArtist"
    }

    companion object {
        // The repo's canvas/ folder straight from GitHub (public repo, HTTPS, byte ranges); canvas.nuvx.lol is the upload page.
        const val FOLDER = "https://raw.githubusercontent.com/bxanedot/nuvx/main/canvas/"
        private const val NOT_FOUND_TTL_MS = 10 * 60 * 1000L
        private const val SHARED_INDEX_TTL_MS = 10 * 60 * 1000L
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val VERSIONED_TITLE = Regex(
            """\bremix\b|(?:[\(\[\-–—]\s*live\b|\blive\s+(?:at|in|from|version|recording|performance)\b|\blive\s*[\]\)]$|\blive$)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
