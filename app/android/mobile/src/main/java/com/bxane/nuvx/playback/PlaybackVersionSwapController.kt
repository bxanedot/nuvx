package com.bxane.nuvx.playback

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.innertube.PlaybackTracker
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.playback.smart.VersionAudioAligner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class PlaybackVersionSwapController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val spare: () -> ExoPlayer?,
    private val crossfade: () -> CrossfadeController?,
    private val versionAlignment: PlaybackVersionAlignmentController,
    private val loudnessA: LoudnessProcessor,
    private val loudnessB: LoudnessProcessor,
    private val spareLoudness: () -> LoudnessProcessor,
    private val nextMediaIdOf: (ExoPlayer) -> String?,
    private val onSwapping: (String?) -> Unit,
    private val adoptPlayerForVersionSwap: (ExoPlayer, ExoPlayer) -> Unit,
    private val onTrackBecameCurrent: (MediaItem?, Boolean, Int, Boolean) -> Unit,
    private val refreshCustomLayouts: () -> Unit,
) {
    private var versionSwapJob: Job? = null
    private var versionSwapGeneration = 0

    fun isActive(): Boolean = versionSwapJob?.isActive == true

    fun smoothSwapCurrentTrackVersion(
        targetMediaItem: MediaItem,
        onSwapCommitted: (() -> Unit)? = null,
    ) {
        val activePlayer = player() ?: return
        val currentIndex = activePlayer.currentMediaItemIndex
        if (currentIndex !in 0 until activePlayer.mediaItemCount) return
        val currentItem = activePlayer.currentMediaItem ?: return
        if (ListenTogether.state.value.inParty) {
            TrackLog.d("nuvx", "version swap ignored: in a Listen Together party", about = currentItem.mediaId)
            return
        }
        if (crossfade()?.isTransitioning() == true) {
            TrackLog.d("nuvx", "version swap deferred: Automix is mid-transition", about = currentItem.mediaId)
            return
        }

        val sourceSong = currentItem.toSong()
        val targetSong = targetMediaItem.toSong()
        val officialMap = YtMusicRepository.versionTimeMap(sourceSong.videoId, targetSong.videoId)
        val measureAlignment = officialMap == null && AppSettings.smartVersionAlignment.value
        val holdsSwitchBar = measureAlignment || officialMap != null

        versionSwapJob?.cancel()
        val generation = ++versionSwapGeneration
        versionSwapJob = scope.launch(Dispatchers.Main) {
            val mediaId = targetMediaItem.mediaId
            onSwapping(mediaId)
            if (holdsSwitchBar) {
                AppSettings.versionAlignmentInProgress.value = true
            }
            try {
                val startPosition = activePlayer.currentPosition
                val alignmentOffsetMs = if (measureAlignment) {
                    versionAlignment.prepareVersionAlignment(sourceSong, targetSong, startPosition)
                } else {
                    0L
                }
                val alignedPosition: (Long) -> Long = if (officialMap != null) {
                    { pos -> officialMap.map(pos).coerceAtLeast(0L) }
                } else {
                    { pos -> (pos + alignmentOffsetMs).coerceAtLeast(0L) }
                }
                if (officialMap != null) {
                    val mapped = alignedPosition(startPosition)
                    VersionAudioAligner.publishPhase(
                        VersionAudioAligner.beginAttempt(sourceSong.videoId, targetSong.videoId),
                        VersionAudioAligner.CutPhase.ALIGNED,
                        mapped - startPosition,
                    )
                    TrackLog.d(
                        "nuvx",
                        "version swap using YouTube's time map: ${startPosition}ms -> ${mapped}ms",
                        about = sourceSong.videoId,
                    )
                }

                if (player() !== activePlayer ||
                    activePlayer.currentMediaItemIndex != currentIndex ||
                    activePlayer.currentMediaItem?.mediaId != sourceSong.videoId
                ) {
                    onSwapping(null)
                    return@launch
                }

                val standbyPlayer = spare()
                val canCrossfade = standbyPlayer != null &&
                    (activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_READY)

                if (!canCrossfade || standbyPlayer == null) {
                    val pos = alignedPosition(activePlayer.currentPosition)
                    val wasPlaying = activePlayer.playWhenReady
                    activePlayer.replaceMediaItem(currentIndex, targetMediaItem)
                    activePlayer.seekTo(currentIndex, pos)
                    if (wasPlaying) activePlayer.play()
                    onSwapCommitted?.invoke()
                    refreshCustomLayouts()
                    onSwapping(null)
                    return@launch
                }

                val newItems = (0 until activePlayer.mediaItemCount).map { i ->
                    if (i == currentIndex) targetMediaItem else activePlayer.getMediaItemAt(i)
                }
                val wasPlaying = activePlayer.playWhenReady
                val alignedStartPos = alignedPosition(activePlayer.currentPosition)

                standbyPlayer.stop()
                standbyPlayer.clearMediaItems()
                standbyPlayer.skipSilenceEnabled = activePlayer.skipSilenceEnabled
                standbyPlayer.repeatMode = activePlayer.repeatMode
                standbyPlayer.shuffleModeEnabled = activePlayer.shuffleModeEnabled
                standbyPlayer.setPlaybackSpeed(AppSettings.playbackSpeed.value)
                standbyPlayer.volume = 0f
                standbyPlayer.setMediaItems(newItems, currentIndex, alignedStartPos)
                spareLoudness().track(mediaId, nextMediaIdOf(standbyPlayer))
                standbyPlayer.playWhenReady = false
                standbyPlayer.prepare()

                val timeoutAt = SystemClock.elapsedRealtime() + 7_000L
                var isReady = false
                while (isActive && SystemClock.elapsedRealtime() < timeoutAt) {
                    if (player() !== activePlayer ||
                        activePlayer.currentMediaItemIndex != currentIndex
                    ) {
                        standbyPlayer.stop()
                        standbyPlayer.clearMediaItems()
                        standbyPlayer.volume = 1f
                        onSwapping(null)
                        return@launch
                    }

                    if (standbyPlayer.playbackState == Player.STATE_READY) {
                        isReady = true
                        break
                    }
                    if (standbyPlayer.playbackState == Player.STATE_IDLE && standbyPlayer.playerError != null) {
                        break
                    }
                    delay(20)
                }

                if (!isReady) {
                    TrackLog.w("nuvx", "version swap standby player failed to prepare, applying directly", about = mediaId)
                    standbyPlayer.stop()
                    standbyPlayer.clearMediaItems()
                    standbyPlayer.volume = 1f
                    onSwapping(null)
                    val pos = alignedPosition(activePlayer.currentPosition)
                    activePlayer.replaceMediaItem(currentIndex, targetMediaItem)
                    activePlayer.seekTo(currentIndex, pos)
                    if (wasPlaying) activePlayer.play()
                    onSwapCommitted?.invoke()
                    onTrackBecameCurrent(
                        targetMediaItem,
                        false,
                        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                        true,
                    )
                    refreshCustomLayouts()
                    return@launch
                }

                standbyPlayer.seekTo(currentIndex, alignedPosition(activePlayer.currentPosition))

                val willPlay = wasPlaying && activePlayer.playWhenReady
                if (willPlay) {
                    standbyPlayer.play()
                    val runningTimeout = SystemClock.elapsedRealtime() + 600L
                    while (isActive && !standbyPlayer.isPlaying && SystemClock.elapsedRealtime() < runningTimeout) {
                        delay(10)
                    }
                    if (standbyPlayer.isPlaying) {
                        standbyPlayer.seekTo(currentIndex, alignedPosition(activePlayer.currentPosition))
                        val resyncTimeout = SystemClock.elapsedRealtime() + 300L
                        while (isActive && standbyPlayer.playbackState != Player.STATE_READY && SystemClock.elapsedRealtime() < resyncTimeout) {
                            delay(10)
                        }
                    }
                } else {
                    val seekTimeout = SystemClock.elapsedRealtime() + 400L
                    while (isActive && standbyPlayer.playbackState != Player.STATE_READY && SystemClock.elapsedRealtime() < seekTimeout) {
                        delay(10)
                    }
                }

                val swapCrossfadeMs = 550L
                val fadeStart = SystemClock.elapsedRealtime()
                if (!holdsSwitchBar) {
                    AppSettings.smartMixInProgress.value = true
                }

                try {
                    while (isActive) {
                        val elapsed = SystemClock.elapsedRealtime() - fadeStart
                        val progress = (elapsed.toFloat() / swapCrossfadeMs).coerceIn(0f, 1f)

                        if (player() !== activePlayer || activePlayer.currentMediaItemIndex != currentIndex) {
                            standbyPlayer.stop()
                            standbyPlayer.clearMediaItems()
                            standbyPlayer.volume = 1f
                            activePlayer.volume = 1f
                            onSwapping(null)
                            return@launch
                        }

                        if (standbyPlayer.playWhenReady != activePlayer.playWhenReady) {
                            standbyPlayer.playWhenReady = activePlayer.playWhenReady
                        }

                        val inGain = sin(progress * (PI / 2.0)).toFloat()
                        val outGain = cos(progress * (PI / 2.0)).toFloat()
                        standbyPlayer.volume = inGain
                        activePlayer.volume = outGain
                        val swapTrim = 1f / sqrt((inGain + outGain).coerceAtLeast(1f))
                        loudnessA.setBlendTrim(swapTrim)
                        loudnessB.setBlendTrim(swapTrim)

                        if (progress >= 1f) break
                        delay(16)
                    }

                    adoptPlayerForVersionSwap(activePlayer, standbyPlayer)
                    onSwapCommitted?.invoke()
                } finally {
                    loudnessA.setBlendTrim(1f)
                    loudnessB.setBlendTrim(1f)
                    if (!holdsSwitchBar) {
                        AppSettings.smartMixInProgress.value = false
                    }
                }
            } finally {
                if (generation == versionSwapGeneration) {
                    AppSettings.versionAlignmentInProgress.value = false
                }
            }
        }
    }

    fun revertCurrentToOriginal() {
        val activePlayer = player() ?: return
        val index = activePlayer.currentMediaItemIndex
        if (index !in 0 until activePlayer.mediaItemCount) return
        val song = activePlayer.currentMediaItem?.toSong() ?: return
        if (!song.hasYouTubeOriginal() ||
            song.localUri != null ||
            OriginalVersion.isPinned(song.videoId)
        ) {
            return
        }

        OriginalVersion.pin(song.videoId)
        val position = activePlayer.currentPosition
        val wasPlaying = activePlayer.isPlaying
        onSwapping(song.videoId)
        activePlayer.replaceMediaItem(index, song.toDirectYouTubeMediaItem())
        activePlayer.seekTo(index, position)
        if (wasPlaying) activePlayer.play()
        refreshCustomLayouts()
    }

    fun swapCurrentToVersion(args: Bundle) {
        val activePlayer = player() ?: return
        val index = activePlayer.currentMediaItemIndex
        if (index !in 0 until activePlayer.mediaItemCount) return
        val song = activePlayer.currentMediaItem?.toSong() ?: return

        val targetVideoId = args.getString("targetVideoId")
        val targetIsVideo = args.getBoolean("targetIsVideo", false)

        if (targetVideoId == null || targetVideoId == song.videoId) return

        val position = activePlayer.currentPosition
        val wasPlaying = activePlayer.isPlaying
        onSwapping(targetVideoId)
        PlaybackTracker.onVersionSwapped(song.videoId, targetVideoId)

        val targetSong = song.copy(
            videoId = targetVideoId,
            isVideo = targetIsVideo,
            isVideoOrigin = if (targetIsVideo) false else song.isVideoOrigin,
        )

        activePlayer.replaceMediaItem(index, targetSong.toMediaItem())
        activePlayer.seekTo(index, position)
        if (wasPlaying) activePlayer.play()
        refreshCustomLayouts()
    }
}
