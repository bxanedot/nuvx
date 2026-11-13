package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue










class PartyDriftJudgeTest {

    private val judge = PartyDriftJudge()


    private fun twoTicks(
        party: Long,
        local: Long,
        now: Long = 0,
        partyIsPlaying: Boolean = true,
        localIsPlaying: Boolean = true,
    ): PartyDriftJudge.Decision {
        judge.onTick(party, local, localIsPlaying, partyIsPlaying, now)
        return judge.onTick(party, local, localIsPlaying, partyIsPlaying, now + PARTY_TICK_MS)
    }



    @Test
    fun `a device in step is left alone`() {
        assertEquals(PartyDriftJudge.Decision.None, twoTicks(party = 30_000, local = 30_000))
    }

    @Test
    fun `a gap too small to hear is left alone`() {



        assertEquals(PartyDriftJudge.Decision.None, twoTicks(party = 30_000, local = 30_100))
    }

    @Test
    fun `a gap over the tolerance but under the limit is still left alone`() {

        assertEquals(PartyDriftJudge.Decision.None, twoTicks(party = 30_000, local = 30_600))
    }

    @Test
    fun `one wide tick is not acted on`() {


        assertEquals(
            PartyDriftJudge.Decision.None,
            judge.onTick(partyPositionMs = 30_000, localPositionMs = 20_000, localIsPlaying = true, partyIsPlaying = true, nowMs = 0),
        )
    }

    @Test
    fun `a gap that clears is forgiven`() {

        judge.onTick(30_000, 20_000, true, true, 0)
        judge.onTick(30_000, 30_000, true, true, PARTY_TICK_MS)
        assertEquals(
            PartyDriftJudge.Decision.None,
            judge.onTick(30_000, 20_000, true, true, PARTY_TICK_MS * 2),
        )
    }



    @Test
    fun `two consecutive wide ticks seek`() {
        val decision = twoTicks(party = 30_000, local = 20_000)
        val seek = assertIs<PartyDriftJudge.Decision.Seek>(decision)
        assertEquals(30_000, seek.seekToMs)
    }

    @Test
    fun `a persistent gap is not machine-gunned`() {


        val seekTimes = mutableListOf<Long>()
        var now = 0L
        repeat(10) {
            judge.onTick(30_000, 20_000, true, true, now)
            val decision = judge.onTick(30_000, 20_000, true, true, now + PARTY_TICK_MS)
            if (decision is PartyDriftJudge.Decision.Seek) seekTimes += now + PARTY_TICK_MS
            now += PARTY_TICK_MS
        }



        assertEquals(2, seekTimes.size, "seeks at $seekTimes")


        assertTrue(
            seekTimes[1] - seekTimes[0] >= PartyDriftJudge.DRIFT_COOLDOWN_MS,
            "seeks were $seekTimes",
        )
    }

    @Test
    fun `a seek is available again after the cooldown`() {
        twoTicks(party = 30_000, local = 20_000, now = 0)

        val later = PartyDriftJudge.DRIFT_COOLDOWN_MS + 10_000
        judge.onTick(30_000, 20_000, true, true, later)
        val decision = judge.onTick(30_000, 20_000, true, true, later + PARTY_TICK_MS)
        assertIs<PartyDriftJudge.Decision.Seek>(decision)
    }

    @Test
    fun `a suppressed seek does not bank a strike`() {



        twoTicks(party = 30_000, local = 20_000, now = 0)
        twoTicks(party = 30_000, local = 20_000, now = 1_000)
        val after = PartyDriftJudge.DRIFT_COOLDOWN_MS + 1_000
        judge.onTick(30_000, 20_000, true, true, after)
        assertIs<PartyDriftJudge.Decision.Seek>(judge.onTick(30_000, 20_000, true, true, after + PARTY_TICK_MS))
    }

    @Test
    fun `a device ahead of the party is pulled back`() {


        val seek = assertIs<PartyDriftJudge.Decision.Seek>(twoTicks(party = 30_000, local = 40_000))
        assertEquals(30_000, seek.seekToMs)
    }

    @Test
    fun `a corrected position is never negative`() {

        val seek = assertIs<PartyDriftJudge.Decision.Seek>(twoTicks(party = -5_000, local = 20_000))
        assertEquals(0, seek.seekToMs)
    }



    @Test
    fun `a playing device stops when the party pauses`() {
        val decision = judge.onTick(
            partyPositionMs = 30_000, localPositionMs = 30_000, localIsPlaying = true, partyIsPlaying = false, nowMs = 0,
        )
        assertEquals(PartyDriftJudge.Decision.Pause, decision)
    }

    @Test
    fun `a playing device stops even at exactly the same position`() {





        val decision = judge.onTick(
            partyPositionMs = 30_000, localPositionMs = 30_000, localIsPlaying = true, partyIsPlaying = false, nowMs = 0,
        )
        assertEquals(PartyDriftJudge.Decision.Pause, decision)
    }

    @Test
    fun `a pause needs no strikes and no cooldown`() {


        val judge = PartyDriftJudge()
        repeat(3) { index ->
            val decision = judge.onTick(30_000, 20_000, true, false, index * PARTY_TICK_MS)
            assertEquals(PartyDriftJudge.Decision.Pause, decision)
        }
    }

    @Test
    fun `a paused party clears the strike count`() {


        judge.onTick(30_000, 20_000, true, true, 0)
        judge.onTick(30_000, 20_000, true, false, PARTY_TICK_MS)


        assertEquals(
            PartyDriftJudge.Decision.None,
            judge.onTick(30_000, 20_000, true, true, PARTY_TICK_MS * 2),
            "the count was not cleared, so this acted on a banked strike",
        )
        assertIs<PartyDriftJudge.Decision.Seek>(
            judge.onTick(30_000, 20_000, true, true, PARTY_TICK_MS * 3),
            "and a fresh pair should then act",
        )
    }



    @Test
    fun `following is judged on the same tolerance as alignment`() {


        assertTrue(isFollowing(30_000, 30_000))
        assertTrue(isFollowing(30_000, 30_100))
        assertTrue(!isFollowing(30_000, 32_000))
    }

    @Test
    fun `resetting clears the strike count and the cooldown`() {
        twoTicks(party = 30_000, local = 20_000, now = 0)
        judge.reset()

        assertIs<PartyDriftJudge.Decision.Seek>(twoTicks(party = 30_000, local = 20_000, now = 1_000))
    }
}
