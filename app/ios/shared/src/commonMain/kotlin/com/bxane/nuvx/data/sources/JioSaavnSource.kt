package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.http.Http
import com.bxane.nuvx.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

















class JioSaavnSource internal constructor(
    override val config: SourceConfig,
    private val transport: (suspend (String, Map<String, String>) -> String)?,
) : MusicSource, ConfigBacked {
    constructor(config: SourceConfig) : this(config, null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.kind.label








    override suspend fun health(): SourceHealth = runCatching { searchRows(HEALTH_QUERY, 1) }
        .fold(
            onSuccess = { SourceHealth.Ok("Reachable") },
            onFailure = { SourceHealth.Unreachable(it.message ?: "Unreachable") },
        )









    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val rows = runCatching { searchRows(trimmed, limit.coerceAtLeast(MIN_FETCH)) }
            .onFailure { DebugLog.w("JioSaavn search failed: ${it.message}") }
            .getOrNull() ?: return emptyList()
        return rows
            .sortedWith(
                compareByDescending<JioSaavnRow> { it.explicit }
                    .thenByDescending { it.durationSec ?: 0 }
            )
            .take(limit)
            .map { it.toSong(config.id) }
    }









    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val song = runCatching { fetchSong(trackId) }
            .onFailure { DebugLog.d("JioSaavn song failed: ${it.message}") }
            .getOrNull() ?: return null
        val cap = request.kbpsCeiling
        val bitrate = listOf(320, 160, 96, 48).firstOrNull { cap == null || it <= cap } ?: return null
        val encrypted = song.encryptedMediaUrl ?: return null
        val response = api("song.generateAuthToken", mapOf("url" to encrypted, "bitrate" to bitrate.toString()))
        val url = (response as? JsonObject)?.str("auth_url")
            ?.takeIf { it.startsWith("https://") } ?: return null
        return SourceStream(
            url = url,


            format = StreamFormat(codec = "aac", kbps = bitrate),
            belowRequest = false,
            durationSec = song.durationSec,
            sourceConfigId = config.id,
        )
    }



    private suspend fun api(call: String, args: Map<String, String>): JsonElement {
        val body = transport?.invoke(call, args) ?: Http.getText(
            url = API,
            query = mapOf("__call" to call, "_format" to "json", "_marker" to "0",
                "api_version" to "4", "ctx" to "android") + args,
            headers = mapOf("Accept" to "application/json", "User-Agent" to "Mozilla/5.0"),
            timeoutMillis = 12_000,
        )
        return json.parseToJsonElement(body)
    }

    private suspend fun searchRows(query: String, limit: Int): List<JioSaavnRow> =
        parseSongs(api("search.getResults", mapOf("q" to query, "n" to limit.toString(), "p" to "1")))

    private suspend fun fetchSong(trackId: String): JioSaavnRow? {
        val root = api("song.getDetails", mapOf("pids" to trackId)) as? JsonObject ?: return null
        val song = (root["songs"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: root.values.filterIsInstance<JsonObject>().firstOrNull()
            ?: return null
        return song.toRow()
    }









    private fun parseSongs(root: JsonElement): List<JioSaavnRow> {
        val elements = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["results"] as? JsonArray) ?: (root["data"] as? JsonArray) ?: emptyList()
            else -> emptyList()
        }
        return elements.mapNotNull { (it as? JsonObject)?.toRow() }
    }

    private fun JsonObject.toRow(): JioSaavnRow? {
        val id = str("id") ?: return null
        val name = str("title") ?: return null
        val info = get("more_info") as? JsonObject ?: return null
        val artists = ((info["artistMap"] as? JsonObject)?.get("primary_artists")).asStringList("name")
        val album = info.str("album")
        val year = str("year")?.toIntOrNull()
        val explicit = str("explicit_content") == "1"
        val durationSec = info.str("duration")?.toIntOrNull()
        val artwork = str("image")?.replace("150x150", "500x500")?.replace("http://", "https://")
        return JioSaavnRow(
            id = id,
            name = name,
            artist = artists.joinToString(", ").ifBlank { null },
            album = album,
            year = year,
            explicit = explicit,
            durationSec = durationSec,
            artworkUrl = artwork,
            encryptedMediaUrl = info.str("encrypted_media_url"),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    private fun JsonElement?.asStringList(key: String): List<String> =
        (this as? JsonArray)?.mapNotNull { entry ->
            when (entry) {
                is JsonObject -> entry.str(key)
                is JsonPrimitive -> entry.contentOrNull
                else -> null
            }
        }.orEmpty()

    private companion object {
        const val API = "https://www.jiosaavn.com/api.php"

        const val HEALTH_QUERY = "music"

        const val MIN_FETCH = 25
    }
}


internal data class JioSaavnRow(
    val id: String,
    val name: String,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val explicit: Boolean,
    val durationSec: Int?,
    val artworkUrl: String?,
    val encryptedMediaUrl: String? = null,
) {
    fun toSong(configId: String): Song = Song(


        videoId = SourceRegistry.trackKey(configId, id),
        title = name,
        artist = artist.orEmpty(),
        thumbnailUrl = artworkUrl,
        durationText = mmss(durationSec),
        albumName = album,


        sourceQuality = "AAC",
    )
}
