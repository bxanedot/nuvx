package com.bxane.nuvx.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PlaybackCacheMaintenanceController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val spare: () -> ExoPlayer?,
    private val streamUpgrade: StreamUpgradeController,
    private val queuePrefetch: PlaybackQueuePrefetchController,
) {
    private var foreignCachePurgeJob: Job? = null

    fun scheduleForeignCachePurge() {
        foreignCachePurgeJob?.cancel()
        foreignCachePurgeJob = scope.launch {
            delay(FOREIGN_CACHE_PURGE_DELAY_MS)
            val exoPlayer = player() ?: return@launch
            val keep = buildSet {
                exoPlayer.currentMediaItem?.mediaId?.let(::add)
                val current = exoPlayer.currentMediaItemIndex
                if (current != C.INDEX_UNSET) {
                    val window = (current - 1)..(current + FOREIGN_CACHE_KEEP_AHEAD)
                    for (index in window) {
                        if (index in 0 until exoPlayer.mediaItemCount) add(exoPlayer.getMediaItemAt(index).mediaId)
                    }
                }
                spare()?.currentMediaItem?.mediaId?.let(::add)
            }
            AudioCache.dropForeignEntries(keep)
        }
    }

    fun onCeilingLowered(ceiling: AudioQuality, lost: List<SourceKind>) {
        val exoPlayer = player() ?: return
        val keep = buildSet {
            exoPlayer.currentMediaItem?.mediaId?.let(::add)
            spare()?.currentMediaItem?.mediaId?.let(::add)
            val duration = exoPlayer.duration
            val next = exoPlayer.nextMediaItemIndex
            if (duration != C.TIME_UNSET && next != C.INDEX_UNSET &&
                exoPlayer.totalBufferedDuration >= duration - exoPlayer.currentPosition
            ) {
                add(exoPlayer.getMediaItemAt(next).mediaId)
            }
        }
        TrackLog.d(
            "nuvx",
            "quality ceiling now ${ceiling.name}; letting go of ${lost.joinToString { it.label }} " +
                "except for ${keep.joinToString()}",
        )
        val permitted = { stream: SourceStream -> SourceRegistry.permitsForPlayback(stream.sourceConfigId) }
        streamUpgrade.cancelStaleUpgradeJob()
        queuePrefetch.cancelPrefetch()
        StreamChoice.forgetWhere(keep) { !permitted(it) }
        QualityUpgrade.onCeilingLowered(keep, permitted)
        AudioCache.dropForeignEntries(keep, keepAnalysis = true)
        queuePrefetch.prefetchAround(exoPlayer)
        streamUpgrade.lookForBetterCopy(exoPlayer)
    }

    private companion object {
        const val FOREIGN_CACHE_PURGE_DELAY_MS = 30_000L
        const val FOREIGN_CACHE_KEEP_AHEAD = 3
    }
}
