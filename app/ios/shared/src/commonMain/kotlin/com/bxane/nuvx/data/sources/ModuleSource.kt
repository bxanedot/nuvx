package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.http.Http
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
























class ModuleSource(override val config: SourceConfig) : MusicSource, ConfigBacked {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var index: List<ModuleEntry> = emptyList()

    @Volatile
    private var indexFetchedAt: TimeMark? = null

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.displayName










    private suspend fun entries(): List<ModuleEntry> {
        val url = SourceRegistry.baseUrlOf(config)
        if (url.isBlank()) return emptyList()
        val fetchedAt = indexFetchedAt
        if (index.isNotEmpty() && fetchedAt != null && fetchedAt.elapsedNow() < INDEX_TTL) {
            return index
        }
        val body = runCatching { Http.getText(url, timeoutMillis = 15_000) }
            .onFailure { DebugLog.w("module index unreachable: ${it.message}") }
            .getOrNull() ?: return index
        val parsed = parseIndex(body)
        if (parsed.isNotEmpty()) {
            index = parsed
            indexFetchedAt = TimeSource.Monotonic.markNow()
        }
        return index
    }









    override suspend fun health(): SourceHealth {
        val url = SourceRegistry.baseUrlOf(config)
        if (url.isBlank()) return SourceHealth.Rejected("No module index configured")
        val found = runCatching { entries() }
            .getOrElse { failure ->
                return SourceHealth.Unreachable(failure.message ?: "Index unreachable")
            }
        if (found.isEmpty()) return SourceHealth.Unreachable("Index listed no modules")
        val usable = found.count { it.download.isNotBlank() }
        if (usable == 0) return SourceHealth.Rejected("No module here ships an executable script")
        return SourceHealth.Ok("${found.size} module(s) · $usable executable")
    }














    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val found = entries()
        if (found.isEmpty()) return emptyList()
        val results = coroutineScope {
            found
                .filter { it.download.isNotBlank() }
                .map { entry ->
                    async {
                        runCatching { ModuleEngine.search(entry, query, request?.tier) }
                            .onFailure { if (it !is CancellationException) {
                                DebugLog.d("${entry.name}: search failed (${it.message})")
                            } }
                            .getOrDefault(emptyList())
                    }
                }
                .awaitAll()
        }
        val rows = results.flatten()
        if (rows.isEmpty()) return emptyList()
        return rows
            .sortedByDescending { it.rank(request) }
            .take(limit)
            .map { it.toSong(config.id) }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {


        val (ownerId, moduleTrackId) = SourceRegistry.parseTrackKey(trackId)
            ?: return null
        if (ownerId != config.id) return null
        val entry = entries().firstOrNull { it.id in moduleTrackId || moduleTrackId.startsWith(it.id) }
            ?: return null
        val answer = runCatching { ModuleEngine.stream(entry, moduleTrackId, request.tier) }
            .onFailure { DebugLog.d("${entry.name}: stream failed (${it.message})") }
            .getOrNull() ?: return null
        if (answer.url.isBlank()) return null
        return SourceStream(
            url = answer.url,
            format = answer.toFormat(),
            headers = answer.headers,
            belowRequest = answer.belowRequest,
            durationSec = answer.durationSec,
            sourceConfigId = config.id,
        )
    }


    fun release() {
        index = emptyList()
        indexFetchedAt = null
        ModuleEngine.forget(config.id)
    }






    private fun parseIndex(body: String): List<ModuleEntry> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val arrays = mutableListOf<JsonArray>()
        when (root) {
            is JsonArray -> arrays += root
            is JsonObject -> {
                root.values.filterIsInstance<JsonArray>().forEach { arrays += it }
                root.values.filterIsInstance<JsonObject>().forEach { nested ->
                    nested.values.filterIsInstance<JsonArray>().forEach { arrays += it }
                }
            }
            else -> Unit
        }
        return arrays.asSequence()
            .flatMap { it.asSequence() }
            .filterIsInstance<JsonObject>()
            .mapNotNull { obj ->
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val tags = (obj["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty() +
                    (obj["labels"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                ModuleEntry(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                    author = obj["author"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    version = obj["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    download = obj["download"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    lossless = tags.any {
                        it.contains("LOSSLESS", true) || it.contains("FLAC", true) ||
                            it.contains("HI-RES", true)
                    },
                )
            }
            .distinctBy { it.id }
            .toList()
    }

    private companion object {
        val INDEX_TTL = 30.minutes
    }
}


internal data class ModuleEntry(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val download: String,
    val lossless: Boolean,
)


@Serializable
internal data class ModuleRow(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val durationSec: Int? = null,
    val artworkUrl: String? = null,
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val explicit: Boolean = false,
    val lossless: Boolean = false,
) {
    fun rank(request: StreamRequest?): Int {
        val losslessRow = lossless || codec?.lowercase() in LOSSY_FREE
        return when {
            request is StreamRequest.Lossless -> if (losslessRow) 10_000 else kbps ?: 0
            losslessRow && request !is StreamRequest.Capped -> 10_000 + (kbps ?: 0)
            else -> kbps ?: 0
        }
    }

    fun toSong(configId: String): Song = Song(
        videoId = SourceRegistry.trackKey(configId, id),
        title = title,
        artist = artist,
        thumbnailUrl = artworkUrl,
        durationText = mmss(durationSec),
        albumName = album,
        sourceQuality = codec?.uppercase() ?: if (lossless) "LOSSLESS" else null,
    )

    private companion object {
        val LOSSY_FREE = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff")
    }
}


@Serializable
internal data class ModuleStream(
    val url: String = "",
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val belowRequest: Boolean = false,
    val durationSec: Int? = null,
) {
    fun toFormat(): StreamFormat = StreamFormat(
        codec = codec,
        kbps = kbps,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
    )
}
