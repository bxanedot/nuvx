package com.bxane.nuvx.playback

import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.MixBlend
import com.bxane.nuvx.playback.smart.TrackAnalysis

internal class CrossfadeBlendPublisher(
    private val phaseLock: CrossfadePhaseLockController,
) {
    fun publish(
        out: ExoPlayer,
        into: ExoPlayer,
        smartFadeActive: Boolean,
        handedOff: Boolean,
        outgoingAnalysis: TrackAnalysis,
        incomingAnalysis: TrackAnalysis,
        positionMs: (ExoPlayer) -> Double,
        force: Boolean = false,
    ) {
        if (!smartFadeActive) return
        val session = if (handedOff) into else out
        val playing = session.isPlaying
        val last = AppSettings.smartMixBlend.value
        if (!force && last != null && !playing && !last.playing) return

        val sides = if (handedOff) {
            listOf(incomingAnalysis to into, outgoingAnalysis to out)
        } else {
            listOf(outgoingAnalysis to out, incomingAnalysis to into)
        }
        var beatMs = 0f
        var anchor = 0L
        for ((analysis, player) in sides) {
            if (!phaseLock.beatAt(analysis, positionMs(player) / 1000.0)) continue
            val rate = player.playbackParameters.speed.toDouble().takeIf { it > 0.0 } ?: 1.0
            val sinceBeat = phaseLock.beatOffsetSeconds
            beatMs = (phaseLock.beatIntervalSeconds * 1000.0 / rate).toFloat()
            anchor = System.nanoTime() - (sinceBeat / rate * 1e9).toLong()
            break
        }
        if (!force && last != null && last.playing == playing && CrossfadeGeometry.sameGrid(last, beatMs, anchor)) return
        AppSettings.smartMixBlend.value = MixBlend(
            beatMs = beatMs,
            beatAnchorNanos = anchor,
            playing = playing,
        )
    }
}
