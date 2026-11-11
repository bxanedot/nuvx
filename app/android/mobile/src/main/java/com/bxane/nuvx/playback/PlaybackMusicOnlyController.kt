package com.bxane.nuvx.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class PlaybackMusicOnlyController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val isTransitioning: () -> Boolean,
    private val isVersionSwapActive: () -> Boolean,
    private val prefetchAround: (ExoPlayer) -> Unit,
    private val smoothSwapCurrentTrackVersion: (MediaItem) -> Unit,
) {
    private val musicOnlyLookups = HashMap<String, Job>()

    fun convertVideosAround(exoPlayer: ExoPlayer) {
        if (!MusicOnlyPlayback.isActive()) return
        if (isTransitioning() || isVersionSwapActive()) return
        val rows = listOf(exoPlayer.currentMediaItemIndex, exoPlayer.nextMediaItemIndex)
            .filter { it in 0 until exoPlayer.mediaItemCount }
            .distinct()
        MusicOnlyPlayback.forgetExcept(rows.map { exoPlayer.getMediaItemAt(it).mediaId }.toSet())
        for (index in rows) {
            val item = exoPlayer.getMediaItemAt(index)
            if (!MusicOnlyPlayback.wants(item)) continue
            val video = item.toSong()
            val audio = YtMusicRepository.cachedAudioVersion(video.videoId)
            when {
                audio == null -> lookUpMusicOnly(video)
                audio.videoId == video.videoId || audio.isVideo -> MusicOnlyPlayback.settle(video.videoId)
                else -> {
                    applyMusicOnly(exoPlayer, index, item, video, audio)
                    convertVideosAround(exoPlayer)
                    return
                }
            }
        }
    }

    private fun lookUpMusicOnly(video: Song) {
        if (musicOnlyLookups[video.videoId]?.isActive == true) return
        musicOnlyLookups[video.videoId] = scope.launch {
            val audio = withTimeoutOrNull(MusicOnlyPlayback.ANSWER_TIMEOUT_MS) {
                runCatching { YtMusicRepository.resolveAudio(video) }.getOrNull()
            }
            musicOnlyLookups.remove(video.videoId)
            if (audio == null || audio.videoId == video.videoId) {
                TrackLog.d("nuvx", "music-only: no audio version, playing the video's own audio", about = video.videoId)
                MusicOnlyPlayback.settle(video.videoId)
            }
            val live = player() ?: return@launch
            convertVideosAround(live)
            if (live.isPlaying) prefetchAround(live)
        }
    }

    private fun applyMusicOnly(exoPlayer: ExoPlayer, index: Int, item: MediaItem, video: Song, audio: Song) {
        TrackLog.d("nuvx", "music-only: playing '${audio.title}' (${audio.videoId})", about = video.videoId)
        YtMusicRepository.rememberVideoVersion(audio.videoId, video)
        val replacement = QueueShuffle.withQueueMetadata(
            audio.asMusicOnlyOf(video).toMediaItem(),
            newCanonicalIndex = item.canonicalIndex,
            newContextCycleId = item.contextCycleId,
        )
        val isCurrent = index == exoPlayer.currentMediaItemIndex
        if (isCurrent && exoPlayer.isPlaying) {
            smoothSwapCurrentTrackVersion(replacement)
            return
        }
        val position = if (isCurrent) exoPlayer.currentPosition else 0L
        exoPlayer.replaceMediaItem(index, replacement)
        if (position > 0L) {
            val mapped = YtMusicRepository.versionTimeMap(video.videoId, audio.videoId)?.map(position) ?: position
            exoPlayer.seekTo(exoPlayer.currentMediaItemIndex, mapped.coerceAtLeast(0L))
        }
    }
}
