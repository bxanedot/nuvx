package com.bxane.nuvx.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@UnstableApi
internal class CrossfadePlayerListener(
    private val readAhead: CrossfadeReadAheadController,
    private val isArming: () -> Boolean,
    private val isFading: () -> Boolean,
    private val handedOff: () -> Boolean,
    private val incoming: () -> ExoPlayer?,
    private val bail: () -> Unit,
    private val ranOutBeforeHandoff: () -> Unit,
    private val handOff: (ExoPlayer, ExoPlayer) -> Unit,
) : Player.Listener {
    private var listeningTo: ExoPlayer? = null

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) bail()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> bail()
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> bail()
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> ranOutBeforeHandoff()
        }
    }

    override fun onPlayerError(error: PlaybackException) = bail()

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
        val held = readAhead.heldAtEnd ?: return
        if (held !== listeningTo) return
        val into = incoming()
        if (isFading() && !handedOff() && into != null) {
            handOff(held, into)
            return
        }
        readAhead.releaseHold()
        if (isArming()) bail()
        held.play()
    }

    fun listenTo(target: ExoPlayer) {
        if (listeningTo === target) return
        listeningTo?.removeListener(this)
        target.addListener(this)
        listeningTo = target
    }

    fun release() {
        listeningTo?.removeListener(this)
        listeningTo = null
    }
}
