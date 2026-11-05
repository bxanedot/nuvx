@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bxane.nuvx

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.core.CrossfadeAudio
import com.bxane.nuvx.core.MediaKind
import com.bxane.nuvx.core.RepeatMode
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** One queue stays visible to the session; the second deck supplies only the overlap audio. */
internal class AndroidCrossfadePlayer(
    private val primary: ExoPlayer,
    private val standby: ExoPlayer,
    private val crossfadeAvailable: Boolean,
) : ForwardingSimpleBasePlayer(primary), AutoCloseable {
    private data class Preload(val key: String, val currentIndex: Int, val nextIndex: Int, val startMs: Long, val durationMs: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var seconds = 0
    private var masterVolume = primary.volume
    private var preload: Preload? = null
    private var transition: Preload? = null
    private var finalizing = false
    private var progress = 0f
    private val loop = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = cancel(clearPreload = true)
        override fun onRepeatModeChanged(repeatMode: Int) = cancel(clearPreload = true)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = cancel(clearPreload = true)
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            if (preload != null) standby.playbackParameters = playbackParameters
        }
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (!finalizing && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) cancel(clearPreload = true)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!finalizing) cancel(clearPreload = true)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (transition != null || finalizing) syncStandby()
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = cancel(clearPreload = true)
    }

    init {
        primary.addListener(listener)
        standby.volume = 0f
        handler.post(loop)
    }

    fun setCrossfadeSeconds(value: Int) {
        val normalized = CrossfadeAudio.normalizeDurationSeconds(value)
        if (normalized == seconds) return
        seconds = normalized
        cancel(clearPreload = true)
    }

    override fun getState(): State = super.getState().buildUpon().setVolume(masterVolume).build()

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        masterVolume = volume.coerceIn(0f, 1f)
        applyVolumes()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    private fun tick() {
        if (!crossfadeAvailable || seconds == 0) return
        val active = transition
        if (active != null) {
            if (primary.currentMediaItemIndex != active.currentIndex && !finalizing) {
                cancel(clearPreload = true)
                return
            }
            if (finalizing) {
                if (!primary.playWhenReady || primary.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    standby.pause()
                } else {
                    standby.play()
                }
                if (primary.currentMediaItemIndex == active.nextIndex && primary.isPlaying) completeTransition()
                return
            }
            if (!primary.playWhenReady || primary.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || !primary.isPlaying) {
                standby.pause()
                return
            }
            standby.play()
            progress = AndroidCrossfadePolicy.progress(primary.currentPosition, active.startMs, active.durationMs)
            applyVolumes()
            if (progress >= 1f) finishTransition(active)
            return
        }
        val candidate = prepareNext() ?: return
        if (primary.isPlaying && primary.currentPosition >= candidate.startMs && standby.playbackState == Player.STATE_READY) {
            transition = candidate
            progress = 0f
            standby.seekTo(0)
            standby.play()
            applyVolumes()
        }
    }

    private fun prepareNext(): Preload? {
        if (primary.mediaItemCount < 2 || primary.currentMediaItemIndex !in 0 until primary.mediaItemCount) {
            cancel(clearPreload = true)
            return null
        }
        val currentIndex = primary.currentMediaItemIndex
        val current = primary.currentMediaItem ?: return null
        val repeat = when (primary.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        val nextIndex = AndroidCrossfadePolicy.nextTrackIndex(currentIndex, primary.mediaItemCount, repeat) ?: run {
            cancel(clearPreload = true)
            return null
        }
        val incoming = primary.getMediaItemAt(nextIndex)
        val outgoingKind = mediaKind(current)
        val incomingKind = mediaKind(incoming)
        if (!AndroidCrossfadePolicy.isEligible(outgoingKind, incomingKind, repeat)) {
            cancel(clearPreload = true)
            return null
        }
        val incomingDuration = incoming.mediaMetadata.extras?.getString("nuvx.durationMs")?.toLongOrNull() ?: 0L
        val duration = CrossfadeAudio.transitionDurationMs(seconds, primary.duration, incomingDuration)
        if (duration <= 0L) {
            cancel(clearPreload = true)
            return null
        }
        val key = "$currentIndex:${current.mediaId}:$nextIndex:${incoming.mediaId}:$duration"
        if (preload?.key != key) {
            val candidate = Preload(key, currentIndex, nextIndex, primary.duration - duration, duration)
            if (primary.currentPosition < candidate.startMs - PRELOAD_LEAD_MS) return null
            cancel(clearPreload = true)
            preload = candidate
            standby.volume = 0f
            standby.playbackParameters = primary.playbackParameters
            standby.setMediaItem(incoming)
            standby.prepare()
        }
        return preload
    }

    private fun finishTransition(active: Preload) {
        finalizing = true
        progress = 1f
        applyVolumes()
        primary.seekTo(active.nextIndex, standby.currentPosition.coerceAtLeast(0L))
        primary.play()
    }

    private fun completeTransition() {
        finalizing = false
        transition = null
        preload = null
        progress = 0f
        standby.pause()
        standby.volume = 0f
        standby.clearMediaItems()
        primary.volume = masterVolume
    }

    private fun syncStandby() {
        if (!primary.playWhenReady || primary.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) standby.pause()
        else if (primary.isPlaying || finalizing) standby.play()
    }

    private fun applyVolumes() {
        val levels = CrossfadeAudio.levels(progress)
        primary.volume = masterVolume * levels.outgoing
        standby.volume = if (transition == null) 0f else masterVolume * levels.incoming
    }

    private fun cancel(clearPreload: Boolean) {
        val hadPreload = preload != null || standby.mediaItemCount > 0
        if (transition == null && !finalizing && !hadPreload && primary.volume == masterVolume && standby.volume == 0f) return
        transition = null
        finalizing = false
        progress = 0f
        standby.pause()
        standby.volume = 0f
        primary.volume = masterVolume
        if (clearPreload && hadPreload) {
            preload = null
            standby.clearMediaItems()
        }
    }

    private fun mediaKind(item: MediaItem): MediaKind =
        item.mediaMetadata.extras?.getString("nuvx.mediaKind")?.let { value -> MediaKind.entries.firstOrNull { it.name == value } }
            ?: MediaKind.SONG

    override fun close() {
        handler.removeCallbacks(loop)
        primary.removeListener(listener)
        cancel(clearPreload = true)
    }

    private companion object {
        const val TICK_MS = 40L
        const val PRELOAD_LEAD_MS = 20_000L
    }
}
