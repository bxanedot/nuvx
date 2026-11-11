package com.bxane.nuvx.playback

import android.os.SystemClock
import androidx.media3.common.Player
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.listentogether.PartyPlayback
import kotlin.math.abs

internal class PartyPlaybackDriftController {
    private var alignedSeq = -1L
    private var lastAnchorMs = 0L
    private var lastPositionMs = 0L
    private var lastTrackId: String? = null
    private var lastIsPlaying = false
    private var driftStrikes = 0
    private var driftCooldownUntilMs = 0L

    fun markAligned(sequence: Long) {
        alignedSeq = sequence
    }

    fun resetPlaybackAnchor() {
        lastAnchorMs = 0L
        lastPositionMs = 0L
        lastTrackId = null
        lastIsPlaying = false
    }

    fun reconcile(player: Player, target: PartyPlayback, trackId: String, want: Long) {
        if (player.playbackState != Player.STATE_READY) {
            driftStrikes = 0
            return
        }

        val drift = player.currentPosition - want
        if (target.seq != alignedSeq) {
            val isPlaybackAnchorChanged = lastAnchorMs != target.anchorMs ||
                lastPositionMs != target.positionMs ||
                lastTrackId != trackId ||
                lastIsPlaying != target.isPlaying

            alignedSeq = target.seq
            lastAnchorMs = target.anchorMs
            lastPositionMs = target.positionMs
            lastTrackId = trackId
            lastIsPlaying = target.isPlaying

            if (isPlaybackAnchorChanged && abs(drift) > ALIGN_TOLERANCE_MS) {
                Log.i(TAG, "aligning ${drift}ms onto party control ${target.seq}")
                player.seekTo(want)
                return
            }
        }
        if (abs(drift) <= DRIFT_LIMIT_MS) {
            driftStrikes = 0
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < driftCooldownUntilMs) return
        if (++driftStrikes < DRIFT_STRIKES) return
        Log.i(TAG, "correcting ${drift}ms of drift against the party")
        driftStrikes = 0
        driftCooldownUntilMs = now + DRIFT_COOLDOWN_MS
        player.seekTo(want)
    }

    private companion object {
        const val TAG = "PartySync"
        const val DRIFT_LIMIT_MS = 1_200L
        const val DRIFT_STRIKES = 2
        const val DRIFT_COOLDOWN_MS = 6_000L
        const val ALIGN_TOLERANCE_MS = 120L
    }
}
