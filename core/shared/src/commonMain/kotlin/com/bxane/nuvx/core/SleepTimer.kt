package com.bxane.nuvx.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Pauses playback after a set time or when the current track ends. Setting a new timer replaces the old one; null turns it off. */
class SleepTimer(private val player: NuvxPlayer, private val scope: CoroutineScope) {
    sealed interface Mode {
        data class After(val minutes: Int) : Mode
        data object EndOfTrack : Mode
    }

    private val mutableMode = MutableStateFlow<Mode?>(null)
    val mode: StateFlow<Mode?> = mutableMode.asStateFlow()
    private var job: Job? = null

    fun set(mode: Mode?) {
        job?.cancel()
        mutableMode.value = mode
        if (mode == null) return
        job = scope.launch {
            when (mode) {
                is Mode.After -> delay(mode.minutes * 60_000L)
                Mode.EndOfTrack -> player.state.value.current?.videoId?.let { playing ->
                    // shortcut: repeat-one detection needs a reported duration; unknown-duration tracks wait for a track change.
                    var reachedEnd = false
                    val ended = player.state.first { state ->
                        if (state.current?.videoId != playing) return@first true
                        if (state.durationMs > 0 && state.positionMs >= (state.durationMs - 1_500).coerceAtLeast(0)) reachedEnd = true
                        reachedEnd && state.positionMs < 3_000
                    }
                    if (ended.current?.videoId != playing) {
                        // The next track is usually still buffering at this point; pause it once it actually starts.
                        withTimeoutOrNull(10_000) { player.state.first { it.isPlaying } }
                    }
                }
            }
            if (player.state.value.isPlaying) player.togglePlay()
            mutableMode.value = null
        }
    }
}
