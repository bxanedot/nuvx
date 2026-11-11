package com.bxane.nuvx.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal class CrossfadeSleepFadeController(
    private val active: () -> ExoPlayer,
    private val outgoing: () -> ExoPlayer?,
    private val envelope: CrossfadeEnvelope,
    private val onStart: (ExoPlayer, Long) -> Unit,
    private val onFinish: () -> Unit,
) {
    private var fadeMs = 0L
    private var startPositionMs = 0L
    private var startGain = 1f

    fun begin(fade: Long) {
        val out = active()
        fadeMs = fade.coerceAtLeast(1L)
        startPositionMs = out.currentPosition
        startGain = out.volume
        onStart(out, fade)
    }

    fun drive() {
        val out = outgoing() ?: return finish()
        if (!SleepTimer.afterTrack.value) {
            out.volume = 1f
            onFinish()
            return
        }
        val elapsed = (out.currentPosition - startPositionMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
        out.volume = startGain * envelope.fallGain(progress)
        val done = progress >= 1f ||
            out.playbackState == Player.STATE_ENDED ||
            out.playbackState == Player.STATE_IDLE
        if (done) finish()
    }

    private fun finish() {
        outgoing()?.pause()
        outgoing()?.volume = 1f
        SleepTimer.cancel()
        onFinish()
    }
}
