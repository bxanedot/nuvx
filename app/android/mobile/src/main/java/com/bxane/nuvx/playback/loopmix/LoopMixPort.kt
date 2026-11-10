package com.bxane.nuvx.playback.loopmix

import androidx.media3.common.C
import java.util.concurrent.atomic.AtomicLong

class LoopMixPort {

    class OutArm(
        val renderer: LoopMixRenderer,
        val takeoverUs: Long,
        val sampleRate: Int,
        val firstFrame: Long,
    )

    class InArm(
        val handoverUs: Long,
    )

    @Volatile var outArm: OutArm? = null
        private set

    @Volatile var inArm: InArm? = null
        private set

    @Volatile var generation: Int = 0
        private set

    fun armOutgoing(arm: OutArm) {
        inArm = null
        outArm = arm
        generation++
    }

    fun armIncoming(arm: InArm) {
        outArm = null
        inArm = arm
        pendingShift.set(0)
        generation++
    }

    fun disarm() {
        outArm = null
        inArm = null
        pendingShift.set(0)
        generation++
    }

    @Volatile var sampleRate: Int = 0
    @Volatile var channelCount: Int = 0
    @Volatile var precision: Boolean = false

    val supportsLoopMix: Boolean get() = precision && channelCount == 2 && sampleRate > 0

    @Volatile var streamOffsetUs: Long = 0L

    @Volatile var tookOver: Boolean = false

    @Volatile var interrupted: Boolean = false

    @Volatile var positionUs: Long = C.TIME_UNSET
    @Volatile var positionNanos: Long = 0L

    fun presentedSeconds(nowNanos: Long, playing: Boolean): Double? {
        val position = positionUs
        if (position == C.TIME_UNSET) return null
        val elapsed = if (playing) (nowNanos - positionNanos) / 1e9 else 0.0
        return position / 1e6 + elapsed
    }

    val pendingShift = AtomicLong()

    @Volatile var processedUs: Long = C.TIME_UNSET

    fun requestShift(frames: Long) {
        pendingShift.addAndGet(frames)
    }

    val spliceSong = FloatArray(SPLICE_CAPTURE)
    val spliceDeck = FloatArray(SPLICE_CAPTURE)
    @Volatile var spliceCaptured = false

    fun resetSplice() {
        spliceCaptured = false
        spliceSong.fill(0f)
        spliceDeck.fill(0f)
    }

    fun spliceLag(maxLag: Int = 960): Pair<Int, Double>? {
        if (!spliceCaptured) return null
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var bestNorm = 0.0
        for (lag in -maxLag..maxLag) {
            var dot = 0.0
            var song = 0.0
            var deck = 0.0
            val from = maxOf(0, -lag)
            val to = minOf(SPLICE_CAPTURE, SPLICE_CAPTURE - lag)
            for (i in from until to) {
                val a = spliceSong[i].toDouble()
                val b = spliceDeck[i + lag].toDouble()
                dot += a * b
                song += a * a
                deck += b * b
            }
            val norm = kotlin.math.sqrt(song * deck)
            if (norm > 0 && dot / norm > bestScore) {
                bestScore = dot / norm
                best = lag
                bestNorm = norm
            }
        }
        return if (bestNorm > 0) best to bestScore else null
    }

    companion object {
        const val SPLICE_CAPTURE = 8192
    }
}
