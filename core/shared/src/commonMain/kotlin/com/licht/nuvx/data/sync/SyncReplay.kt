package com.licht.nuvx.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One operation from the Nuvx Sync log, in the server's order (`seq`). Matches `core/sync` and `SYNC_AND_CONNECT.md`. */
data class SyncOp(val seq: Long, val kind: String, val entity: String, val payload: JsonObject)

data class SyncedPlaylist(val id: String, val title: String, val tracks: List<String>)

data class SyncState(
    val playlists: Map<String, SyncedPlaylist> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val settings: Map<String, JsonElement> = emptyMap(),
)

/**
 * Replays the log into state. Every client applies the same ops in the same server order, so every client
 * ends in the same state, however the edits were made offline:
 *
 * - a track is placed after an anchor track, not at an index, so a concurrent edit elsewhere in the list does
 *   not shift it. `after` absent appends, `after: null` puts it first, and an anchor that is gone appends;
 * - adding a track already present does nothing (no duplicates); removing or moving a missing track does nothing;
 * - for titles, favorites and settings, the later op in server order wins;
 * - ops on a deleted playlist are ignored, so a stale offline edit cannot bring it back;
 * - unknown kinds are skipped, so an older client never corrupts state written by a newer one.
 *
 * Restoring an earlier version of a playlist is replaying the log up to that `seq`.
 */
fun replay(ops: List<SyncOp>, upTo: Long = Long.MAX_VALUE): SyncState {
    val playlists = LinkedHashMap<String, SyncedPlaylist>()
    val deleted = HashSet<String>()
    val favorites = LinkedHashSet<String>()
    val settings = LinkedHashMap<String, JsonElement>()
    for (op in ops.sortedBy { it.seq }) {
        if (op.seq > upTo) break
        val target = op.entity.substringAfter(':')
        fun str(key: String) = op.payload[key]?.jsonPrimitive?.contentOrNull
        when (op.kind) {
            "playlist.create" -> if (target !in deleted && target !in playlists) {
                playlists[target] = SyncedPlaylist(target, str("title").orEmpty(), emptyList())
            }
            "playlist.rename" -> playlists[target]?.let { playlists[target] = it.copy(title = str("title") ?: it.title) }
            "playlist.delete" -> if (playlists.remove(target) != null) deleted += target
            "playlist.addTrack", "playlist.moveTrack" -> playlists[target]?.let { list ->
                val track = str("videoId") ?: return@let
                if (op.kind == "playlist.addTrack" && track in list.tracks) return@let
                if (op.kind == "playlist.moveTrack" && track !in list.tracks) return@let
                val rest = list.tracks - track
                val anchor = str("after")
                val at = when {
                    "after" !in op.payload -> rest.size
                    anchor == null -> 0
                    else -> rest.indexOf(anchor).let { if (it < 0) rest.size else it + 1 }
                }
                playlists[target] = list.copy(tracks = rest.toMutableList().apply { add(at, track) })
            }
            "playlist.removeTrack" -> playlists[target]?.let { list ->
                playlists[target] = list.copy(tracks = list.tracks - (str("videoId") ?: return@let))
            }
            "favorite.set" -> if (op.payload["on"]?.jsonPrimitive?.booleanOrNull == true) favorites += target else favorites -= target
            "setting.set" -> op.payload["value"]?.let { settings[target] = it }
        }
    }
    return SyncState(playlists, favorites, settings)
}
