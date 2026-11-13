package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue









class ServerClockTest {



    @Test
    fun `an unsynced clock has no offset and says so`() {
        val clock = ServerClock()
        assertNull(clock.offsetMs)
        assertFalse(clock.synced)
        assertNull(clock.serverNowMs(1_000))
    }

    @Test
    fun `one sample is enough to sync`() {
        val clock = ServerClock()

        clock.record(sentAtLocalMs = 1_000, serverMs = 5_000, receivedAtLocalMs = 1_200)
        assertTrue(clock.synced)
        assertEquals(3_900, clock.offsetMs)
        assertEquals(200, clock.roundTripMs)
    }

    @Test
    fun `the server clock is the local clock plus the offset`() {
        val clock = ServerClock()
        clock.record(1_000, 5_000, 1_200)
        assertEquals(10_000 + 3_900, clock.serverNowMs(10_000))
    }



    @Test
    fun `the shortest round trip wins · not the newest` () {
        val clock = ServerClock()


        clock.record(0, 10_000, 800)

        clock.record(1_000, 11_020, 1_040)


        assertEquals(10_000, clock.offsetMs)
        assertEquals(40, clock.roundTripMs)
    }

    @Test
    fun `a long sample does not overwrite a short one`() {
        val clock = ServerClock()
        clock.record(0, 5_000, 100)
        clock.record(200, 5_000, 900)
        assertEquals(100, clock.roundTripMs)
    }

    @Test
    fun `the window is bounded`() {
        val clock = ServerClock()
        repeat(40) { index ->
            clock.record(
                sentAtLocalMs = index * 1_000L,
                serverMs = 0,
                receivedAtLocalMs = index * 1_000L + 10,
            )
        }
        assertTrue(clock.sampleCount() <= ServerClock.WINDOW)
    }

    @Test
    fun `a sample older than its lifetime stops counting`() {
        val clock = ServerClock()

        clock.record(0, 10_000, 20)



        clock.record(1_000_000, 10_500, 1_000_200)
        assertEquals(200, clock.roundTripMs)
        assertEquals(10_500 - 1_000_100, clock.offsetMs)
    }

    @Test
    fun `a negative round trip is treated as zero rather than skewing the offset`() {


        val clock = ServerClock()
        clock.record(sentAtLocalMs = 5_000, serverMs = 5_000, receivedAtLocalMs = 4_000)
        assertEquals(0, clock.roundTripMs)
        assertEquals(0, clock.offsetMs)
    }

    @Test
    fun `reset forgets everything`() {
        val clock = ServerClock()
        clock.record(0, 5_000, 100)
        clock.reset()
        assertNull(clock.offsetMs)
        assertEquals(0, clock.sampleCount())
        assertFalse(clock.synced)
    }



    @Test
    fun `a position is advanced by the time since the party said it`() {
        val clock = ServerClock()
        clock.record(0, 0, 0)

        assertEquals(35_000, clock.positionFor(positionMs = 30_000, trueAtServerMs = 0, localNowMs = 5_000))
    }

    @Test
    fun `a position we are already past is not rewound`() {
        val clock = ServerClock()
        clock.record(0, 0, 0)


        assertEquals(2_000, clock.positionFor(positionMs = 0, trueAtServerMs = -2_000, localNowMs = 0))
    }

    @Test
    fun `a position the party has not reached is held at zero`() {
        val clock = ServerClock()
        clock.record(0, 0, 0)



        assertEquals(0, clock.positionFor(positionMs = 0, trueAtServerMs = 2_000, localNowMs = 0))
        assertEquals(0, clock.positionFor(positionMs = 0, trueAtServerMs = 1, localNowMs = 0))
    }

    @Test
    fun `an unsynced device reports zero rather than guessing`() {
        val clock = ServerClock()
        assertEquals(0, clock.positionFor(30_000, trueAtServerMs = 0, localNowMs = 5_000))
    }



    @Test
    fun `a small gap is in sync`() {
        assertTrue(isInSync(0))
        assertTrue(isInSync(200))
        assertTrue(isInSync(-200))
        assertTrue(isInSync(750))
    }

    @Test
    fun `a large gap is not`() {
        assertFalse(isInSync(751))
        assertFalse(isInSync(-751))
        assertFalse(isInSync(5_000))
    }

    @Test
    fun `two devices each within tolerance of the server can still be out with each other`() {




        val gap = PARTY_SYNC_TOLERANCE_MS + 400
        assertFalse(isInSync(gap))
    }
}
