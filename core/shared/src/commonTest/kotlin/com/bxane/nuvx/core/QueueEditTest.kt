package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueEditTest {
    private val q = PlayerState(listOf("a", "b", "c").map { Track(it, it) }, index = 1)
    private fun ids(s: PlayerState) = s.queue.map { it.videoId } to s.index

    @Test fun removingBeforeAfterAndCurrent() {
        assertEquals((listOf("b", "c") to 0) to false, q.removing(0)!!.let { ids(it.first) to it.second })
        assertEquals((listOf("a", "b") to 1) to false, q.removing(2)!!.let { ids(it.first) to it.second })
        assertEquals((listOf("a", "c") to 1) to true, q.removing(1)!!.let { ids(it.first) to it.second }, "next track takes over")
        val last = q.copy(index = 2).removing(2)!!
        assertEquals((listOf("a", "b") to 1) to true, ids(last.first) to last.second, "removing the last playing track falls back to the new last")
        val only = PlayerState(listOf(Track("x", "x")), 0).removing(0)!!
        assertEquals(PlayerState() to true, only)
        assertNull(q.removing(5))
    }

    @Test fun movingKeepsThePlayingTrack() {
        assertEquals(listOf("b", "c", "a") to 0, ids(q.moving(0, 2)!!))
        assertEquals(listOf("c", "a", "b") to 2, ids(q.moving(2, 0)!!))
        assertEquals(listOf("a", "c", "b") to 2, ids(q.moving(1, 2)!!), "moving the playing track follows it")
        assertEquals(listOf("b", "a", "c") to 0, ids(q.moving(0, 1)!!))
        assertNull(q.moving(1, 1)); assertNull(q.moving(0, 3))
    }
}
