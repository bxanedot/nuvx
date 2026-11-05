package com.bxane.nuvx.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Music radio keeps extending the queue near the end of the active station. */
class RadioStation(
    private val router: BackendRouter,
    private val player: NuvxPlayer,
    private val scope: CoroutineScope,
    private val refillWhenRemaining: Int = 3,
    var avoidRepeats: Boolean = true,
) {
    private val _seed = MutableStateFlow<Track?>(null)
    val seed: StateFlow<Track?> = _seed.asStateFlow()
    private var continuation: String? = null
    private val seen = mutableSetOf<String>()
    private val disliked = mutableSetOf<String>()
    private var refill: Job? = null
    private var session = 0

    suspend fun start(seed: Track) {
        stop()
        val startedSession = session
        seen.clear()
        disliked.clear()
        seen += seed.videoId
        val page = router.radio(seed.videoId)
        if (startedSession != session) return
        continuation = page.continuation
        _seed.value = seed
        player.playQueue(listOf(seed) + fresh(page.tracks), 0)
    }

    fun dislike(track: Track) { disliked += track.videoId }

    fun stop() {
        session++
        _seed.value = null
        continuation = null
        refill?.cancel()
        refill = null
    }

    /** Call when the active queue or index changes. */
    fun onState(state: PlayerState) {
        val seed = _seed.value ?: return
        if (state.queue.isEmpty()) {
            stop()
            return
        }
        val current = state.current ?: return
        if (current.videoId !in seen) {
            stop()
            return
        }
        if (state.queue.size - state.index > refillWhenRemaining || refill?.isActive == true) return
        val startedSession = session
        refill = scope.launch {
            val anchor = state.queue.lastOrNull()?.videoId ?: seed.videoId
            val page = try {
                router.radio(if (continuation != null) seed.videoId else anchor, continuation)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (startedSession != session) return@launch
            continuation = page.continuation
            fresh(page.tracks).takeIf { it.isNotEmpty() }?.let(player::enqueue)
        }
    }

    private fun fresh(tracks: List<Track>) = tracks.filter { track ->
        val isNew = seen.add(track.videoId)
        track.videoId !in disliked && (!avoidRepeats || isNew)
    }
}
