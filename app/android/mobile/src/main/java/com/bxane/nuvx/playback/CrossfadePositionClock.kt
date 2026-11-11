package com.bxane.nuvx.playback

import androidx.media3.exoplayer.ExoPlayer

internal class CrossfadePositionClock(
    private val halfTickNanos: Long,
    private val maxExtrapolationNanos: Long,
) {
    private var player: ExoPlayer? = null
    private var reported = 0L
    private var seenAt = 0L

    fun reset() {
        player = null
    }

    fun read(p: ExoPlayer): Double {
        val now = System.nanoTime()
        val position = p.currentPosition
        if (p !== player || position != reported) {
            seenAt = if (p === player) now - halfTickNanos else now
            player = p
            reported = position
        }
        if (!p.isPlaying) return position.toDouble()
        val since = (now - seenAt).coerceIn(0L, maxExtrapolationNanos)
        return position + since / 1e6 * p.playbackParameters.speed
    }
}
