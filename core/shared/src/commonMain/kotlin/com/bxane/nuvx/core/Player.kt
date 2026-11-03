package com.bxane.nuvx.core

import kotlinx.coroutines.flow.StateFlow

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
}

/** Platform playback. Implementations resolve each [Track.videoId] through [BackendRouter.stream] when it is loaded. */
interface NuvxPlayer {
    val state: StateFlow<PlayerState>
    fun playQueue(tracks: List<Track>, startIndex: Int = 0)
    fun enqueue(tracks: List<Track>)
    fun togglePlay()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun skipTo(index: Int)
    /** Insert right after the current track. Players that can't insert fall back to adding at the end. */
    fun playNext(tracks: List<Track>) = enqueue(tracks)
    /** Remove a queued track. The default does nothing; platform players override it. */
    fun remove(index: Int) {}
}

/** Minimal persisted preferences; Android uses SharedPreferences, desktop java.util.prefs. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Restores and persists the engine choice so the switch survives restarts. */
fun BackendRouter.bindTo(store: KeyValueStore): BackendRouter = apply {
    store.get("engine")?.let { saved -> BackendId.entries.firstOrNull { it.name == saved }?.takeIf { it in available }?.let(::select) }
    store.get("engineFallback")?.let { setFallback(it == "true") }
}

fun BackendRouter.choose(id: BackendId, store: KeyValueStore) { select(id); store.put("engine", id.name) }

fun BackendRouter.chooseFallback(enabled: Boolean, store: KeyValueStore) { setFallback(enabled); store.put("engineFallback", enabled.toString()) }

/**
 * Queue after removing [index]. Returns the new state and whether the playing track changed (so the player must load it).
 * Removing the playing track moves on to the track that takes its place, or the new last one; removing the only track empties the queue.
 */
fun PlayerState.removing(index: Int): Pair<PlayerState, Boolean>? {
    if (index !in queue.indices) return null
    val rest = queue.filterIndexed { i, _ -> i != index }
    return when {
        index < this.index -> copy(queue = rest, index = this.index - 1) to false
        index > this.index -> copy(queue = rest) to false
        rest.isEmpty() -> PlayerState() to true
        else -> copy(queue = rest, index = this.index.coerceAtMost(rest.lastIndex)) to true
    }
}
