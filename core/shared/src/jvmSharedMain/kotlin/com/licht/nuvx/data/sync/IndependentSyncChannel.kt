package com.licht.nuvx.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
internal data class IndependentState(
    val cursor: Long = 0,
    val pending: List<StoredOp> = emptyList(),
    val favorites: Map<String, Boolean> = emptyMap(),
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
) {
    private companion object {
        val lock = Any()
        val youtubeId = Regex("[A-Za-z0-9_-]{11}")
        const val MAX_PENDING = 1_000
    }

    private fun read(): IndependentState = store.load()?.let {
        runCatching { Json.decodeFromString(IndependentState.serializer(), it) }.getOrNull()
    } ?: IndependentState()

    private fun write(state: IndependentState) = store.save(Json.encodeToString(IndependentState.serializer(), state))

    fun current(): IndependentState = synchronized(lock) { read() }

    /** Persists locally before any network action. Returns false when disabled/invalid/full. */
    fun setFavorite(videoId: String, on: Boolean): Boolean {
        if (!enabled() || !youtubeId.matches(videoId)) return false
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
                    val incoming = state.favorites.toMutableMap()
                    for (event in pulled.ops) {
                        if (event.kind != "favorite.set" || !event.entity.startsWith("favorite:")) continue
                        val id = event.entity.removePrefix("favorite:")
                        val on = event.payload["on"]?.jsonPrimitive?.booleanOrNull
                        if (youtubeId.matches(id) && on != null) incoming[id] = on
                    }
                    // A new, not-yet-acknowledged local edit wins optimistically until its receipt.
                    for (event in state.pending) {
                        val id = event.entity.removePrefix("favorite:")
                        val on = runCatching {
                            Json.parseToJsonElement(event.payload).let { it as JsonObject }["on"]?.jsonPrimitive?.booleanOrNull
                        }.getOrNull()
                        if (event.kind == "favorite.set" && youtubeId.matches(id) && on != null) incoming[id] = on
                    }
                    write(state.copy(cursor = pulled.cursor, favorites = incoming))
                }
            }
            if (!pulled.more) return IndependentResult.Applied(current())
        }
    }

    /** Forget server-bound cursors and unsent operations on space change; keep local favorites. */
    fun resetForNewSpace() = synchronized(lock) {
        val local = read().favorites
        write(IndependentState(favorites = local))
    }
}
