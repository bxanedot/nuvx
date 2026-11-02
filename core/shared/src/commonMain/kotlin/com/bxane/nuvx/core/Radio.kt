package com.bxane.nuvx.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Music radio: seeds a queue from a track and keeps extending it near the end, skipping anything already played,
 * queued or disliked so the station doesn't loop.
 */
class RadioStation(
    private val router: BackendRouter,
    private val player: NuvxPlayer,
    private val scope: CoroutineScope,
    private val refillWhenRemaining: Int = 3,
) {
    private val _seed = MutableStateFlow<Track?>(null)
    val seed: StateFlow<Track?> = _seed.asStateFlow()
    private var continuation: String? = null
    private val seen = mutableSetOf<String>()
    private val disliked = mutableSetOf<String>()
    private var refill: Job? = null

    suspend fun start(seed: Track) {
        _seed.value = seed
        seen.clear()
        seen += seed.videoId
        continuation = null
        val page = router.radio(seed.videoId)
        continuation = page.continuation
        val tracks = listOf(seed) + fresh(page.tracks)
        player.playQueue(tracks, 0)
    }

    fun dislike(track: Track) { disliked += track.videoId }

    fun stop() { _seed.value = null; refill?.cancel() }

    /** Call on every player state change. */
    fun onState(state: PlayerState) {
        val seed = _seed.value ?: return
        if (state.queue.size - state.index > refillWhenRemaining || refill?.isActive == true) return
        refill = scope.launch {
            val anchor = state.queue.lastOrNull()?.videoId ?: seed.videoId
            val page = runCatching { router.radio(if (continuation != null) seed.videoId else anchor, continuation) }.getOrNull() ?: return@launch
            continuation = page.continuation
            fresh(page.tracks).takeIf { it.isNotEmpty() }?.let(player::enqueue)
        }
    }

    private fun fresh(tracks: List<Track>) = tracks.filter { it.videoId !in disliked && seen.add(it.videoId) }
}
