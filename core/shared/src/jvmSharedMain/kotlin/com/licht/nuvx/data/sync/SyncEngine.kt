package com.licht.nuvx.data.sync

import com.licht.nuvx.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** A nuvx playlist as the app holds it. */
data class LibraryPlaylist(val id: String, val title: String, val songs: List<Song>)

/** Where the engine keeps its state between runs. [save] must replace the old state atomically. */
interface SyncStore {
    fun load(): String?
    fun save(state: String)
}

sealed interface SyncOutcome {
    /** Make the library exactly [playlists] (same order), unless the user changed it since this sync began. */
    data class Apply(val playlists: List<LibraryPlaylist>) : SyncOutcome
    data class RetryLater(val why: String) : SyncOutcome
    data class Stop(val why: String, val authorizationLost: Boolean = false) : SyncOutcome
}

@Serializable
internal data class TrackMeta(val title: String = "", val artist: String = "", val thumbnailUrl: String? = null)

@Serializable
internal data class SnapshotPlaylist(val id: String, val title: String, val tracks: List<String>)

@Serializable
internal data class StoredOp(val id: String, val kind: String, val entity: String, val payload: String, val clientMs: Long)

@Serializable
internal data class LoggedOp(val seq: Long, val kind: String, val entity: String, val payload: String)

@Serializable
internal data class EngineState(
    val lastSynced: List<SnapshotPlaylist> = emptyList(),
    val outbox: List<StoredOp> = emptyList(),
    val log: List<LoggedOp> = emptyList(),
    val cursor: Long = 0,
    val meta: Map<String, TrackMeta> = emptyMap(),
)

/**
 * Syncs nuvx playlists through a Nuvx Sync space, offline first:
 *
 * 1. Local changes since the last sync are found by comparing the library with the last synced snapshot, so
 *    every way a playlist can change (edits, undo, restore from backup) is caught, and saved to the outbox
 *    before anything is sent.
 * 2. The outbox is pushed in order. A failure keeps it for the next run, and nothing is applied locally, so an
 *    offline edit is never overwritten.
 * 3. New ops are pulled into the local log, the whole log is replayed ([replay]), and the result is returned for
 *    the app to apply. Every device replays the same log, so every device ends with the same playlists.
 *
 * Tracks are identified by video id; titles, artists and artwork travel in the add-track payload.
 */
class SyncEngine(
    private val client: SyncClient,
    private val store: SyncStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun syncOnce(library: List<LibraryPlaylist>): SyncOutcome {
        var state = load()
        val deleted = state.log.filter { it.kind == "playlist.delete" }.mapTo(HashSet()) { it.entity.removePrefix("playlist:") }
        val local = library.map { p -> if (p.id in deleted) p.copy(id = "${p.id}-${newId().take(8)}") else p }
        val ops = diff(state.lastSynced, local)
        state = state.copy(
            outbox = state.outbox + ops,
            lastSynced = local.map(::snapshot),
            meta = state.meta + local.flatMap { it.songs }.associate { it.videoId to TrackMeta(it.title, it.artist, it.thumbnailUrl) },
        )
        save(state)

        while (state.outbox.isNotEmpty()) {
            val pending = state.outbox.map { PendingOp(it.id, it.kind, it.entity, Json.parseToJsonElement(it.payload) as JsonObject, it.clientMs) }
            when (val r = client.push(pending)) {
                is SyncResult.Pushed -> {
                    if (r.ids.isEmpty()) return SyncOutcome.RetryLater("Nothing was accepted")
                    val sent = r.ids.toHashSet()
                    state = state.copy(outbox = state.outbox.filterNot { it.id in sent })
                    save(state)
                }
                is SyncResult.Stop -> return SyncOutcome.Stop(r.why, authorizationLost = r.authorizationLost)
                is SyncResult.RetryLater -> return SyncOutcome.RetryLater(r.why)
                else -> return SyncOutcome.RetryLater("Unexpected reply")
            }
        }

        while (true) {
            when (val r = client.pull(state.cursor)) {
                is SyncResult.Pulled -> {
                    val logged = r.ops.map { LoggedOp(it.seq, it.kind, it.entity, it.payload.toString()) }
                    val meta = r.ops.filter { it.kind == "playlist.addTrack" }.mapNotNull { op ->
                        val id = op.payload.str("videoId") ?: return@mapNotNull null
                        id to TrackMeta(op.payload.str("title").orEmpty(), op.payload.str("artist").orEmpty(), op.payload.str("thumbnailUrl"))
                    }
                    // Local metadata wins over a remote copy of the same track; remote fills in the rest.
                    state = state.copy(log = state.log + logged, cursor = r.cursor, meta = meta.toMap() + state.meta)
                    save(state)
                    if (!r.more) break
                }
                is SyncResult.Stop -> return SyncOutcome.Stop(r.why, authorizationLost = r.authorizationLost)
                is SyncResult.RetryLater -> return SyncOutcome.RetryLater(r.why)
                else -> return SyncOutcome.RetryLater("Unexpected reply")
            }
        }

        val replayed = replay(state.log.map { SyncOp(it.seq, it.kind, it.entity, Json.parseToJsonElement(it.payload) as JsonObject) })
        val result = replayed.playlists.values.map { p ->
            LibraryPlaylist(p.id, p.title, p.tracks.map { id ->
                val m = state.meta[id] ?: TrackMeta()
                Song(videoId = id, title = m.title, artist = m.artist, thumbnailUrl = m.thumbnailUrl)
            })
        }
        save(state.copy(lastSynced = result.map(::snapshot)))
        return SyncOutcome.Apply(result)
    }

    /** Forget everything this device synced (after leaving a space). Local playlists are untouched. */
    fun reset() = store.save(Json.encodeToString(EngineState.serializer(), EngineState()))

    /** Ops that turn [before] into [after], in the replay rules' terms; replaying them on [before] gives [after]. */
    internal fun diff(before: List<SnapshotPlaylist>, after: List<LibraryPlaylist>): List<StoredOp> {
        val ops = ArrayList<StoredOp>()
        fun op(kind: String, playlist: String, payload: JsonObject) =
            ops.add(StoredOp(newId(), kind, "playlist:$playlist", payload.toString(), now()))
        val old = before.associateBy { it.id }
        val keep = after.mapTo(HashSet()) { it.id }
        for (p in before) if (p.id !in keep) op("playlist.delete", p.id, JsonObject(emptyMap()))
        for (p in after) {
            val was = old[p.id]
            if (was == null) op("playlist.create", p.id, buildJsonObject { put("title", p.title) })
            else if (was.title != p.title) op("playlist.rename", p.id, buildJsonObject { put("title", p.title) })
            val target = p.songs.map { it.videoId }.distinct()
            val work = (was?.tracks ?: emptyList()).toMutableList()
            for (gone in work.filter { it !in target }) {
                work.remove(gone)
                op("playlist.removeTrack", p.id, buildJsonObject { put("videoId", gone) })
            }
            val songs = p.songs.associateBy { it.videoId }
            target.forEachIndexed { i, id ->
                val wantAfter = if (i == 0) null else target[i - 1]
                val at = work.indexOf(id)
                val nowAfter = if (at <= 0) null else work[at - 1]
                if (at >= 0 && nowAfter == wantAfter) return@forEachIndexed
                if (at >= 0) work.removeAt(at)
                work.add(if (wantAfter == null) 0 else work.indexOf(wantAfter) + 1, id)
                val song = songs.getValue(id)
                op(if (at >= 0) "playlist.moveTrack" else "playlist.addTrack", p.id, buildJsonObject {
                    put("videoId", id)
                    put("after", wantAfter?.let(::JsonPrimitive) ?: JsonNull)
                    if (at < 0) {
                        put("title", song.title)
                        put("artist", song.artist)
                        song.thumbnailUrl?.let { put("thumbnailUrl", it) }
                    }
                })
            }
        }
        return ops
    }

    private fun snapshot(p: LibraryPlaylist) = SnapshotPlaylist(p.id, p.title, p.songs.map { it.videoId }.distinct())
    private fun load(): EngineState = store.load()?.let {
        runCatching { Json.decodeFromString(EngineState.serializer(), it) }.getOrNull()
    } ?: EngineState()
    private fun save(state: EngineState) = store.save(Json.encodeToString(EngineState.serializer(), state))
    private fun JsonObject.str(key: String) = (this[key] as? JsonElement)?.jsonPrimitive?.contentOrNull
}
