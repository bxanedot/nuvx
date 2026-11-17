package com.licht.nuvx.data.sources

import com.licht.nuvx.data.model.Song
import kotlinx.coroutines.CancellationException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI

class RadioJavanSource internal constructor(
    override val config: SourceConfig,
    private val transport: (suspend (String, Map<String, String>) -> String)?,
) : MusicSource, SourceRegistry.ConfigBacked {
    constructor(config: SourceConfig) : this(config, null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.RADIOJAVAN
    override val displayName: String get() = config.kind.label

    override suspend fun health(): SourceHealth = try {
        searchRows("music")
        SourceHealth.Ok("Reachable")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SourceHealth.Unreachable(e::class.simpleName ?: "Unavailable")
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
        } catch (_: Exception) {
            emptyList()
        }
        return rows
            .take(limit)
            .map { row ->
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
        if (request == StreamRequest.Lossless) return null
        val row = try {
            parseRows(api("mp3", mapOf("id" to trackId))).firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        val maxKbps = (request as? StreamRequest.Capped)?.maxKbps
        val media = listOfNotNull(row.hqLink, row.lqLink)
            .distinct()
            .mapNotNull { raw -> httpsUrl(raw)?.let { url -> url to bitrate(url) } }
            .sortedByDescending { it.second ?: 0 }
            .firstOrNull { (_, kbps) -> maxKbps == null || kbps?.let { it <= maxKbps } == true }
            ?: return null
        val extension = runCatching { URI(media.first).path.substringAfterLast('.').lowercase() }.getOrDefault("")
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
        val body = transport?.invoke(path, query) ?: run {
            val response = client.get("$API/$path") {
                query.forEach { (key, value) -> parameter(key, value) }
                headers.append("x-rj-user-agent", CLIENT_AGENT)
                headers.append("User-Agent", CLIENT_AGENT)
                headers.append("Accept", "application/json")
            }
            if (response.status != HttpStatusCode.OK) error("Radio Javan HTTP ${response.status.value}")
            response.bodyAsText()
        }
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
        return RadioJavanRow(
            id = id,
            title = title,
            artist = string("artist"),
            album = string("album"),
            photo = string("photo"),
            durationSec = string("duration")?.let(::seconds),
            hqLink = string("hq_link"),
            lqLink = string("lq_link"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun httpsUrl(raw: String?): String? = raw?.takeIf {
        runCatching { URI(it).let { uri -> uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null } }
            .getOrDefault(false)
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
        val client by lazy {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 12_000
                    connectTimeoutMillis = 6_000
                    socketTimeoutMillis = 12_000
                }
                expectSuccess = false
            }
        }
    }
}
