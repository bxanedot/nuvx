package com.licht.nuvx.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test
    fun retriesTwiceWithGrowingDelayThenGivesUp() {
        assertFalse(RecoveryPolicy.givesUp(1, null))
        assertFalse(RecoveryPolicy.givesUp(2, null))
        assertTrue(RecoveryPolicy.givesUp(3, null))
        assertEquals(350L, RecoveryPolicy.retryDelayMs(1))
        assertEquals(700L, RecoveryPolicy.retryDelayMs(2))
    }

    @Test
    fun staleRetryGuardRejectsTrackChangesSourceChangesAndUserPause() {
        assertTrue(RecoveryPolicy.retryStillApplicable(
            "song1", "https://source/a", "song1", "https://source/a", true,
        ))
        assertFalse(RecoveryPolicy.retryStillApplicable(
            "song1", "https://source/a", "song2", "https://source/a", true,
        ))
        assertFalse(RecoveryPolicy.retryStillApplicable(
            "song1", "https://source/a", "song1", "https://source/b", true,
        ))
        assertFalse(RecoveryPolicy.retryStillApplicable(
            "song1", "https://source/a", "song1", "https://source/a", false,
        ))
        assertFalse(RecoveryPolicy.retryStillApplicable(
            "song1", null, null, null, true,
        ))
    }

    @Test
    fun jitterStaysBoundedAndDeterministicOverBaseBackoff() {
        for (attempt in 1..RecoveryPolicy.MAX_RECOVERIES) {
            val base = RecoveryPolicy.retryDelayMs(attempt)
            val jittered = RecoveryPolicy.retryDelayWithJitterMs(attempt, "song")
            assertTrue(jittered >= base)
            assertTrue(jittered <= base + 100L)
            assertEquals(jittered, RecoveryPolicy.retryDelayWithJitterMs(attempt, "song"))
        }
    }

    @Test
    fun aPermanentFailureIsNeverRetried() {
        assertTrue(RecoveryPolicy.givesUp(1, "removed"))
        assertEquals("removed", RecoveryPolicy.skipReason("removed", "E", neverStarted = false, attempts = 1, skipsInARow = 9))
    }

    @Test
    fun skipsOnlyTracksThatNeverStartedAndStopsAfterFourInARow() {
        assertNull(RecoveryPolicy.skipReason(null, "E", neverStarted = false, attempts = 3, skipsInARow = 0))
        assertEquals(
            "failed to start after 3 attempts (E)",
            RecoveryPolicy.skipReason(null, "E", neverStarted = true, attempts = 3, skipsInARow = 3),
        )
        assertNull(RecoveryPolicy.skipReason(null, "E", neverStarted = true, attempts = 3, skipsInARow = 4))
    }

    @Test
    fun anUnresolvableGooglevideoHostIsRecognisedThroughWrappedCauses() {
        val dns = java.net.UnknownHostException("Unable to resolve host \"rr1---sn-4g5e6nzl.googlevideo.com\": No address associated with hostname")
        assertTrue(RecoveryPolicy.streamHostUnresolved(java.io.IOException("source error", java.io.IOException("open", dns))))
        assertFalse(RecoveryPolicy.streamHostUnresolved(java.net.UnknownHostException("Unable to resolve host \"music.youtube.com\"")))
        assertFalse(RecoveryPolicy.streamHostUnresolved(java.net.SocketTimeoutException("googlevideo.com timed out")))
        assertFalse(RecoveryPolicy.streamHostUnresolved(null))
    }

    @Test
    fun `agrees with the shared stream recovery cases`() {
        // The same cases the desktop's src/recovery.rs is tested against.
        val file = java.io.File("../../../core/contracts/stream-recovery.json").takeIf { it.isFile }
            ?: java.io.File("core/contracts/stream-recovery.json")
        val cases = (kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject)["cases"]
            as kotlinx.serialization.json.JsonArray
        for (case in cases) {
            val c = case as kotlinx.serialization.json.JsonObject
            fun v(k: String) = (c[k] as kotlinx.serialization.json.JsonPrimitive).content
            val attempts = v("attempts").toInt()
            val permanent = if (v("permanent").toBoolean()) "permanent" else null
            val got = if (!RecoveryPolicy.givesUp(attempts, permanent)) {
                "retry:" + RecoveryPolicy.retryDelayMs(attempts)
            } else if (RecoveryPolicy.skipReason(permanent, "ERROR", v("neverStarted").toBoolean(), attempts, v("skipsInARow").toInt()) != null) {
                "skip"
            } else {
                "stay"
            }
            val want = if (v("decision") == "retry") "retry:" + v("delayMs") else v("decision")
            org.junit.Assert.assertEquals(v("name"), want, got)
        }
    }
}
