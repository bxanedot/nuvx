package com.bxane.nuvx.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue










class LyricsOffsetTest {



    @Test
    fun no_correction_is_zero() {
        assertEquals(0, LyricsOffset.coerce(0))
    }

    @Test
    fun the_range_is_plus_or_minus_five_seconds() {
        assertEquals(5_000, LyricsOffset.coerce(5_000))
        assertEquals(-5_000, LyricsOffset.coerce(-5_000))
    }

    @Test
    fun a_stored_value_beyond_the_range_is_clamped() {



        assertEquals(5_000, LyricsOffset.coerce(9_000))
        assertEquals(-5_000, LyricsOffset.coerce(-9_000))
    }

    @Test
    fun a_value_at_the_edge_stays_at_the_edge() {
        assertEquals(5_000, LyricsOffset.increase(5_000))
        assertEquals(-5_000, LyricsOffset.decrease(-5_000))
    }



    @Test
    fun a_step_is_a_hundred_milliseconds() {
        assertEquals(100, LyricsOffset.increase(0))
        assertEquals(-100, LyricsOffset.decrease(0))
    }

    @Test
    fun stepping_is_cumulative() {
        var value = 0
        repeat(10) { value = LyricsOffset.increase(value) }
        assertEquals(1_000, value)
        repeat(10) { value = LyricsOffset.decrease(value) }
        assertEquals(0, value)
    }

    @Test
    fun increasing_delays_the_lyrics() {



        assertTrue(LyricsOffset.increase(0) > 0)
        assertTrue(LyricsOffset.decrease(0) < 0)
    }



    @Test
    fun the_slider_spans_the_whole_range() {
        assertEquals(0f, LyricsOffset.fraction(LyricsOffset.MIN_MS))
        assertEquals(1f, LyricsOffset.fraction(LyricsOffset.MAX_MS))
    }

    @Test
    fun the_middle_of_the_slider_is_zero() {


        assertEquals(0, LyricsOffset.value(0.5f))
    }

    @Test
    fun a_dragged_slider_lands_on_a_value_the_buttons_can_reach() {



        for (step in 0..100) {
            val fraction = step / 100f
            val stored = LyricsOffset.value(fraction)
            assertEquals(
                stored,
                LyricsOffset.coerce(stored),
                "fraction $fraction stored an out-of-range value",
            )
            assertEquals(
                0,
                stored % LyricsOffset.STEP_MS,
                "fraction $fraction stored $stored, which is not a whole number of steps",
            )
        }
    }

    @Test
    fun the_slider_snaps_to_the_nearest_step_rather_than_truncating() {


        assertEquals(100, LyricsOffset.value(0.51f))
        assertEquals(200, LyricsOffset.value(0.52f))
    }

    @Test
    fun a_dragged_value_comes_back_to_where_it_was_dragged() {
        for (ms in -5_000..5_000 step 100) {
            val roundTripped = LyricsOffset.value(LyricsOffset.fraction(ms))
            assertEquals(ms, roundTripped, "$ms did not survive the round trip")
        }
    }

    @Test
    fun a_fraction_outside_the_slider_is_clamped() {


        assertEquals(5_000, LyricsOffset.value(1.2f))
        assertEquals(-5_000, LyricsOffset.value(-0.4f))
    }



    @Test
    fun no_correction_reads_as_a_bare_zero() {
        assertEquals("0.0s", LyricsOffset.format(0))
    }

    @Test
    fun a_correction_carries_its_sign() {
        assertEquals("+0.4s", LyricsOffset.format(400))
        assertEquals("−0.4s", LyricsOffset.format(-400))
    }

    @Test
    fun the_positive_side_is_signed_too() {


        assertTrue(LyricsOffset.format(400).startsWith("+"))
    }

    @Test
    fun the_label_uses_a_real_minus_sign() {


        assertTrue(LyricsOffset.format(-400).contains("−"))
        assertTrue(!LyricsOffset.format(-400).contains("-"))
    }

    @Test
    fun the_label_shows_a_tenth_of_a_second() {
        assertEquals("+5.0s", LyricsOffset.format(5_000))
        assertEquals("−0.1s", LyricsOffset.format(-100))
    }

    @Test
    fun the_label_clamps_a_value_from_a_hand_edited_store() {
        assertEquals("+5.0s", LyricsOffset.format(99_000))
        assertEquals("−5.0s", LyricsOffset.format(-99_000))
    }
}
