package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.http.Http
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class RadioJavanSource internal constructor(
    override val config: SourceConfig,
    private val transport: (suspend (String, Map<String, String>) -> String)?,
) : MusicSource, ConfigBacked {
    constructor(config: SourceConfig) : this(config, null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.kind.label

    override suspend fun health(): SourceHealth = try {
        searchRows("music")
        SourceHealth.Ok("Reachable")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SourceHealth.Unreachable(e.message ?: "Unavailable")
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || limit <= 0) return emptyList()
        val rows = try {
            searchRows(trimmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w("Radio Javan search failed: ${e::class.simpleName}")
            emptyList()
        }
        return rows.take(limit).map { row ->
            Song(
                videoId = SourceRegistry.trackKey(config.id, row.id),
                title = row.title,
                artist = row.artist.orEmpty(),
                albumName = row.album,
                thumbnailUrl = httpsUrl(row.photo),
                durationText = row.durationSec?.let(::mmss),
                sourceQuality = "HIGH",
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        if (request is StreamRequest.Lossless) return null
        val row = try {
            parseRows(api("mp3", mapOf("id" to trackId))).firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d("Radio Javan track lookup failed: ${e::class.simpleName}")
            null
        } ?: return null
        val cap = (request as? StreamRequest.Capped)?.capKbps
        val media = listOfNotNull(row.hqLink, row.lqLink)
            .distinct()
            .mapNotNull { raw -> httpsUrl(raw)?.let { url -> url to bitrate(url) } }
            .sortedByDescending { it.second ?: 0 }
            .firstOrNull { (_, kbps) -> cap == null || (kbps != null && kbps <= cap) }
            ?: return null
        val extension = media.first.substringAfterLast('.').substringBefore('?').lowercase()
        return SourceStream(
            url = media.first,
            format = StreamFormat(codec = if (extension == "m4a") "aac" else extension.ifBlank { null }, kbps = media.second),
            durationSec = row.durationSec,
            sourceConfigId = config.id,
        )
    }

    private suspend fun searchRows(query: String): List<RadioJavanRow> =
        parseRows(api("search", mapOf("query" to query)))

    private suspend fun api(path: String, query: Map<String, String>): JsonElement {
        val body = transport?.invoke(path, query) ?: Http.getText(
            url = "$API/$path",
            query = query,
            headers = mapOf("x-rj-user-agent" to CLIENT_AGENT, "User-Agent" to CLIENT_AGENT, "Accept" to "application/json"),
            timeoutMillis = 12_000,
        )
        return json.parseToJsonElement(body)
    }

    private fun parseRows(root: JsonElement): List<RadioJavanRow> {
        val rows = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["mp3s"] as? JsonArray)
                ?: (root["results"] as? JsonArray)
                ?: (root["data"] as? JsonArray)
                ?: listOfNotNull((root["mp3"] as? JsonObject) ?: root.takeIf { it.string("id") != null })
            else -> emptyList()
        }
        return rows.mapNotNull { (it as? JsonObject)?.toRow() }
    }

    private fun JsonObject.toRow(): RadioJavanRow? {
        val id = string("id") ?: return null
        val title = string("song") ?: string("title") ?: return null
        return RadioJavanRow(id, title, string("artist"), string("album"), string("photo"),
            string("duration")?.let(::seconds), string("hq_link"), string("lq_link"))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun httpsUrl(raw: String?): String? {
        if (raw == null || !raw.startsWith("https://", ignoreCase = true)) return null
        val authority = raw.substringAfter("://", "").substringBefore('/').substringBefore('?')
        return raw.takeIf { authority.isNotBlank() && '@' !in authority }
    }

    private fun bitrate(url: String): Int? =
        Regex("(?:aac|mp3)-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun seconds(raw: String): Int? = raw.toDoubleOrNull()?.toInt() ?: raw.split(':').takeIf { it.size == 2 }
        ?.let { (minutes, seconds) -> minutes.toIntOrNull()?.times(60)?.plus(seconds.toIntOrNull() ?: return null) }

    private fun mmss(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    private data class RadioJavanRow(
        val id: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val photo: String?,
        val durationSec: Int?,
        val hqLink: String?,
        val lqLink: String?,
    )

    private companion object {
        const val API = "https://rj-deskcloud.com/api2"
        const val CLIENT_AGENT = "Radio Javan/5.0.0 (Desktop) com.radioJavan.rj.desktop"
    }
}
