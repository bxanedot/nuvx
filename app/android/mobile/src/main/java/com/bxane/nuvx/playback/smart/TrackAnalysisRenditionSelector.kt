

package com.bxane.nuvx.playback.smart

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.bxane.nuvx.playback.AudioCache
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@UnstableApi
internal class TrackAnalysisRenditionSelector(
    private val cache: AudioCache,
    private val badRenditions: MutableSet<String>,
    private val tag: String,
) {
    private val headAttempts = ConcurrentHashMap<String, Long>()
    private val headSkipLogged = ConcurrentHashMap.newKeySet<String>()

    fun headWorthTrying(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        val candidate = cache.renditionsOf(uri)
            .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(uri.getQueryParameter("v"), it.key) }
            .filter { it.cachedPrefix > 0L && it.key !in badRenditions }
            .filter { rendition ->
                val previous = headAttempts[rendition.key] ?: return@filter true
                rendition.cachedPrefix >= previous * HEAD_RETRY_GROWTH
            }
            .maxByOrNull { headSecondsOf(it, durationSeconds) }
            ?: return null

        val prefix = candidate.cachedPrefix
        val total = candidate.contentLength
        val needed = if (durationSeconds.isFinite() && durationSeconds > MIN_HEAD_SECONDS && total > 0) {
            val bytesPerSecond = total / durationSeconds
            (MIN_HEAD_SECONDS * bytesPerSecond * HEAD_BYTES_MARGIN).toLong()
                .coerceAtLeast(MIN_HEAD_BYTES)
                .coerceAtMost(total)
        } else {
            MIN_HEAD_BYTES
        }
        if (prefix < needed) {
            if (headSkipLogged.add("$trackId@${candidate.key}")) {
                Log.d(
                    tag,
                    "Head pass for $trackId waiting: ${prefix / 1024}kB cached of " +
                        "${needed / 1024}kB needed (rendition ${candidate.key})",
                )
            }
            return null
        }

        headAttempts[candidate.key] = prefix
        return candidate
    }

    private fun headSecondsOf(rendition: AudioCache.Rendition, durationSeconds: Double): Double =
        if (rendition.contentLength > 0 && durationSeconds.isFinite() && durationSeconds > 0) {
            rendition.cachedPrefix * durationSeconds / rendition.contentLength
        } else {
            rendition.cachedPrefix / ASSUMED_BYTES_PER_SECOND
        }

    fun chooseRendition(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        val complete = cache.renditionsOf(uri)
            .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(uri.getQueryParameter("v"), it.key) }
            .filter { it.isComplete && it.key !in badRenditions }
        if (complete.isEmpty()) return null

        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        if (expected == null) {
            val own = cache.cacheKeyOf(uri)
            complete.firstOrNull { it.key == own }?.let { return it }
            return complete.singleOrNull()?.also {
                Log.d(
                    tag,
                    "Analysing $trackId from its only cached rendition ${it.key}; " +
                        "no duration to check it against",
                )
            }
        }

        for (candidate in complete) {
            val length = cache.renditionDataSource(uri, candidate)
                .use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                badRenditions.add(candidate.key)
                continue
            }
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    tag,
                    "Rendition ${candidate.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against " +
                        "${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                badRenditions.add(candidate.key)
                continue
            }
            if (candidate.key != cache.cacheKeyOf(uri)) {
                Log.d(
                    tag,
                    "Analysing $trackId from lighter rendition ${candidate.key} " +
                        "(${candidate.contentLength / 1024}kB)",
                )
            }
            return candidate
        }
        return null
    }

    fun release() {
        headAttempts.clear()
        headSkipLogged.clear()
    }

    companion object {
        const val MIN_HEAD_SECONDS = 12.0
        const val RENDITION_DURATION_TOLERANCE = 1.0
        const val HEAD_BYTES_MARGIN = 1.35
        const val MIN_HEAD_BYTES = 256L * 1024L
        const val ASSUMED_BYTES_PER_SECOND = 20_000.0
        const val HEAD_RETRY_GROWTH = 2
    }
}
