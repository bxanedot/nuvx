package com.bxane.nuvx.playback

import android.content.Context
import android.net.Uri
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.playback.smart.AutomixAnalysisSource
import com.bxane.nuvx.playback.smart.VersionAudioAligner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

internal class PlaybackVersionAlignmentController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val versionAlignmentMeasurements = ConcurrentHashMap<Pair<String, String>, Deferred<Long?>>()

    private fun measureVersionAlignmentAsync(sourceSong: Song, targetSong: Song): Deferred<Long?> {
        val sourceId = sourceSong.videoId
        val targetId = targetSong.videoId
        val key = sourceId to targetId
        VersionAudioAligner.getCachedOffsetMs(sourceId, targetId)?.let { return CompletableDeferred(it) }
        versionAlignmentMeasurements[key]?.let { return it }
        val deferred = scope.async(Dispatchers.Main) {
            try {
                val sourceUri = Uri.parse(sourceSong.localUri ?: AutomixAnalysisSource.opusUri(sourceId))
                val targetUri = Uri.parse(targetSong.localUri ?: AutomixAnalysisSource.opusUri(targetId))
                withContext(Dispatchers.IO) {
                    val sourceHead = async {
                        sourceSong.localUri != null ||
                            AudioCache.awaitAnalysisHead(sourceUri, ALIGNMENT_HEAD_TIMEOUT_MS)
                    }
                    val targetHead = async {
                        targetSong.localUri != null ||
                            AudioCache.awaitAnalysisHead(targetUri, ALIGNMENT_HEAD_TIMEOUT_MS)
                    }
                    val sourceReady = sourceHead.await()
                    val targetReady = targetHead.await()
                    if (!sourceReady || !targetReady) {
                        TrackLog.w(
                            "nuvx",
                            "version alignment head short on disk (source=$sourceReady, " +
                                "target=$targetReady) for '${sourceSong.title}'",
                            sourceId,
                        )
                    }
                }
                withTimeoutOrNull(ALIGNMENT_MEASURE_TIMEOUT_MS) {
                    VersionAudioAligner.findOffsetMs(
                        context = context,
                        sourceSong = sourceSong,
                        targetSong = targetSong,
                    )
                }
            } finally {
                versionAlignmentMeasurements.remove(key)
            }
        }
        versionAlignmentMeasurements[key] = deferred
        return deferred
    }

    suspend fun prepareVersionAlignment(
        sourceSong: Song,
        targetSong: Song,
        currentPosMs: Long,
    ): Long {
        val sourceId = sourceSong.videoId
        val targetId = targetSong.videoId

        VersionAudioAligner.getCachedOffsetMs(sourceId, targetId)?.let { cached ->
            val cachedToken = VersionAudioAligner.beginAttempt(sourceId, targetId)
            VersionAudioAligner.publishPhase(
                cachedToken,
                VersionAudioAligner.CutPhase.ALIGNED,
                cached,
                fromCache = true,
            )
            return cached
        }

        val token = VersionAudioAligner.beginAttempt(sourceId, targetId)
        try {
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FETCHING)
            val deferred = measureVersionAlignmentAsync(sourceSong, targetSong)
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.MEASURING)
            val measured = deferred.await()
            if (measured == null) {
                TrackLog.w(
                    "nuvx",
                    "version alignment found nothing to measure for '${sourceSong.title}'; switching with no shift",
                    sourceId,
                )
                VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FAILED)
                return 0L
            }
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.ALIGNED, measured)
            return measured
        } catch (e: CancellationException) {
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FAILED)
            throw e
        }
    }

    private companion object {
        const val ALIGNMENT_HEAD_TIMEOUT_MS = 20_000L
        const val ALIGNMENT_MEASURE_TIMEOUT_MS = 30_000L
    }
}
