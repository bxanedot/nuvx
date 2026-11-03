package com.bxane.nuvx.core

import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

/**
 * Community canvases: looping cover videos uploaded at canvas.nuvx.lol (paste the song link, drop the MP4) into the repo's
 * canvas/ folder as `<YouTube video id>.mp4`. Exact ids, so there is no fuzzy name matching.
 */
class CommunityCanvas(private val http: HttpClient = nuvxHttpClient(), private val folder: String = FOLDER) {
    // Only definite answers (found / not there) are remembered; a network error is asked again next time.
    private val known = mutableMapOf<String, String?>()

    /** The canvas URL for [videoId], or null when there is none or it can't be checked right now. */
    suspend fun find(videoId: String): String? {
        if (!VIDEO_ID.matches(videoId)) return null
        if (videoId in known) return known[videoId]
        val url = "$folder$videoId.mp4"
        val status = try { http.head(url).status } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        return when (status) {
            HttpStatusCode.OK -> url.also { known[videoId] = it }
            HttpStatusCode.NotFound -> null.also { known[videoId] = null }
            else -> null
        }
    }

    companion object {
        // The repo's canvas/ folder straight from GitHub (public repo, HTTPS, byte ranges); canvas.nuvx.lol is the upload page.
        const val FOLDER = "https://raw.githubusercontent.com/bxanedot/nuvx/main/canvas/"
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
