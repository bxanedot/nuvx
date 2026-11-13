package com.bxane.nuvx.data.listentogether

import kotlin.math.abs
import kotlin.concurrent.Volatile

































class PartyDriftJudge {








    sealed interface Decision {

        data object None : Decision


        data class Seek(val seekToMs: Long) : Decision


        data object Pause : Decision
    }

    private var strikes = 0

    @Volatile
    private var lastSeekAtMs: Long? = null

    fun reset() {
        strikes = 0
        lastSeekAtMs = null
    }










    fun onTick(
        partyPositionMs: Long,
        localPositionMs: Long,
        localIsPlaying: Boolean,
        partyIsPlaying: Boolean,
        nowMs: Long,
    ): Decision {















        if (!partyIsPlaying) {
            strikes = 0
            return if (localIsPlaying) Decision.Pause else Decision.None
        }

        val gap = partyPositionMs - localPositionMs
        val magnitude = abs(gap)



        if (magnitude <= ALIGN_TOLERANCE_MS) {
            strikes = 0
            return Decision.None
        }

        if (magnitude <= DRIFT_LIMIT_MS) {



            strikes = 0
            return Decision.None
        }

        strikes++
        if (strikes < DRIFT_STRIKES) return Decision.None



        strikes = 0

        val last = lastSeekAtMs
        if (last != null && nowMs - last < DRIFT_COOLDOWN_MS) return Decision.None

        lastSeekAtMs = nowMs


        return Decision.Seek(partyPositionMs.coerceAtLeast(0))
    }

    companion object {

        const val ALIGN_TOLERANCE_MS = 120L


        const val DRIFT_LIMIT_MS = 1_200L


        const val DRIFT_STRIKES = 2


        const val DRIFT_COOLDOWN_MS = 6_000L

    }
}


const val PARTY_TICK_MS = 700L









const val INTENT_QUIET_MS = 4_500L







const val DEFERRED_PLAY_TIMEOUT_MS = 1_800L


fun isFollowing(partyPositionMs: Long, localPositionMs: Long): Boolean =
    abs(partyPositionMs - localPositionMs) <= PartyDriftJudge.ALIGN_TOLERANCE_MS
