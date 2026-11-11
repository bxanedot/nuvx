package com.bxane.nuvx.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal class PlaybackAudioEffectSessionController(
    private val context: Context,
) {
    private var advertisedSessionId = C.AUDIO_SESSION_ID_UNSET

    fun update(activePlayer: ExoPlayer) {
        val sessionId = activePlayer.audioSessionId
        val shouldAdvertise = activePlayer.playWhenReady &&
            (activePlayer.playbackState == Player.STATE_BUFFERING ||
                activePlayer.playbackState == Player.STATE_READY) &&
            sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0

        if (!shouldAdvertise) {
            close()
            return
        }
        if (advertisedSessionId == sessionId) return

        close()
        advertisedSessionId = sessionId
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    fun close() {
        val sessionId = advertisedSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

        advertisedSessionId = C.AUDIO_SESSION_ID_UNSET
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            },
        )
    }
}
