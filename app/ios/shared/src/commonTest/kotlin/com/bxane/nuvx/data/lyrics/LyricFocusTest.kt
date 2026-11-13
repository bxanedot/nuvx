package com.bxane.nuvx.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue









class LyricFocusTest {

    private fun line(
        timeMs: Long,
        text: String = "words",
        sungUntilMs: Long? = null,
        words: List<LyricWordDto> = emptyList(),
        background: LyricLineDto? = null,
    ) = LyricLineDto(
        timeMs = timeMs,
        text = text,
        words = words,
        sungUntilMs = sungUntilMs,
        background = background,
    )

    private fun word(start: Long, end: Long, text: String = "w") =
        LyricWordDto(startMs = start, endMs = end, text = text)



    @Test
    fun the_latest_line_passed_is_the_active_one() {
        val lines = listOf(line(1_000), line(5_000), line(9_000))
        assertEquals(listOf(1), LyricFocus.activeRows(lines, 6_000))
    }

    @Test
    fun nothing_is_active_before_the_first_line() {
        val lines = listOf(line(1_000), line(5_000))
        assertEquals(emptyList(), LyricFocus.activeRows(lines, 500))
    }

    @Test
    fun nothing_is_active_on_an_empty_list() {
        assertEquals(emptyList(), LyricFocus.activeRows(emptyList(), 5_000))
    }

    @Test
    fun an_unsynced_transcript_activates_nothing() {


        val lines = listOf(line(0, "one"), line(0, "two"))
        assertEquals(emptyList(), LyricFocus.activeRows(lines, 5_000))
        assertTrue(!LyricFocus.isSynced(lines))
    }

    @Test
    fun a_real_transcript_is_synced() {
        assertTrue(LyricFocus.isSynced(listOf(line(0, "one"), line(4_000, "two"))))
    }



    @Test
    fun a_line_with_a_known_end_stays_active_after_the_next_stamp() {



        val lines = listOf(line(1_000, "lead", sungUntilMs = 20_000), line(10_000, "answer"))
        assertEquals(listOf(0, 1), LyricFocus.activeRows(lines, 12_000))
    }

    @Test
    fun a_line_with_a_known_end_releases_it_once_it_ends() {
        val lines = listOf(line(1_000, "lead", sungUntilMs = 20_000), line(10_000, "answer"))
        assertEquals(listOf(1), LyricFocus.activeRows(lines, 20_000))
    }

    @Test
    fun a_line_whose_words_end_holds_on_those_words_instead() {
        val lines = listOf(
            line(1_000, "lead", words = listOf(word(1_000, 20_000))),
            line(10_000, "answer"),
        )
        assertEquals(listOf(0, 1), LyricFocus.activeRows(lines, 15_000))
    }

    @Test
    fun a_line_with_no_known_end_releases_at_the_next_stamp() {

        val lines = listOf(line(1_000, "lead"), line(10_000, "answer"))
        assertEquals(listOf(1), LyricFocus.activeRows(lines, 12_000))
    }

    @Test
    fun a_background_vocal_keeps_its_lead_alive() {




        val lines = listOf(
            line(1_000, "lead", background = line(3_000, "(ooh)", words = listOf(word(3_000, 20_000)))),
            line(10_000, "next"),
        )
        assertEquals(listOf(0, 1), LyricFocus.activeRows(lines, 12_000))
    }

    @Test
    fun a_lead_with_no_end_and_no_background_is_released_at_the_next_stamp() {


        val lines = listOf(line(1_000, "lead"), line(10_000, "next"))
        assertEquals(listOf(1), LyricFocus.activeRows(lines, 12_000))
    }

    @Test
    fun a_gap_is_never_active() {


        val lines = listOf(line(1_000, "words"), line(5_000, ""))
        assertEquals(emptyList(), LyricFocus.activeRows(lines, 6_000))
    }

    @Test
    fun a_current_instrumental_gap_does_not_highlight_a_music_note() {
        val lines = listOf(line(1_000, "words", sungUntilMs = 2_000), line(5_000, ""))
        assertEquals(emptyList(), LyricFocus.activeRows(lines, 6_000))
    }

    @Test
    fun word_timestamps_make_a_zero_stamped_line_synced_and_active() {
        val lines = listOf(
            line(0, "hello", words = listOf(word(1_000, 1_500, "hello"))),
            line(0, "world", words = listOf(word(2_000, 2_500, "world"))),
        )
        assertTrue(LyricFocus.isSynced(lines))
        assertEquals(emptyList(), LyricFocus.activeRows(lines, 500))
        assertEquals(listOf(0), LyricFocus.activeRows(lines, 1_200))
        assertEquals(listOf(1), LyricFocus.activeRows(lines, 2_200))
    }

    @Test
    fun a_gap_with_a_known_end_does_not_hold_the_highlight() {
        val lines = listOf(line(1_000, ""), line(5_000, "back", sungUntilMs = 9_000))
        assertEquals(listOf(1), LyricFocus.activeRows(lines, 6_000))
    }



    @Test
    fun the_lead_row_is_the_first_active_one() {



        val lines = listOf(line(1_000, "lead", sungUntilMs = 20_000), line(10_000, "answer"))
        assertEquals(0, LyricFocus.leadRow(lines, 12_000))
    }

    @Test
    fun the_lead_row_is_the_current_line_when_there_is_only_one() {
        val lines = listOf(line(1_000), line(5_000), line(9_000))
        assertEquals(1, LyricFocus.leadRow(lines, 6_000))
    }

    @Test
    fun the_lead_row_is_negative_before_anything_has_been_sung() {
        val lines = listOf(line(1_000), line(5_000))
        assertEquals(-1, LyricFocus.leadRow(lines, 0))
    }

    @Test
    fun the_lead_row_never_points_past_the_end() {
        val lines = listOf(line(1_000), line(5_000))
        assertTrue(LyricFocus.leadRow(lines, 60_000) < lines.size)
    }



    @Test
    fun the_lead_is_at_least_the_minimum() {
        val lines = listOf(line(0, "one", sungUntilMs = 500), line(600, "two"))

        assertEquals(LyricFocus.SCROLL_LEAD_MIN_MS, LyricFocus.scrollLead(lines, 300))
    }

    @Test
    fun the_lead_is_at_most_the_maximum() {
        val lines = listOf(line(0, "one", sungUntilMs = 1_000), line(20_000, "two"))

        assertEquals(LyricFocus.SCROLL_LEAD_MAX_MS, LyricFocus.scrollLead(lines, 1_500))
    }

    @Test
    fun the_lead_scales_with_the_gap_in_between() {
        val lines = listOf(line(0, "one", sungUntilMs = 1_000), line(1_400, "two"))
        assertEquals(400, LyricFocus.scrollLead(lines, 1_200))
    }

    @Test
    fun the_lead_before_the_first_line_is_the_minimum() {



        val lines = listOf(line(4_000), line(8_000))
        assertEquals(LyricFocus.SCROLL_LEAD_MIN_MS, LyricFocus.scrollLead(lines, 0))
    }

    @Test
    fun the_lead_on_the_last_line_is_the_minimum() {
        val lines = listOf(line(0, "one"), line(5_000, "two"))
        assertEquals(LyricFocus.SCROLL_LEAD_MIN_MS, LyricFocus.scrollLead(lines, 9_000))
    }

    @Test
    fun the_lead_is_never_negative_for_a_malformed_track() {


        val lines = listOf(line(0, "one", sungUntilMs = 9_000), line(500, "two"))
        assertEquals(LyricFocus.SCROLL_LEAD_MIN_MS, LyricFocus.scrollLead(lines, 1_000))
    }

    @Test
    fun `a partly timed lyric keeps untimed lines from stealing the highlight`() {


        val lines = listOf(
            line(0),
            line(10_000),
            line(0),
            line(20_000),
            line(0),
            line(0),
        )

        assertEquals(listOf(1), LyricFocus.activeRows(lines, 12_000))
        assertEquals(listOf(2), LyricFocus.activeRows(lines, 16_000))
        assertEquals(listOf(3), LyricFocus.activeRows(lines, 21_000))
        assertEquals(15_000, LyricFocus.startTimes(lines)[2])
        assertEquals(24_000, LyricFocus.startTimes(lines)[4])
    }

    @Test
    fun `a fully timed lyric keeps its own times`() {
        val lines = listOf(line(0), line(1_000), line(2_500))
        assertEquals(listOf(0L, 1_000L, 2_500L), LyricFocus.startTimes(lines).toList())
    }
}
