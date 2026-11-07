package com.bxane.nuvx.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object CrossfadeAudio {
    val durationChoicesSeconds = listOf(0, 3, 5, 8)

    data class Levels(val outgoing: Float, val incoming: Float)

    fun normalizeDurationSeconds(seconds: Int): Int = seconds.takeIf { it in durationChoicesSeconds } ?: 0

    fun transitionDurationMs(seconds: Int, outgoingDurationMs: Long, incomingDurationMs: Long): Long {
        val configuredMs = normalizeDurationSeconds(seconds) * 1_000L
        if (configuredMs == 0L || outgoingDurationMs <= 0L || incomingDurationMs <= 0L) return 0L
        return minOf(configuredMs, outgoingDurationMs, incomingDurationMs)
    }

    /** Equal-power gains keep perceived loudness steadier while two unrelated tracks overlap. */
    fun levels(progress: Float): Levels {
        val t = progress.coerceIn(0f, 1f)
        if (t == 0f) return Levels(outgoing = 1f, incoming = 0f)
        if (t == 1f) return Levels(outgoing = 0f, incoming = 1f)
        val angle = t * PI.toFloat() / 2f
        return Levels(outgoing = cos(angle), incoming = sin(angle))
    }
}
