package com.licht.nuvx.playback

import com.licht.nuvx.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal const val MUSIC_VIDEO_ALIGNMENT_HOP_MS = 10L
internal const val MUSIC_VIDEO_ALIGNMENT_MAX_LAG_FRAMES = 6_000
internal const val MUSIC_VIDEO_ALIGNMENT_MIN_OVERLAP_FRAMES = 300
internal const val MUSIC_VIDEO_ALIGNMENT_MIN_CONFIDENCE = 0.4
internal const val MUSIC_VIDEO_ALIGNMENT_MIN_PEAK_MARGIN = 0.03
internal const val MUSIC_VIDEO_ALIGNMENT_PEAK_SEPARATION_FRAMES = 30
internal const val MUSIC_VIDEO_ALIGNMENT_WINDOW_MS = 40
internal const val MUSIC_VIDEO_ALIGNMENT_ANALYSIS_SECONDS = 90

internal fun musicVideoEnergyEnvelope(samples: FloatArray, channels: Int, rate: Int): FloatArray {
    if (channels <= 0 || rate <= 0) return FloatArray(0)
    val frameCount = samples.size / channels
    val hopFrames = max(1, rate * MUSIC_VIDEO_ALIGNMENT_HOP_MS.toInt() / 1000)
    val windowFrames = max(hopFrames, rate * MUSIC_VIDEO_ALIGNMENT_WINDOW_MS / 1000)
    if (frameCount < windowFrames) return FloatArray(0)
    val maxFrames = rate * MUSIC_VIDEO_ALIGNMENT_ANALYSIS_SECONDS
    val count = min((frameCount - windowFrames) / hopFrames + 1, maxFrames / hopFrames + 1)
    return FloatArray(count) { frame ->
        val start = frame * hopFrames * channels
        val end = start + windowFrames * channels
        var sum = 0.0
        for (index in start until end) sum += samples[index].toDouble() * samples[index]
        sqrt(sum / (windowFrames * channels)).toFloat()
    }
}

internal fun musicVideoAlignmentOffsetMs(source: FloatArray, target: FloatArray): Long? {
    if (source.size < MUSIC_VIDEO_ALIGNMENT_MIN_OVERLAP_FRAMES ||
        target.size < MUSIC_VIDEO_ALIGNMENT_MIN_OVERLAP_FRAMES
    ) {
        return null
    }
    val minLag = -min(MUSIC_VIDEO_ALIGNMENT_MAX_LAG_FRAMES, source.size - MUSIC_VIDEO_ALIGNMENT_MIN_OVERLAP_FRAMES)
    val maxLag = min(MUSIC_VIDEO_ALIGNMENT_MAX_LAG_FRAMES, target.size - MUSIC_VIDEO_ALIGNMENT_MIN_OVERLAP_FRAMES)
    var bestLag = 0
    var bestCorrelation = -1.0
    val correlations = ArrayList<Pair<Int, Double>>()
    for (lag in minLag..maxLag) {
        val sourceStart = max(0, -lag)
        val targetStart = max(0, lag)
        val overlap = min(source.size - sourceStart, target.size - targetStart)
        if (overlap < MUSIC_VIDEO_ALIGNMENT_MIN_OVERLAP_FRAMES) continue
        var sourceSum = 0.0
        var targetSum = 0.0
        var sourceSq = 0.0
        var targetSq = 0.0
        var cross = 0.0
        for (index in 0 until overlap) {
            val x = source[sourceStart + index].toDouble()
            val y = target[targetStart + index].toDouble()
            sourceSum += x
            targetSum += y
            sourceSq += x * x
            targetSq += y * y
            cross += x * y
        }
        val n = overlap.toDouble()
        val covariance = cross - sourceSum * targetSum / n
        val sourceVariance = sourceSq - sourceSum * sourceSum / n
        val targetVariance = targetSq - targetSum * targetSum / n
        val denominator = sqrt(sourceVariance * targetVariance)
        if (denominator > 1e-6) {
            val correlation = covariance / denominator
            correlations += lag to correlation
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestLag = lag
            }
        }
    }
    if (bestCorrelation < MUSIC_VIDEO_ALIGNMENT_MIN_CONFIDENCE || bestLag == minLag || bestLag == maxLag) return null
    if (correlations.any { (lag, correlation) ->
            abs(lag - bestLag) >= MUSIC_VIDEO_ALIGNMENT_PEAK_SEPARATION_FRAMES &&
                bestCorrelation - correlation < MUSIC_VIDEO_ALIGNMENT_MIN_PEAK_MARGIN
        }
    ) {
        return null
    }
    return bestLag.toLong() * MUSIC_VIDEO_ALIGNMENT_HOP_MS
}

internal fun musicVideoTargetMs(audioPositionMs: Long, offsetMs: Long): Long =
    (audioPositionMs + offsetMs).coerceAtLeast(0L)

internal object MusicVideoAlignment {
    private const val TAG = "MusicVideoAlignment"
    private const val MAX_CACHED_PAIRS = 128

    private val offsets = ConcurrentHashMap<Pair<String, String>, Long>()
    private val rejected = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    fun cached(audioId: String, videoId: String): Long? {
        if (audioId == videoId) return 0
        return offsets[audioId to videoId]
    }

    internal fun remember(audioId: String, videoId: String, offsetMs: Long) {
        if (audioId == videoId) return
        if (offsets.size >= MAX_CACHED_PAIRS) offsets.clear()
        offsets[audioId to videoId] = offsetMs
        offsets[videoId to audioId] = -offsetMs
        rejected.remove(audioId to videoId)
        rejected.remove(videoId to audioId)
    }

    suspend fun align(audioId: String, videoId: String): Long? {
        if (audioId == videoId) return 0
        val key = audioId to videoId
        cached(audioId, videoId)?.let { return it }
        if (key in rejected) return null
        try {
            val (source, target) = coroutineScope {
                val source = async(Dispatchers.IO) { MusicVideoAlignmentAudio.envelope(audioId) }
                val target = async(Dispatchers.IO) { MusicVideoAlignmentAudio.envelope(videoId) }
                source.await() to target.await()
            }
            if (source == null || target == null) {
                TrackLog.w(TAG, "video alignment: the audio for one side is unavailable", about = videoId)
                return null
            }
            val offset = musicVideoAlignmentOffsetMs(source, target)
            if (offset == null) {
                reject(key)
                TrackLog.i(TAG, "video alignment: not confident, keeping timestamp sync", about = videoId)
                return null
            }
            remember(audioId, videoId, offset)
            TrackLog.i(TAG, "video alignment: video runs ${offset}ms from the song", about = videoId)
            return offset
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "video alignment: ${e.message ?: e.javaClass.simpleName}", about = videoId)
            return null
        }
    }

    private fun reject(key: Pair<String, String>) {
        if (rejected.size >= MAX_CACHED_PAIRS) rejected.clear()
        rejected += key
    }
}
