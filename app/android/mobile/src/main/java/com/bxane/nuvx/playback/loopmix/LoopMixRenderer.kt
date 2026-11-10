package com.bxane.nuvx.playback.loopmix

import android.util.Log

class LoopMixRenderer(val sampleRate: Int) : AutoCloseable {

    @Volatile
    private var handle: Long = if (available) nativeCreate(sampleRate) else 0L

    val isValid: Boolean get() = handle != 0L

    class DeckSetup(
        val left: FloatArray,
        val right: FloatArray,
        val regionStart: Double,
        val loopStart: Long,
        val loopEnd: Long,
        val releaseAt: Double,
        val knotFrames: DoubleArray,
        val knotPositions: DoubleArray,
        val runOn: Boolean,
        val stopAt: Double,
        val preroll: Double,
        val level: Float,
    )

    fun setDeck(index: Int, deck: DeckSetup) = nativeSetDeck(
        handle, index, deck.left, deck.right, deck.regionStart, deck.loopStart, deck.loopEnd, deck.releaseAt,
        deck.knotFrames, deck.knotPositions, deck.runOn, deck.stopAt, deck.preroll, deck.level,
    )

    fun setEnvelope(index: Int, kind: Int, times: DoubleArray, values: FloatArray, exponential: Boolean = false) =
        nativeSetEnvelope(handle, index, kind, times, values, exponential)

    fun prepare() = nativePrepare(handle)

    fun firstFrame(): Long = nativeFirstFrame(handle)

    fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int) =
        nativeRender(handle, frame, interleaved, offset, frames)

    fun setFadeOut(index: Int, trackSeconds: Double) = nativeSetFadeOut(handle, index, trackSeconds)

    fun heard(index: Int): Double = nativeHeard(handle, index)

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    companion object {
        const val OUT = 0
        const val IN = 1
        const val KIND_GAIN = 0
        const val KIND_LOW_DB = 1
        const val KIND_HIGH_PASS_HZ = 2

        val available: Boolean = runCatching { System.loadLibrary("nuvx_loopmix") }
            .onFailure { Log.w("NuvxLoopMix", "Loop mix renderer unavailable", it) }
            .isSuccess

        @JvmStatic private external fun nativeCreate(sampleRate: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeSetDeck(
            handle: Long, index: Int, left: FloatArray, right: FloatArray, regionStart: Double,
            loopStart: Long, loopEnd: Long, releaseAt: Double, knotFrames: DoubleArray, knotPositions: DoubleArray,
            runOn: Boolean, stopAt: Double, preroll: Double, level: Float,
        )
        @JvmStatic private external fun nativeSetEnvelope(
            handle: Long, index: Int, kind: Int, times: DoubleArray, values: FloatArray, exponential: Boolean,
        )
        @JvmStatic private external fun nativePrepare(handle: Long)
        @JvmStatic private external fun nativeFirstFrame(handle: Long): Long
        @JvmStatic private external fun nativeRender(handle: Long, frame: Long, interleaved: FloatArray, offset: Int, frames: Int)
        @JvmStatic private external fun nativeSetFadeOut(handle: Long, index: Int, trackSeconds: Double)
        @JvmStatic private external fun nativeHeard(handle: Long, index: Int): Double
    }
}
