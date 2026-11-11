package com.bxane.nuvx.playback

import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.playback.smart.TrackAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

internal class CrossfadeSpeedController(
    private val scope: CoroutineScope,
    private val active: () -> ExoPlayer,
    private val isIdle: () -> Boolean,
) {
    private var easing: ExoPlayer? = null
    private var easeRate = 1.0
    private var easeStep = 0.0
    private var easeStepMs = 0L
    private var easeNextAtMs = 0L
    private var easeItemIndex = 0
    private var softSpeedJob: Job? = null
    private var outgoingRampRate = 1.0
    private var outgoingRampSteps = 0
    private var outgoingRampStepMs = 0L
    private var outgoingRampStartMs = 0L
    private var outgoingRampStep = 0

    val isEasing: Boolean get() = easing != null
    val hasOutgoingRamp: Boolean get() = outgoingRampSteps > 0
    val outgoingRampIncomplete: Boolean get() = outgoingRampSteps > 0 && outgoingRampStep < outgoingRampSteps

    fun beginEase(player: ExoPlayer, rate: Double, analysis: TrackAnalysis) {
        val stretch = rate - 1.0
        if (abs(stretch) < EASE_DONE) {
            player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
            return
        }
        val beat = when {
            analysis.beatInterval > 0.0 -> analysis.beatInterval
            analysis.bpm > 0.0 -> 60.0 / analysis.bpm
            else -> 0.0
        }
        val steps = rampStepsFor(rate)
        easeStep = stretch / steps
        easeRate = rate
        easeStepMs = if (beat > 0.0) (beat * 1000).roundToLong() else EASE_FALLBACK_STEP_MS
        easeItemIndex = player.currentMediaItemIndex
        easeNextAtMs = player.currentPosition + easeStepMs
        easing = player
        player.setPlaybackSpeed((AppSettings.playbackSpeed.value * rate).toFloat())
    }

    fun driveEase() {
        val player = easing ?: return
        val position = player.currentPosition
        if (player !== active() ||
            player.currentMediaItemIndex != easeItemIndex ||
            position < easeNextAtMs - 2 * easeStepMs ||
            ListenTogether.state.value.inParty
        ) {
            endEase()
            return
        }
        if (position < easeNextAtMs) return
        easeRate -= easeStep
        if (abs(easeRate - 1.0) < EASE_DONE) {
            endEase(clickless = false)
            return
        }
        player.setPlaybackSpeed((AppSettings.playbackSpeed.value * easeRate).toFloat())
        easeNextAtMs += easeStepMs
    }

    fun rampStepsFor(rate: Double): Int {
        val stretch = abs(rate - 1.0)
        return if (stretch < EASE_DONE) 0 else ceil(stretch / TEMPO_STEP_PER_BEAT).toInt()
    }

    fun beginOutgoingRamp(rate: Double, beatMs: Long, endMs: Long, fadeMs: Long): Int {
        outgoingRampRate = rate
        outgoingRampSteps = if (beatMs > 0L) rampStepsFor(rate) else 0
        outgoingRampStepMs = beatMs
        outgoingRampStartMs = endMs - fadeMs - outgoingRampSteps * beatMs
        outgoingRampStep = 0
        return outgoingRampSteps
    }

    fun rampOutgoing(player: ExoPlayer) {
        val due = ((player.currentPosition - outgoingRampStartMs) / outgoingRampStepMs + 1)
            .toInt()
            .coerceIn(0, outgoingRampSteps)
        if (due <= outgoingRampStep) return
        outgoingRampStep = due
        val rate = 1.0 + (outgoingRampRate - 1.0) * due / outgoingRampSteps
        player.setPlaybackSpeed((AppSettings.playbackSpeed.value * rate).toFloat())
    }

    fun finishOutgoingRamp(player: ExoPlayer) {
        outgoingRampStep = outgoingRampSteps
        player.setPlaybackSpeed((AppSettings.playbackSpeed.value * outgoingRampRate).toFloat())
    }

    fun clearOutgoingRamp() {
        outgoingRampSteps = 0
    }

    fun endEase(clickless: Boolean = true) {
        val player = easing ?: return
        easing = null
        if (clickless) {
            setPlaybackSpeedClickless(player, AppSettings.playbackSpeed.value)
        } else {
            softSpeedJob?.cancel()
            softSpeedJob = null
            player.volume = 1f
            player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        }
    }

    private fun setPlaybackSpeedClickless(player: ExoPlayer, target: Float) {
        if (abs(player.playbackParameters.speed - target) < SPEED_CHANGE_EPSILON) return
        if (!player.isPlaying || player !== active() || player.volume <= 0f) {
            player.setPlaybackSpeed(target)
            return
        }
        softSpeedJob?.cancel()
        softSpeedJob = scope.launch {
            val level = player.volume
            try {
                for (step in 1..SPEED_NOTCH_STEPS) {
                    player.volume = level * (1f - step.toFloat() / SPEED_NOTCH_STEPS)
                    delay(SPEED_NOTCH_STEP_MS)
                }
                player.setPlaybackSpeed(target)
                delay(SPEED_NOTCH_HOLD_MS)
                for (step in 1..SPEED_NOTCH_STEPS) {
                    player.volume = level * step.toFloat() / SPEED_NOTCH_STEPS
                    delay(SPEED_NOTCH_STEP_MS)
                }
            } finally {
                if (player === active() && isIdle()) player.volume = level
            }
        }
    }

    private companion object {
        const val TEMPO_STEP_PER_BEAT = 0.0075
        const val EASE_DONE = 1e-4
        const val EASE_FALLBACK_STEP_MS = 500L
        const val SPEED_NOTCH_STEPS = 2
        const val SPEED_NOTCH_STEP_MS = 2L
        const val SPEED_NOTCH_HOLD_MS = 2L
        const val SPEED_CHANGE_EPSILON = 0.0001f
    }
}
