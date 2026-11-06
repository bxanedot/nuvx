package com.bxane.nuvx

import com.bxane.nuvx.core.KeyValueStore
import com.bxane.nuvx.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.net.URI

internal data class PlaybackSnapshot(
    val queue: List<Track>,
    val index: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
)

internal class PlaybackRestore(
    private val store: KeyValueStore,
    scope: CoroutineScope,
    private val capture: () -> PlaybackSnapshot,
    restorePaused: (PlaybackSnapshot) -> Unit,
) : AutoCloseable {
    private val json = Json
    private val tracks = ListSerializer(Track.serializer())
    private var lastSaved = runCatching { store.get(STORAGE_KEY) }.getOrNull()
    private var closed = false

    init {
        val saved = decode(lastSaved)
        if (saved != null) restorePaused(saved.copy(playWhenReady = false))
        else if (lastSaved != null) write(null)
    }

    private val checkpoints = scope.launch {
        while (isActive) {
            delay(POSITION_CHECKPOINT_MS)
            checkpoint()
        }
    }

    fun checkpoint() {
        if (closed) return
        val encoded = try {
            capture().validated()?.let { snapshot ->
                buildJsonObject {
                    put("version", 1)
                    put("queue", json.encodeToJsonElement(tracks, snapshot.queue))
                    put("index", snapshot.index)
                    put("positionMs", snapshot.positionMs)
                    put("playWhenReady", snapshot.playWhenReady)
                }.toString().takeIf { it.length <= MAX_CHARS }
            }
        } catch (_: Exception) { return }
        write(encoded)
    }

    private fun decode(raw: String?): PlaybackSnapshot? {
        if (raw == null || raw.length > MAX_CHARS) return null
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val version = root.getValue("version").jsonPrimitive
            require(!version.isString && version.int == 1)
            val index = root.getValue("index").jsonPrimitive
            val position = root.getValue("positionMs").jsonPrimitive
            val intent = root.getValue("playWhenReady").jsonPrimitive
            require(!index.isString && !position.isString && !intent.isString)
            val queue = root.getValue("queue").jsonArray
            require(queue.size in 1..MAX_ITEMS)
            PlaybackSnapshot(json.decodeFromJsonElement(tracks, queue), index.int, position.long, intent.boolean).validated()
        } catch (_: Exception) { null }
    }

    private fun PlaybackSnapshot.validated(): PlaybackSnapshot? {
        if (queue.size !in 1..MAX_ITEMS || index !in queue.indices || positionMs < 0) return null
        if (queue.any { track ->
            track.videoId.length > 4_096 || track.title.length > 4_096 || track.artists.size > 100 ||
                track.artists.any { it.length > 4_096 } || (track.album?.length ?: 0) > 4_096 ||
                (track.artistId?.length ?: 0) > 512 || (track.albumId?.length ?: 0) > 512 ||
                (track.durationMs?.let { it < 0 } == true) ||
                (track.pairedVideoId?.let { !REMOTE_ID.matches(it) } == true) ||
                (if (track.isLocal) track.videoId.removePrefix("local:").isBlank() || !validLocalUri(track.localUri)
                else !REMOTE_ID.matches(track.videoId) || track.localUri != null)
        }) return null
        // Store no remote addresses, even artwork; local content/file references are durable identities.
        return copy(queue = queue.map { it.copy(artworkUrl = null) })
    }

    private fun validLocalUri(value: String?): Boolean = try {
        value != null && value.length <= 4_096 && URI(value).let {
            it.scheme in setOf("content", "file") && !it.path.isNullOrBlank() &&
                it.rawQuery == null && it.rawFragment == null && it.rawUserInfo == null &&
                (if (it.scheme == "content") !it.host.isNullOrBlank() else it.rawAuthority.isNullOrEmpty())
        }
    } catch (_: Exception) { false }

    private fun write(value: String?) {
        if (value == lastSaved) return
        try {
            store.put(STORAGE_KEY, value)
            lastSaved = value
        } catch (_: Exception) { /* A later checkpoint retries; storage failure must not stop playback. */ }
    }

    override fun close() {
        if (closed) return
        checkpoint()
        closed = true
        checkpoints.cancel()
    }

    internal companion object {
        const val STORAGE_KEY = "playback.queue.v1"
        const val POSITION_CHECKPOINT_MS = 5_000L
        private const val MAX_ITEMS = 1_000
        private const val MAX_CHARS = 1_048_576
        private val REMOTE_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
