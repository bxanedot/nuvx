package com.bxane.nuvx.playback.loopmix

import android.util.Log
import com.bxane.nuvx.playback.smart.LoopMix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

class LoopRegion(val left: FloatArray, val right: FloatArray, val start: Double)

class LoopMixSession private constructor(
    val plan: LoopMix.Plan,
    val clock: LoopMix.Clock,
    val outId: String,
    val inId: String,
    val renderer: LoopMixRenderer,
    val sampleRate: Int,
) {
    val takeoverUs: Long = (plan.out.loopStart * 1_000_000).roundToLong()

    val handoverTrack: Double = clock.elementTrackTime

    var inStartTrack: Double = 0.0
        private set
    var inStartAt: Double = 0.0
        private set

    lateinit var outPort: LoopMixPort
        private set
    lateinit var inPort: LoopMixPort
        private set

    var alignedErrorMs: Double = Double.NaN
        private set

    fun attach(out: LoopMixPort, inn: LoopMixPort) {
        outPort = out
        inPort = inn
        val firstFrame = renderer.firstFrame()
        out.armOutgoing(LoopMixPort.OutArm(renderer, takeoverUs, sampleRate, firstFrame))
    }

    fun mixSeconds(nowNanos: Long, playing: Boolean): Double? =
        outPort.presentedSeconds(nowNanos, playing)?.let { it - plan.out.loopStart }

    fun planIncomingStart() {
        inStartAt = STARTUP_LEAD
        inStartTrack = max(0.0, handoverTrack - (clock.elementAt - inStartAt))
    }

    fun offsetNow(nowNanos: Long): Double? {
        val m = mixSeconds(nowNanos, playing = true) ?: return null
        val player = inPort.presentedSeconds(nowNanos, playing = true) ?: return null
        val deck = handoverTrack - (clock.elementAt - m)
        return player - deck
    }

    fun correct(offsetSeconds: Double) {
        val frames = (offsetSeconds * inPort.sampleRate.coerceAtLeast(1)).roundToLong()
        if (frames != 0L) inPort.requestShift(-frames)
    }

    fun recordAligned(errorSeconds: Double) {
        alignedErrorMs = errorSeconds * 1000
    }

    fun close() {
        outPort.disarm()
        inPort.disarm()
        renderer.close()
    }

    companion object {
        private const val TAG = "NuvxLoopMix"

        const val STARTUP_LEAD = 0.5

        private const val PREROLL_SECONDS = 1.0
        private const val TAIL_SECONDS = 3.0

        fun build(
            plan: LoopMix.Plan,
            outId: String,
            inId: String,
            sampleRate: Int,
            inLevel: Float,
            decode: (side: LoopMix.Side, from: Double, to: Double) -> LoopRegion?,
        ): LoopMixSession? {
            if (!LoopMixRenderer.available || sampleRate <= 0) return null
            val clock = LoopMix.Clock(plan)
            val outRegion = decode(
                LoopMix.Side.OUT,
                plan.out.loopStart - PREROLL_SECONDS,
                plan.out.loopEnd + 0.5,
            ) ?: return null
            val inRegion = decode(
                LoopMix.Side.IN,
                plan.inn.loopStart - PREROLL_SECONDS,
                clock.elementTrackTime + TAIL_SECONDS,
            ) ?: return null
            if (outRegion.start > plan.out.loopStart || inRegion.start > plan.inn.loopStart) {
                Log.w(TAG, "a region starts after its loop: out ${outRegion.start}, in ${inRegion.start}")
                return null
            }
            val renderer = LoopMixRenderer(sampleRate)
            if (!renderer.isValid) return null
            val sr = sampleRate.toDouble()
            val beats = clock.beats

            fun samplesOf(region: LoopRegion, trackSeconds: Double) = (trackSeconds - region.start) * sr

            val outKnotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr }
            val outKnotPositions = DoubleArray(beats + 1) { samplesOf(outRegion, clock.outPositionAt(it)) }
            renderer.setDeck(
                LoopMixRenderer.OUT,
                LoopMixRenderer.DeckSetup(
                    left = outRegion.left,
                    right = outRegion.right,
                    regionStart = outRegion.start,
                    loopStart = samplesOf(outRegion, plan.out.loopStart).roundToLong(),
                    loopEnd = samplesOf(outRegion, plan.out.loopEnd).roundToLong(),
                    releaseAt = 1e18,
                    knotFrames = outKnotFrames,
                    knotPositions = outKnotPositions,
                    runOn = false,
                    stopAt = clock.releaseAt + 0.05,
                    preroll = 0.4,
                    level = 1f,
                ),
            )
            val inKnotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr }
            val inKnotPositions = DoubleArray(beats + 1) { samplesOf(inRegion, clock.inPositionAt(it)) }
            renderer.setDeck(
                LoopMixRenderer.IN,
                LoopMixRenderer.DeckSetup(
                    left = inRegion.left,
                    right = inRegion.right,
                    regionStart = inRegion.start,
                    loopStart = samplesOf(inRegion, plan.inn.loopStart).roundToLong(),
                    loopEnd = samplesOf(inRegion, plan.inn.loopEnd).roundToLong(),
                    releaseAt = samplesOf(inRegion, clock.inReleasePosition),
                    knotFrames = inKnotFrames,
                    knotPositions = inKnotPositions,
                    runOn = true,
                    stopAt = clock.elementAt + 2.0,
                    preroll = 0.4,
                    level = inLevel,
                ),
            )

            val h = LoopMix.HANDOVER_SEC
            renderer.setEnvelope(
                LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                doubleArrayOf(-h, 0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt),
                floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
            )
            renderer.setEnvelope(
                LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd),
                floatArrayOf(0f, 0.55f, 1f),
            )
            val swapFrom = clock.handoffAt - clock.swapBar / 2
            val swapTo = clock.handoffAt + clock.swapBar / 2
            val cut = LoopMix.BASS_CUT_DB.toFloat()
            renderer.setEnvelope(
                LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB,
                doubleArrayOf(swapFrom, swapTo), floatArrayOf(0f, cut),
            )
            renderer.setEnvelope(
                LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB,
                doubleArrayOf(swapFrom, swapTo), floatArrayOf(cut, 0f),
            )
            renderer.setEnvelope(
                LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                doubleArrayOf(clock.rampEnd, clock.releaseAt),
                floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                exponential = true,
            )
            renderer.prepare()
            renderer.setFadeOut(LoopMixRenderer.IN, clock.elementTrackTime)
            return LoopMixSession(plan, clock, outId, inId, renderer, sampleRate).also { it.planIncomingStart() }
        }

        const val ALIGNED_SECONDS = 0.0015

        fun aligned(errorSeconds: Double) = abs(errorSeconds) <= ALIGNED_SECONDS
    }
}
