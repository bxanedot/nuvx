

package com.bxane.nuvx.playback.smart

import android.media.MediaDataSource
import android.util.Log
import kotlin.math.abs
import kotlin.math.ceil

internal class TrackAnalysisRegionProcessor(
    private val tracker: BeatTracker,
    private val vocals: VocalTracker,
    private val stopped: () -> Boolean,
    private val tag: String,
) {
    class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
        val seconds: Double,
        val features: TrackFeatures.Features? = null,
        val start: Double = 0.0,
        val vocalWindow: CurveWindow? = null,
        val levelWindow: CurveWindow? = null,
    )

    class Beats(val beats: List<Double>, val downbeats: List<Double>, val confidence: Double)

    private class RegionInputs(val forModel: FloatArray?, val derived: TrackFeatures.Features?)

    fun modelWindowSeconds(): Double =
        if (VocalSpectrogram.available) {
            (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        } else {
            BeatTracker.WINDOW_SECONDS
        }

    fun headEnd(duration: Double, span: Double): Double =
        minOf(duration, HEAD_MODEL_WINDOWS * span - (HEAD_MODEL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS)

    fun tailStart(duration: Double, span: Double): Double =
        maxOf(0.0, duration - (TAIL_MODEL_WINDOWS * span - (TAIL_MODEL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS))

    fun modelWindows(headEnd: Double, tailStart: Double, duration: Double, span: Double): List<Pair<Double, Double>> {
        val spans = if (tailStart <= headEnd) {
            listOf(0.0 to duration)
        } else {
            listOf(0.0 to headEnd, tailStart to duration)
        }
        val windows = ArrayList<Pair<Double, Double>>()
        for ((from, to) in spans) {
            val length = to - from
            if (length < 1.0) continue
            if (length <= span) {
                windows += from to to
                continue
            }
            val count = ceil((length - WINDOW_OVERLAP_SECONDS) / (span - WINDOW_OVERLAP_SECONDS) - 1e-6).toInt()
            val stride = (length - span) / (count - 1)
            for (index in 0 until count) {
                val start = from + index * stride
                windows += start to minOf(to, start + span)
            }
        }
        return windows
    }

    fun seamBeats(regions: List<Region>): Beats {
        val graded = regions.filter { it.grid != null }.sortedBy { it.start }
        if (graded.isEmpty()) return Beats(emptyList(), emptyList(), 0.0)
        val beats = ArrayList<Double>()
        val downbeats = ArrayList<Double>()
        var weighted = 0.0
        var count = 0
        for ((index, region) in graded.withIndex()) {
            val grid = region.grid!!
            val previous = graded.getOrNull(index - 1)
            val next = graded.getOrNull(index + 1)
            val from = previous?.let { (it.start + it.seconds + region.start) / 2 }
                ?.takeIf { it > region.start } ?: Double.NEGATIVE_INFINITY
            val until = next?.let { (region.start + region.seconds + it.start) / 2 }
                ?.takeIf { it < region.start + region.seconds } ?: Double.POSITIVE_INFINITY
            val kept = grid.beats.filter { it >= from && it < until }
            beats += kept
            downbeats += grid.downbeats.filter { it >= from && it < until }
            weighted += grid.beatConfidence * kept.size
            count += kept.size
        }
        return Beats(beats.sorted(), downbeats.sorted(), if (count > 0) weighted / count else 0.0)
    }

    fun beatSide(all: Beats, from: Double, to: Double): BeatSide? {
        val beats = all.beats.filter { it >= from && it <= to }
        val bpm = BeatTracker.tempoFromBeats(beats)
        if (bpm <= 0) return null
        return BeatSide(
            beats = beats,
            downbeats = all.downbeats.filter { it >= from && it <= to },
            bpm = bpm,
            confidence = all.confidence,
        )
    }

    fun beatGrid(side: BeatSide) = BeatTracker.Grid(
        beats = side.beats,
        downbeats = side.downbeats,
        bpm = side.bpm,
        beatInterval = 60 / side.bpm,
        firstBeat = side.beats.first(),
        beatConfidence = side.confidence,
    )

    fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features?,
        deriveFeatures: Boolean = false,
    ): Region? {
        val decoded = openSource()?.use { AudioDecoder.decodeRegionStereo(it, startSeconds, endSeconds) { stopped() } }
            ?: run {
                Log.d(tag, "Region [$startSeconds, $endSeconds) would not open")
                return null
            }
        val (stereo, actualStart) = decoded
        if (stereo.left.size < stereo.sampleRate) {
            Log.d(tag, "Region [$startSeconds, $endSeconds) decoded ${stereo.left.size} frames; too few")
            return null
        }

        val seconds = stereo.left.size / stereo.sampleRate
        val inputs = regionInputs(stereo, seconds, deriveFeatures)

        val vocalValues = if (features != null) vocalValues(stereo) else null
        return Region(
            grid = inputs.forModel?.let { tracker.track(it, offsetSeconds = actualStart) },
            vocalMask = if (features != null && vocalValues != null) vocalMask(vocalValues, features, actualStart) else null,
            seconds = seconds,
            features = inputs.derived,
            start = actualStart,
            vocalWindow = vocalValues?.let { CurveWindow(actualStart, VocalSpectrogram.frameRate, it) },
            levelWindow = if (features != null) levelCurve(stereo, actualStart) else null,
        )
    }

    fun mergeMasks(size: Int, masks: List<DoubleArray>): List<Double>? {
        if (size <= 0 || masks.isEmpty()) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in masks) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    private fun levelCurve(stereo: AudioDecoder.StereoPcm, actualStart: Double): CurveWindow? {
        val frame = (stereo.sampleRate / LEVEL_FRAME_RATE).toInt()
        if (frame <= 0) return null
        val count = stereo.left.size / frame
        if (count <= 0) return null
        val values = FloatArray(count)
        for (index in 0 until count) {
            var sum = 0.0
            var n = 0
            var sample = index * frame
            val end = sample + frame
            while (sample < end) {
                val mixed = (stereo.left[sample] + stereo.right[sample]) * 0.5f
                sum += mixed * mixed
                n++
                sample += 4
            }
            values[index] = levelFromRms(kotlin.math.sqrt(sum / n)).toFloat()
        }
        return CurveWindow(actualStart, LEVEL_FRAME_RATE, values)
    }

    private fun regionInputs(
        stereo: AudioDecoder.StereoPcm,
        seconds: Double,
        deriveFeatures: Boolean,
    ): RegionInputs {
        val mono = FloatArray(stereo.left.size) { index -> (stereo.left[index] + stereo.right[index]) * 0.5f }
        val forModel = if (abs(stereo.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
            MelSpectrogram.resample(mono, stereo.sampleRate, MelSpectrogram.sampleRate)
        } else {
            mono
        }

        val derived = if (deriveFeatures) {
            val forFeatures = if (abs(stereo.sampleRate - TrackFeatures.sampleRate) > 1.0) {
                TrackFeatures.resample(mono, stereo.sampleRate, TrackFeatures.sampleRate)
            } else {
                mono
            }
            forFeatures?.let { TrackFeatures.analyze(it, seconds) }
        } else {
            null
        }

        return RegionInputs(forModel, derived)
    }

    private fun vocalValues(stereo: AudioDecoder.StereoPcm): FloatArray? {
        if (!VocalSpectrogram.available) return null
        val maxSeconds = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val maxSamples = (maxSeconds * stereo.sampleRate).toInt().coerceAtMost(stereo.left.size)
        if (maxSamples <= 0) return null
        val left = if (maxSamples < stereo.left.size) stereo.left.copyOf(maxSamples) else stereo.left
        val right = if (maxSamples < stereo.right.size) stereo.right.copyOf(maxSamples) else stereo.right
        return vocals.track(left, right, stereo.sampleRate)
    }

    private fun vocalMask(
        values: FloatArray,
        features: TrackFeatures.Features,
        actualStart: Double,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty()) return null

        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }
        for (index in curve.indices) {
            val frame = ((curve[index].time - actualStart) * VocalSpectrogram.frameRate).toInt()
            if (frame in values.indices) mask[index] = values[frame].toDouble()
        }
        return mask
    }

    private companion object {
        const val NEUTRAL_VOCAL = 0.2
        const val HEAD_MODEL_WINDOWS = 2
        const val TAIL_MODEL_WINDOWS = 3
        const val WINDOW_OVERLAP_SECONDS = 1.0
    }
}
