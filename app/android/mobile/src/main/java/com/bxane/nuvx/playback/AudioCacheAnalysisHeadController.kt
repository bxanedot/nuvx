package com.bxane.nuvx.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.playback.smart.AutomixAnalysisSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class AudioCacheAnalysisHeadController(
    private val scope: CoroutineScope,
    private val cache: () -> SimpleCache,
    private val cacheInitialized: () -> Boolean,
    private val upstreamAvailable: () -> Boolean,
    private val renditionsOf: (Uri) -> List<AudioCache.Rendition>,
    private val cachedPrefixBytes: (Uri) -> Long,
    private val fetch: suspend (String, Uri, Long, Long, Boolean) -> Unit,
    private val pull: suspend (String, Uri, Long, Long, Boolean) -> Unit,
    private val tag: String,
    private val minimumPrefixBytes: Long,
    private val maximumAnalysisHeadBytes: Long,
    private val maximumAnalysisTrackBytes: Long,
) {
    private val analysisHeads = ConcurrentHashMap<String, Boolean>()
    private val analysisHeadsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val analysisHeadWant = ConcurrentHashMap<String, Long>()

    fun clear() {
        analysisHeads.clear()
        analysisHeadsInFlight.clear()
        analysisHeadWant.clear()
    }

    fun removeRequest(videoId: String) {
        analysisHeads.remove(videoId)
    }

    fun removeState(videoId: String) {
        analysisHeads.remove(videoId)
        analysisHeadsInFlight.remove(videoId)
        analysisHeadWant.remove(videoId)
    }

    fun removeRequestAndWant(videoId: String) {
        analysisHeads.remove(videoId)
        analysisHeadWant.remove(videoId)
    }

    suspend fun awaitAnalysisHead(uri: Uri, timeoutMs: Long): Boolean {
        if (!cacheInitialized()) return false
        val videoId = uri.getQueryParameter("v") ?: return false
        if (alignmentHeadComplete(videoId)) return true
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        if (videoId !in analysisHeadsInFlight) {
            analysisHeads.remove(videoId)
        }
        requestAnalysisHead(opus)
        var rounds = 1
        var askedAt = SystemClock.elapsedRealtime()
        val deadline = askedAt + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (alignmentHeadComplete(videoId)) return true
            if (videoId !in analysisHeadsInFlight) {
                if (rounds >= MAX_ALIGNMENT_HEAD_ROUNDS) return alignmentHeadComplete(videoId)
                val now = SystemClock.elapsedRealtime()
                if (now - askedAt >= HEAD_ROUND_SETTLE_MS) {
                    if (alignmentPrefixBytes(videoId) < minimumPrefixBytes) {
                        analysisHeads.remove(videoId)
                        requestAnalysisHead(opus)
                        rounds++
                    }
                    askedAt = now
                }
            }
            delay(40)
        }
        return alignmentHeadComplete(videoId)
    }

    private fun alignmentHeadComplete(videoId: String): Boolean {
        val held = alignmentPrefixBytes(videoId)
        if (held >= minimumPrefixBytes) return true
        val want = analysisHeadWant[videoId] ?: return false
        if (videoId in analysisHeadsInFlight) return false
        return held >= want
    }

    private fun alignmentPrefixBytes(videoId: String): Long {
        val watch = Uri.parse("nuvx://watch?v=$videoId")
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        val renditions = renditionsOf(watch)
        val sameSource = renditions
            .filter { !it.key.endsWith("#alt") }
            .maxOfOrNull { it.cachedPrefix } ?: 0L
        if (sameSource >= minimumPrefixBytes) return sameSource
        return maxOf(
            cachedPrefixBytes(opus),
            cachedPrefixBytes(watch),
            renditions.maxOfOrNull { it.cachedPrefix } ?: 0L,
        )
    }

    fun requestAnalysisHead(uri: Uri) {
        if (!cacheInitialized()) {
            TrackLog.w(tag, "analysis head for $uri not started: cache is not initialised")
            return
        }
        if (!upstreamAvailable()) {
            TrackLog.w(tag, "analysis head for $uri not started: no upstream yet")
            return
        }
        val videoId = uri.getQueryParameter("v") ?: return
        if (analysisHeads.putIfAbsent(videoId, true) != null) return
        if (!analysisHeadsInFlight.add(videoId)) return
        scope.launch {
            try {
                val total = runCatching { StreamResolver.contentLength(videoId) }
                    .onFailure {
                        TrackLog.w(tag, "contentLength failed for $videoId: ${it.message}", videoId)
                    }
                    .getOrNull() ?: 0L
                val want = analysisHeadSize(total)
                analysisHeadWant[videoId] = want
                TrackLog.w(
                    tag,
                    "analysis head round for $videoId: total=$total want=$want " +
                        "held=${alignmentPrefixBytes(videoId)}",
                    videoId,
                )
                if (!clearPartialHead(videoId, want)) {
                    TrackLog.w(tag, "analysis head round for $videoId skipped: partial is held elsewhere", videoId)
                    return@launch
                }
                fetch(
                    videoId,
                    Uri.parse(AutomixAnalysisSource.opusUri(videoId)),
                    0,
                    want,
                    true,
                )
                if (alignmentPrefixBytes(videoId) < minimumPrefixBytes) {
                    pull(
                        videoId,
                        Uri.parse("nuvx://watch?v=$videoId"),
                        0,
                        want,
                        false,
                    )
                }
                if (total > 0) recordContentLength(videoId, total)
                val held = alignmentPrefixBytes(videoId)
                if (held < minimumPrefixBytes) {
                    TrackLog.w(
                        tag,
                        "analysis head for $videoId came up short: " +
                            "held=$held want=$want total=$total " +
                            "(cause in the read-ahead lines around this one)",
                        videoId,
                    )
                }
            } finally {
                analysisHeadsInFlight.remove(videoId)
            }
        }
    }

    private fun analysisHeadSize(total: Long): Long =
        if (total > 0) minOf(total, maximumAnalysisTrackBytes) else maximumAnalysisHeadBytes

    private fun clearPartialHead(videoId: String, want: Long): Boolean {
        val held = cache().getCachedBytes(videoId, 0, want)
        if (held <= 0L) return true
        if (held >= want) return false
        return runCatching { cache().removeResource(videoId) }
            .onSuccess {
                TrackLog.d(tag, "cleared partial analysis head for $videoId ($held of $want)", about = videoId)
            }
            .onFailure {
                TrackLog.d(tag, "partial head for $videoId is in use: ${it.message}", about = videoId)
            }
            .isSuccess
    }

    private fun recordContentLength(videoId: String, total: Long) {
        if (ContentMetadata.getContentLength(cache().getContentMetadata(videoId)) > 0) return
        if (total <= 0) return
        runCatching {
            cache().applyContentMetadataMutations(
                videoId,
                ContentMetadataMutations().apply { ContentMetadataMutations.setContentLength(this, total) },
            )
        }.onFailure { TrackLog.d(tag, "could not record length for $videoId: ${it.message}", about = videoId) }
    }

    private companion object {
        const val MAX_ALIGNMENT_HEAD_ROUNDS = 3
        const val HEAD_ROUND_SETTLE_MS = 500L
    }
}
