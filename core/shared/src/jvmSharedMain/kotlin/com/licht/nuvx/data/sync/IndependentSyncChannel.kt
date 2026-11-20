package com.licht.nuvx.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

internal enum class IndependentCategory { FAVORITES, HISTORY }

@Serializable
internal data class SyncedListen(val id: String, val videoId: String, val playedAtMs: Long)

@Serializable
internal data class IndependentState(
    val cursor: Long = 0,
    val pending: List<StoredOp> = emptyList(),
    val favorites: Map<String, Boolean> = emptyMap(),
    val history: List<SyncedListen> = emptyList(),
)

internal sealed interface IndependentResult {
    data class Applied(val state: IndependentState) : IndependentResult
    data class Failed(val cause: SyncResult) : IndependentResult
    data object Disabled : IndependentResult
}

/**
 * Independently opted-in favorite changes, not part of the playlist snapshot.
 * No local file paths, sign-in cookies, account data or library metadata are sent.
 * The saved cursor, pending IDs and current values share a single atomic store.
 */
internal class IndependentSyncChannel(
    private val client: SyncClient,
    private val store: SyncStore,
    private val enabled: () -> Boolean = { false },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val category: IndependentCategory = IndependentCategory.FAVORITES,
) {
    private companion object {
        val lock = Any()
        val youtubeId = Regex("[A-Za-z0-9_-]{11}")
        const val MAX_PENDING = 1_000
        const val MAX_HISTORY = 500
    }

    private fun read(): IndependentState = store.load()?.let {
        runCatching { Json.decodeFromString(IndependentState.serializer(), it) }.getOrNull()
    } ?: IndependentState()

    private fun write(state: IndependentState) = store.save(Json.encodeToString(IndependentState.serializer(), state))

    fun current(): IndependentState = synchronized(lock) { read() }

    /** Persists locally before any network action. Returns false when disabled/invalid/full. */
    fun setFavorite(videoId: String, on: Boolean): Boolean {
        if (category != IndependentCategory.FAVORITES || !enabled() || !youtubeId.matches(videoId)) return false
        return synchronized(lock) {
            val state = read()
            if (state.pending.size >= MAX_PENDING) return@synchronized false
            if (state.favorites[videoId] == on) return@synchronized true
            val op = StoredOp(
                id = newId(),
                kind = "favorite.set",
                entity = "favorite:$videoId",
                payload = buildJsonObject { put("on", on) }.toString(),
                clientMs = now(),
            )
            write(state.copy(
                favorites = state.favorites + (videoId to on),
                pending = state.pending + op,
            ))
            true
        }
    }

    /** Only a counted, explicitly consented play is retained. No title, IP, file URI or account details. */
    fun recordPlay(videoId: String, playedAtMs: Long = now()): Boolean {
        if (category != IndependentCategory.HISTORY || !enabled() ||
            !youtubeId.matches(videoId) || playedAtMs <= 0L) return false
        return synchronized(lock) {
            val state = read()
            if (state.pending.size >= MAX_PENDING) return@synchronized false
            val id = newId()
            val event = SyncedListen(id, videoId, playedAtMs)
            val op = StoredOp(id, "history.play", "history:$id",
                buildJsonObject {
                    put("v", 1)
                    put("videoId", videoId)
                    put("playedAtMs", playedAtMs)
                }.toString(), playedAtMs)
            write(state.copy(
                pending = state.pending + op,
                history = (state.history + event).takeLast(MAX_HISTORY),
            ))
            true
        }
    }

    fun sync(): IndependentResult {
        if (!enabled()) return IndependentResult.Disabled
        while (true) {
            if (!enabled()) return IndependentResult.Disabled
            val batch = synchronized(lock) { read().pending.take(SyncClient.MAX_OPS_PER_PUSH) }
            if (batch.isEmpty()) break
            val result = client.push(batch.map {
                PendingOp(it.id, it.kind, it.entity, Json.parseToJsonElement(it.payload) as JsonObject, it.clientMs)
            })
            if (result !is SyncResult.Pushed) return IndependentResult.Failed(result)
            if (result.ids.isEmpty()) return IndependentResult.Failed(SyncResult.RetryLater("No durable receipt for favorites"))
            synchronized(lock) {
                val state = read()
                write(state.copy(pending = state.pending.filterNot { it.id in result.ids.toSet() }))
            }
        }
        while (true) {
            if (!enabled()) return IndependentResult.Disabled
            val cursor = synchronized(lock) { read().cursor }
            val pulled = client.pull(cursor)
            if (pulled !is SyncResult.Pulled) return IndependentResult.Failed(pulled)
            synchronized(lock) {
                val state = read()
                // Avoid writing stale pages if this category was updated while the request ran.
                if (state.cursor == cursor) {
                    when (category) {
                        IndependentCategory.FAVORITES -> {
                            val incoming = state.favorites.toMutableMap()
                            for (event in pulled.ops) {
                                if (event.kind != "favorite.set" || !event.entity.startsWith("favorite:")) continue
                                val id = event.entity.removePrefix("favorite:")
                                val on = event.payload["on"]?.jsonPrimitive?.booleanOrNull
                                if (youtubeId.matches(id) && on != null) incoming[id] = on
                            }
                            // A new local edit awaiting its receipt remains visible optimistically.
                            for (event in state.pending) {
                                if (event.kind != "favorite.set") continue
                                val id = event.entity.removePrefix("favorite:")
                                val on = runCatching {
                                    (Json.parseToJsonElement(event.payload) as JsonObject)["on"]?.jsonPrimitive?.booleanOrNull
                                }.getOrNull()
                                if (youtubeId.matches(id) && on != null) incoming[id] = on
                            }
                            write(state.copy(cursor = pulled.cursor, favorites = incoming))
                        }
                        IndependentCategory.HISTORY -> {
                            val seen = state.history.associateByTo(LinkedHashMap()) { it.id }
                            for (event in pulled.ops) {
                                if (event.kind != "history.play" || !event.entity.startsWith("history:")) continue
                                val id = event.entity.removePrefix("history:")
                                val videoId = event.payload["videoId"]?.jsonPrimitive?.contentOrNull
                                val playedAt = event.payload["playedAtMs"]?.jsonPrimitive?.longOrNull
                                if (id.isNotBlank() && id.length <= 64 && videoId != null &&
                                    youtubeId.matches(videoId) && playedAt != null && playedAt > 0L
                                ) seen.putIfAbsent(id, SyncedListen(id, videoId, playedAt))
                            }
                            write(state.copy(cursor = pulled.cursor, history = seen.values.takeLast(MAX_HISTORY)))
                        }
                    }
                }
            }
            if (!pulled.more) return IndependentResult.Applied(current())
        }
    }

    /** Reset server-bound state when changing spaces; local favorites/history remain on this device. */
    fun resetForNewSpace() = synchronized(lock) {
        val local = read()
        write(IndependentState(favorites = local.favorites, history = local.history))
    }
}
