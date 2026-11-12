package com.bxane.nuvx.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToLong

@UnstableApi
internal class CrossfadeReadAheadController(
    private val tag: String,
) {
    var heldAtEnd: ExoPlayer? = null
        private set

    var holdWanted = false
        private set

    fun clearHoldWanted() {
        holdWanted = false
    }

    fun holdAtEnd(out: ExoPlayer) {
        if (heldAtEnd === out) return
        val duration = out.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val left = duration - out.currentPosition
        if (left <= maxReadAheadMs(out)) {
            Log.d(tag, "end not held: ${left}ms left, may already be reading on (${maxReadAheadMs(out)}ms)")
            return
        }
        releaseHold()
        out.pauseAtEndOfMediaItems = true
        heldAtEnd = out
        Log.d(tag, "end held at ${left}ms left")
    }

    fun holdAhead(player: ExoPlayer, duration: Long) {
        val left = duration - player.currentPosition
        val limit = maxReadAheadMs(player)
        if (left > limit + HOLD_AHEAD_MS) return
        holdWanted = true
        if (left > limit) holdAtEnd(player)
    }

    fun releaseHold() {
        heldAtEnd?.pauseAtEndOfMediaItems = false
        heldAtEnd = null
    }

    private fun maxReadAheadMs(player: ExoPlayer): Long {
        val speed = player.playbackParameters.speed.coerceAtLeast(1f)
        return (MEDIA3_READ_AHEAD_LIMIT_MS * speed).roundToLong() + HOLD_SAFETY_MS
    }

    private companion object {
        const val MEDIA3_READ_AHEAD_LIMIT_MS = 10_000L
        const val HOLD_SAFETY_MS = 1_000L
        const val HOLD_AHEAD_MS = 3_000L
    }
}
