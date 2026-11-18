package com.licht.nuvx.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Nuvx Connect: control nuvx on the paired computer, or hand this phone's song over to it. It only works through
 * the Local Wi-Fi Sync pairing (encrypted, replay-protected, revocable); there is no other way in. Blocking calls.
 */
object NuvxConnect {
    data class Remote(
        val title: String?,
        val artist: String?,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long?,
        val id: String? = null,
        val ended: Boolean = false,
    )

    private const val URL = "http://lan.invalid/v1/connect"
    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    val available: Boolean get() = NuvxSync.isLan && NuvxSync.status.value.enabled

    fun state(http: OkHttpClient? = NuvxSync.lanHttp()): Result<Remote> = runCatching {
        val client = http ?: throw IOException("Not paired with a computer")
        client.newCall(Request.Builder().url(URL).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            parse(r.body?.string().orEmpty())
        }
    }

    fun send(command: JsonObject, http: OkHttpClient? = NuvxSync.lanHttp()): Result<Unit> = runCatching {
        val client = http ?: throw IOException("Not paired with a computer")
        val body = command.toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(URL).post(body).build()).execute().use { r ->
            if (r.code != 202) throw IOException("HTTP ${r.code}")
        }
    }

    fun action(name: String) = buildJsonObject { put("action", name) }

    /** One song for [load]; null for anything that is not a YouTube song (a local file has nothing to hand over). */
    fun item(videoId: String, title: String, artist: String, art: String? = null): JsonObject? = if (!YOUTUBE_ID.matches(videoId)) null else buildJsonObject {
        put("videoId", videoId); put("title", title.take(300)); put("artist", artist.take(300))
        art?.takeIf { it.startsWith("https://") && it.length <= 300 }?.let { put("art", it) }
    }

    /** Plays [tracks] on the computer (the first from [positionMs]), or gets them ready there when [paused]. */
    fun load(tracks: List<JsonObject>, positionMs: Long, paused: Boolean) = buildJsonObject {
        put("action", "load"); put("positionMs", positionMs.coerceAtLeast(0)); put("paused", paused)
        put("tracks", kotlinx.serialization.json.JsonArray(tracks.take(20)))
    }

    fun seek(positionMs: Long) = buildJsonObject { put("action", "seek"); put("positionMs", positionMs.coerceAtLeast(0)) }

    /** Only YouTube songs can move to the computer; a local file or another source has nothing to hand over. */
    fun handOff(videoId: String, title: String, artist: String, positionMs: Long): JsonObject? =
        if (!YOUTUBE_ID.matches(videoId)) null else buildJsonObject {
            put("action", "playTrack"); put("videoId", videoId); put("title", title.take(300)); put("artist", artist.take(300))
            put("positionMs", positionMs.coerceAtLeast(0))
        }

    internal fun parse(text: String): Remote {
        val json = Json.parseToJsonElement(text).jsonObject
        val track = json["track"]?.takeIf { it is JsonObject }?.jsonObject
        return Remote(
            title = track?.get("title")?.jsonPrimitive?.contentOrNull,
            artist = track?.get("artist")?.jsonPrimitive?.contentOrNull,
            playing = json["playing"]?.jsonPrimitive?.booleanOrNull == true,
            positionMs = json["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            durationMs = track?.get("durationMs")?.jsonPrimitive?.longOrNull,
            id = track?.get("id")?.jsonPrimitive?.contentOrNull,
            ended = json["ended"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }
}
