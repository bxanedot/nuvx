package com.bxane.nuvx.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.innertube.PlaybackTracker
import com.bxane.nuvx.data.stats.ListeningRecorder
import com.bxane.nuvx.playback.cast.Speakers
import com.bxane.nuvx.wear.WearBridge

@UnstableApi
internal class PlaybackPlayerListener(
    private val context: Context,
    private val player: () -> ExoPlayer?,
    private val audioEffectSession: PlaybackAudioEffectSessionController,
    private val trackSelectedAt: () -> Long?,
    private val clearTrackSelectedAt: () -> Unit,
    private val lastPositionSeconds: () -> Long,
    private val clearLastPositionSeconds: () -> Unit,
    private val errorRecovery: PlaybackErrorRecovery,
    private val registerCurrentPlay: () -> Unit,
    private val prefetchAround: (ExoPlayer) -> Unit,
    private val cancelPrefetch: () -> Unit,
    private val lookForBetterCopy: (ExoPlayer) -> Unit,
    private val savePlaybackState: (ExoPlayer) -> Unit,
    private val publishWidgetState: (Boolean?) -> Unit,
    private val scrobbling: PlaybackScrobblingController,
    private val listenBrainz: PlaybackListenBrainzController,
    private val discordPresence: PlaybackDiscordPresenceController,
    private val partySync: () -> PartySync?,
    private val speakers: () -> Speakers,
    private val directSourceWindow: PlaybackDirectSourceWindowController,
    private val swappingMediaId: () -> String?,
    private val clearSwappingMediaId: () -> Unit,
    private val onTrackBecameCurrent: (MediaItem?, Boolean, Int, Boolean) -> Unit,
    private val autoplay: PlaybackAutoplayController,
    private val loadLyricsForCurrentTrack: () -> Unit,
    private val startLyricsTicker: () -> Unit,
    private val stopLyricsTicker: () -> Unit,
    private val updateLyricSubtitle: () -> Unit,
    private val refreshCustomLayouts: () -> Unit,
    private val boundedQueue: BoundedQueueController,
    private val activeLoudness: () -> LoudnessProcessor,
    private val nextMediaIdOf: (ExoPlayer) -> String?,
    private val musicOnlyController: PlaybackMusicOnlyController,
    private val saveQueueSnapshot: (ExoPlayer) -> Unit,
) : Player.Listener {
    private var lastRepeatMode = Player.REPEAT_MODE_OFF

    fun setLastRepeatMode(repeatMode: Int) {
        lastRepeatMode = repeatMode
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val activePlayer = this.player() ?: return
        if (player === activePlayer) audioEffectSession.update(activePlayer)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val exoPlayer = player() ?: return
        if (isPlaying) {
            trackSelectedAt()?.let {
                TrackLog.d(
                    "nuvx",
                    "TIMING first audio: ${SystemClock.elapsedRealtime() - it}ms since track selected",
                    about = exoPlayer.currentMediaItem?.mediaId,
                )
                clearTrackSelectedAt()
            }
            errorRecovery.onAudible(exoPlayer.currentMediaItem?.mediaId)
        }
        if (isPlaying) registerCurrentPlay()
        if (isPlaying) prefetchAround(exoPlayer) else cancelPrefetch()
        if (isPlaying) lookForBetterCopy(exoPlayer)
        savePlaybackState(exoPlayer)
        publishWidgetState(null)

        val song = exoPlayer.currentMediaItem?.toSong()
        val durationMs = exoPlayer.duration.takeIf { it > 0 }
        scrobbling.onPlayerStateChanged(isPlaying, song, durationMs)

        if (!isPlaying) ListeningRecorder.onStopped()

        if (isPlaying && song != null) {
            listenBrainz.onIsPlayingChanged(song, exoPlayer.currentPosition, durationMs)
        }

        if (isPlaying) {
            discordPresence.push(exoPlayer)
            startLyricsTicker()
        } else {
            discordPresence.clear()
            stopLyricsTicker()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) player()?.let { speakers().onLocalStartedPlaying(it) }
        publishWidgetState(playWhenReady)
        partySync()?.onPlayWhenReadyChanged(playWhenReady, reason)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val exoPlayer = player() ?: return
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            directSourceWindow.onSeek()
            if (exoPlayer.isPlaying) discordPresence.push(exoPlayer)
            updateLyricSubtitle()
            WearBridge.republish(context, exoPlayer)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val exoPlayer = player() ?: return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            mediaItem?.mediaId != null &&
            mediaItem.mediaId == swappingMediaId()
        ) {
            clearSwappingMediaId()
            return
        }

        speakers().onLocalTransition(mediaItem, reason)

        onTrackBecameCurrent(
            mediaItem,
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
            reason,
            false,
        )
        QueueCoordinator.consumePlayedUserQueue(exoPlayer)

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            partySync()?.onLocalIntent()
        }
        autoplay.resetAndLoadForCurrentTrack()
        loadLyricsForCurrentTrack()
        if (exoPlayer.isPlaying) startLyricsTicker()
        refreshCustomLayouts()
    }

    override fun onPlayerError(error: PlaybackException) {
        val exoPlayer = player() ?: return
        errorRecovery.recoverFrom(error, exoPlayer)
    }

    override fun onPlaybackStateChanged(state: Int) {
        val exoPlayer = player() ?: return
        if (state == Player.STATE_ENDED) {
            SleepTimer.cancel()
            PlaybackTracker.onPlaybackFinished(lastPositionSeconds())
            clearLastPositionSeconds()
            listenBrainz.onPlaybackEnded(exoPlayer)
            autoplay.refreshIfQueueEmpty()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        val previous = lastRepeatMode
        lastRepeatMode = repeatMode
        speakers().onLocalRepeatChanged(repeatMode)
        player()?.let { WearBridge.republish(context, it) }
        player()?.let { activeLoudness().nextMediaId = nextMediaIdOf(it) }
        when {
            repeatMode == Player.REPEAT_MODE_ALL -> {
                autoplay.stashAutoplayTracks()
                if (boundedQueue.active) boundedQueue.scheduleTopUp()
            }
            previous == Player.REPEAT_MODE_ALL -> autoplay.restoreAutoplayTracks()
        }
        autoplay.loadForCurrentTrack()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        val exoPlayer = player() ?: return
        musicOnlyController.convertVideosAround(exoPlayer)
        if (exoPlayer.isPlaying) prefetchAround(exoPlayer)
        activeLoudness().nextMediaId = nextMediaIdOf(exoPlayer)
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            speakers().onLocalQueueChanged()
            saveQueueSnapshot(exoPlayer)
            refreshCustomLayouts()
            autoplay.refreshIfQueueEmpty()
        }
    }

}
