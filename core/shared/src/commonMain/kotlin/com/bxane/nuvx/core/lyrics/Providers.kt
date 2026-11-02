package com.bxane.nuvx.core.lyrics

import com.bxane.nuvx.core.Lrc
import com.bxane.nuvx.core.Lyrics
import com.bxane.nuvx.core.LyricsProvider
import com.bxane.nuvx.core.Track
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val MAX_RESPONSE_LENGTH = 1_048_576
private const val USER_AGENT = "nuvx (https://nuvx.lol; bxanee@gmail.com)"
private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
private val isrcPattern = Regex("[A-Za-z0-9]{12}")

/** Public Unison read API. One lookup is keyed to the exact YouTube video, not scraped page data. */
class Unison(private val http: HttpClient) : LyricsProvider {
    override val name = "Unison"
    override val homepage = "https://unison.boidu.dev"

    override suspend fun find(track: Track, offsetMs: Long): Lyrics? {
        if (!videoIdPattern.matches(track.videoId)) return null
        val response = http.get("https://unison.boidu.dev/lyrics") { parameter("v", track.videoId) }
        if (!response.status.isSuccess()) return null
        val body = response.bodyAsText().takeIf { it.length <= MAX_RESPONSE_LENGTH } ?: return null
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val data = root["data"] as? JsonObject ?: return null
        val source = (data["lyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        return when ((data["format"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
            "ttml" -> Ttml.parse(source, offsetMs)
            "lrc" -> Lrc.parse(source, offsetMs)
            "plain" -> Lyrics.Plain(source)
            else -> null
        }
    }
}

/** Uses lrc.red's documented search → ISRC → TTML flow and required attribution identity. */
class LrcRed(private val http: HttpClient) : LyricsProvider {
    override val name = "lrc.red"
    override val homepage = "https://lrc.red"

    override suspend fun find(track: Track, offsetMs: Long): Lyrics? {
        val query = (listOf(track.title) + track.artists).filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) return null
        val search = http.get("https://lrc.red/search.json") {
            header(HttpHeaders.UserAgent, USER_AGENT)
            parameter("q", query)
        }
        if (!search.status.isSuccess()) return null
        val body = search.bodyAsText().takeIf { it.length <= MAX_RESPONSE_LENGTH } ?: return null
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val isrc = (root["hits"] as? JsonArray)?.firstNotNullOfOrNull { hit ->
            ((hit as? JsonObject)?.get("isrc") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { isrcPattern.matches(it) }
        } ?: return null
        val lyricResponse = http.get("https://lrc.red/s/$isrc.ttml") { header(HttpHeaders.UserAgent, USER_AGENT) }
        if (!lyricResponse.status.isSuccess()) return null
        return lyricResponse.bodyAsText().takeIf { it.length <= MAX_RESPONSE_LENGTH }?.let { Ttml.parse(it, offsetMs) }
    }
}

/** Better Lyrics' documented TTML API. Full match metadata is required to avoid its avoidable cache misses. */
class BetterLyrics(private val http: HttpClient) : LyricsProvider {
    override val name = "Better Lyrics"
    override val homepage = "https://better-lyrics.boidu.dev"

    override suspend fun find(track: Track, offsetMs: Long): Lyrics? {
        val artist = track.artists.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
        val album = track.album?.takeIf(String::isNotBlank) ?: return null
        val durationSeconds = track.durationMs?.takeIf { it > 0 }?.div(1000) ?: return null
        val response = http.get("https://api.betterlyrics.org/getLyrics") {
            header(HttpHeaders.UserAgent, "nuvx (https://nuvx.lol)")
            parameter("s", track.title)
            parameter("a", artist)
            parameter("al", album)
            parameter("d", durationSeconds)
        }
        if (!response.status.isSuccess()) return null
        val body = response.bodyAsText().takeIf { it.length <= MAX_RESPONSE_LENGTH } ?: return null
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val ttml = (root["ttml"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        return Ttml.parse(ttml, offsetMs)
    }
}

/** Not registered until nuvx has an approved lyrics license and its official API credentials. */
internal object MusixmatchDisabled : LyricsProvider {
    override val name = "Musixmatch"
    override val homepage = "https://developer.musixmatch.com/"
    override suspend fun find(track: Track, offsetMs: Long): Lyrics? = null
}
