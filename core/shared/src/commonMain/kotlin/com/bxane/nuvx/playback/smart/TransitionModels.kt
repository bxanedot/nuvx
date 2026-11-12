// SPDX-License-Identifier: AGPL-3.0-or-later

package com.bxane.nuvx.playback.smart

import kotlin.math.roundToLong

enum class CrossfadeMode { STANDARD, SMART }

data class TransitionTrackInfo(
    val id: String,
    val durationMs: Long,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
)

enum class TransitionStyle {
    EQUAL_POWER,

    GAPLESS,

    DJ_BLEND,

    DJ_FILTER,
}

enum class FilterTransitionVariant {
    SWEEP,

    BASS_HANDOFF,

    ECHO_RIDE,
}

data class TransitionPlan(
    val shouldStart: Boolean = false,
    val markerVisible: Boolean = false,
    val blocked: Boolean = false,
    val reason: String = "",
    val transitionStart: Double = 0.0,
    val transitionEnd: Double = 0.0,
    val fadeSeconds: Double = 0.0,
    val transitionStyle: TransitionStyle = TransitionStyle.EQUAL_POWER,
    val incomingCueTime: Double = 0.0,
    val incomingHandoffTime: Double = 0.0,
    val incomingPlaybackRate: Double = 1.0,
    val outgoingPlaybackRate: Double = 1.0,
    val handoffStartSeconds: Double = 0.0,
    val handoffDuration: Double = 0.0,
    val pickupSeconds: Double = 0.0,
    val transitionBeats: Int = 0,
    val bassSwap: Boolean = false,
    val handoffFraction: Double = HANDOFF_FRACTION,
    val bedPosition: Double = BED_POSITION,
    val bassSwapFraction: Double = 0.7,
    val filterSweep: Double = 0.0,
    val filterVariant: FilterTransitionVariant = FilterTransitionVariant.SWEEP,
    val vocalOverlap: Double = 0.0,
    val outgoingBpm: Double = 0.0,
    val incomingBpm: Double = 0.0,
    val beatSeconds: Double = 0.0,
    val phaseLock: Boolean = false,
    val echoSeconds: Double = 0.0,
    val policyReasons: List<String> = emptyList(),
) {
    val fadeMs: Long get() = (fadeSeconds * 1000).roundToLong()
}
