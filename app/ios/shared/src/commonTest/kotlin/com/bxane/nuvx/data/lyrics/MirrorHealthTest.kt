package com.bxane.nuvx.data.lyrics

import kotlinx.coroutines.async

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue









class MirrorHealthTest {

    private val mirrors = listOf("a", "b", "c")


    private class Clock(var at: Long = 1_000L) {
        fun advance(ms: Long) { at += ms }
        fun read() = at
    }

    private fun health(clock: Clock) = MirrorHealth(now = { clock.read() })



    @Test
    fun every_mirror_is_tried_when_none_has_been_used() {
        val clock = Clock()
        assertEquals(mirrors, health(clock).order(mirrors))
    }

    @Test
    fun the_last_host_to_answer_is_tried_first() {


        val clock = Clock()
        val h = health(clock)
        h.answered("b")
        assertEquals("b", h.order(mirrors).first())
        assertEquals(mirrors.size, h.order(mirrors).size)
    }

    @Test
    fun ordering_keeps_every_mirror_exactly_once() {
        val clock = Clock()
        val h = health(clock)
        h.answered("c")
        val order = h.order(mirrors)
        assertEquals(mirrors.size, order.size)
        assertEquals(mirrors.toSet(), order.toSet())
    }

    @Test
    fun a_host_that_answered_stays_in_the_list_after_a_later_empty_answer() {


        val clock = Clock()
        val h = health(clock)
        h.answered("b")
        h.empty("b")
        assertEquals("b", h.order(mirrors).first())
        assertEquals("b", h.lastGood)
    }



    @Test
    fun an_unreachable_host_is_skipped_after_one_failure() {
        val clock = Clock()
        val h = health(clock)
        h.unreachable("b")
        assertTrue("b" !in h.order(mirrors), h.order(mirrors).toString())
    }

    @Test
    fun a_skipped_host_comes_back_when_its_penalty_expires() {



        val clock = Clock()
        val h = health(clock)
        h.unreachable("b")
        clock.advance(29_000)
        assertTrue("b" !in h.order(mirrors))
        clock.advance(2_000)
        assertTrue("b" in h.order(mirrors), h.order(mirrors).toString())
    }

    @Test
    fun repeated_failures_back_off_further() {
        val clock = Clock()
        val h = health(clock)
        h.unreachable("b")
        clock.advance(31_000)
        assertTrue("b" in h.order(mirrors), "the first penalty has expired, so it is retried")
        h.unreachable("b")
        clock.advance(31_000)
        assertTrue("b" !in h.order(mirrors), "the second penalty is twice as long")
    }

    @Test
    fun the_backoff_is_capped() {
        val clock = Clock()
        val h = health(clock)
        repeat(10) {
            h.unreachable("b")
            clock.advance(1_000_000)
        }


        clock.at = 0
        h.unreachable("b")
        clock.advance(15 * 60_000L)
        assertTrue("b" in h.order(mirrors), h.order(mirrors).toString())
    }

    @Test
    fun a_host_that_answers_again_is_fully_reinstated() {
        val clock = Clock()
        val h = health(clock)
        h.unreachable("b")
        h.unreachable("b")
        clock.advance(10 * 60_000L)
        h.answered("b")
        clock.advance(10 * 60_000L)
        assertEquals("b", h.order(mirrors).first())
        assertNull(h.lastGood.takeIf { it != "b" })
    }

    @Test
    fun a_host_that_just_failed_is_not_offered_first() {


        val clock = Clock()
        val h = health(clock)
        h.answered("b")
        h.unreachable("b")
        assertTrue(h.order(mirrors).first() != "b", h.order(mirrors).toString())
    }



    @Test
    fun every_host_failing_still_offers_someone_to_try() {



        val clock = Clock()
        val h = health(clock)
        mirrors.forEach { h.unreachable(it) }
        assertEquals(mirrors, h.order(mirrors))
    }

    @Test
    fun a_mirror_the_table_has_never_seen_is_always_offered() {

        val clock = Clock()
        val h = health(clock)
        h.unreachable("a")
        assertTrue("b" in h.order(mirrors))
        assertTrue("c" in h.order(mirrors))
    }

    @Test
    fun a_mirror_dropped_from_the_list_is_forgotten() {




        val clock = Clock()
        val h = health(clock)
        h.unreachable("a")
        h.unreachable("b")
        assertEquals(2, h.skippedCount)
        assertEquals(listOf("a", "b"), h.order(listOf("a", "b")))



        assertEquals(listOf("b"), h.order(listOf("b")))
        assertEquals(1, h.skippedCount, h.order(listOf("b")).toString())
    }

    @Test
    fun reset_clears_both_the_penalties_and_the_last_good_host() {
        val clock = Clock()
        val h = health(clock)
        h.answered("b")
        h.unreachable("a")
        h.reset()
        assertEquals(mirrors, h.order(mirrors))
        assertNull(h.lastGood)
    }
    @Test
    fun concurrentRequestsShareSafeSnapshots() = kotlinx.coroutines.runBlocking {
        val health = MirrorHealth { 0L }
        val mirrors = listOf("a", "b", "c")
        val workers = List(8) { worker ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                repeat(200) { iteration ->
                    val host = mirrors[(worker + iteration) % mirrors.size]
                    health.unreachable(host)
                    assertTrue(health.order(mirrors).isNotEmpty())
                    health.answered(host)
                }
            }
        }
        workers.forEach { it.await() }
        health.reset()
        assertEquals(0, health.skippedCount)
        assertEquals(mirrors, health.order(mirrors))
    }

}
