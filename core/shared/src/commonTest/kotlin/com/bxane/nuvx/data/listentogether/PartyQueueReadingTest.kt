package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals

class PartyQueueReadingTest {

    private fun queue(vararg ids: String, seq: Long = 4, index: Int = -1) =
        PartyQueue(seq = seq, index = index, items = ids.map { PartyTrack(it) })

    @Test
    fun theStateFramesIndexWinsWhenItNamesTheTrack() {
        val q = queue("x", "a", "x", "b", index = 0)
        val playback = PartyPlayback(queueSeq = 4, queueIndex = 2)

        assertEquals(2, partyQueueIndexOf(q, playback, "x"))
        assertEquals(listOf("b"), partyUpcomingAfter(q, playback, "x").map { it.videoId })
    }

    @Test
    fun aStaleQueueIndexIsNotTakenFromAnOlderQueue() {
        val q = queue("x", "a", "x", "b", seq = 3, index = 2)
        val playback = PartyPlayback(queueSeq = 4, queueIndex = 0)

        assertEquals(2, partyQueueIndexOf(q, playback, "x"))
    }

    @Test
    fun theCopyAheadOfTheNeedleBeatsTheOneBehindIt() {
        val q = queue("b", "a", "x", "b", index = 2)
        val playback = PartyPlayback(queueSeq = 3, queueIndex = 2)

        assertEquals(3, partyQueueIndexOf(q, playback, "b"))
    }

    @Test
    fun aTrackTheQueueDoesNotHoldHasNothingAfterIt() {
        val q = queue("a", "b")

        assertEquals(-1, partyQueueIndexOf(q, PartyPlayback(), "z"))
        assertEquals(emptyList(), partyUpcomingAfter(q, PartyPlayback(), "z"))
        assertEquals(-1, partyQueueIndexOf(q, PartyPlayback(), null))
    }
}
