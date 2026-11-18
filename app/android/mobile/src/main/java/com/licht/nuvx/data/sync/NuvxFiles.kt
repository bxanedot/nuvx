package com.licht.nuvx.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream

/**
 * Copies downloaded songs from the paired computer, over the Local Wi-Fi Sync channel only: encrypted, and only
 * between your own approved devices. Blocking calls.
 */
object NuvxFiles {
    data class Remote(
        val id: String,
        val title: String,
        val artist: String,
        val art: String?,
        val durationMs: Long?,
        val size: Long,
        val ext: String,
    )

    private const val BASE = "http://lan.invalid/v1/files"
    private val EXTENSIONS = setOf("m4a", "webm", "opus", "mp3", "flac", "ogg")

    fun list(http: OkHttpClient? = NuvxSync.lanHttp()): Result<List<Remote>> = runCatching {
        val client = http ?: throw IOException("Not paired with a computer")
        client.newCall(Request.Builder().url(BASE).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            parseList(r.body?.string().orEmpty())
        }
    }

    /** Writes the whole file into [out], piece by piece; fails if any piece is missing or the size is off. */
    fun fetch(file: Remote, out: OutputStream, onProgress: (Long) -> Unit = {}, http: OkHttpClient? = NuvxSync.lanHttp()) {
        val client = http ?: throw IOException("Not paired with a computer")
        var offset = 0L
        while (offset < file.size) {
            val piece = client.newCall(Request.Builder().url("$BASE/${file.id}?offset=$offset").get().build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            }
            if (piece["offset"]?.jsonPrimitive?.longOrNull != offset || piece["size"]?.jsonPrimitive?.longOrNull != file.size) {
                throw IOException("The file changed on the computer")
            }
            val bytes = LanSyncCrypto.unb64(piece["data"]?.jsonPrimitive?.contentOrNull.orEmpty())
                ?.takeIf { it.isNotEmpty() } ?: throw IOException("Empty piece")
            out.write(bytes)
            offset += bytes.size
            onProgress(offset)
        }
        if (offset != file.size) throw IOException("Size mismatch")
    }

    internal fun parseList(text: String): List<Remote> =
        Json.parseToJsonElement(text).jsonObject["files"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { YOUTUBE_ID.matches(it) } ?: return@mapNotNull null
            val size = o["size"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..MAX_FILE_BYTES } ?: return@mapNotNull null
            val ext = o["ext"]?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { it in EXTENSIONS } ?: return@mapNotNull null
            Remote(
                id = id,
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty().take(300),
                artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty().take(300),
                art = o["art"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") && it.length <= 500 },
                durationMs = o["durationMs"]?.jsonPrimitive?.longOrNull,
                size = size,
                ext = ext,
            )
        }

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
}
