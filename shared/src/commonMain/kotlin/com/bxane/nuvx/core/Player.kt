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
