package com.bxane.nuvx.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals










class LyricAlignmentsTest {

    private fun sides(vararg singers: String?, types: Map<String, String> = emptyMap()) =
        lineAlignments(singers.toList(), types)



    @Test
    fun one_voice_is_all_on_one_side() {
        assertEquals(
            listOf(LyricAlignment.Start, LyricAlignment.Start, LyricAlignment.Start),
            sides("v1", "v1", "v1"),
        )
    }

    @Test
    fun a_line_with_no_agent_is_on_the_left() {


        assertEquals(listOf(LyricAlignment.Start), sides(null))
        assertEquals(listOf(LyricAlignment.Start, LyricAlignment.Start), sides("", "v1"))
    }



    @Test
    fun two_voices_alternate_sides() {
        assertEquals(
            listOf(LyricAlignment.Start, LyricAlignment.End, LyricAlignment.Start),
            sides("v1", "v2", "v1"),
        )
    }

    @Test
    fun consecutive_lines_by_one_voice_stay_on_their_side() {

        assertEquals(
            listOf(
                LyricAlignment.Start, LyricAlignment.Start,
                LyricAlignment.End, LyricAlignment.End,
            ),
            sides("v1", "v1", "v2", "v2"),
        )
    }

    @Test
    fun three_voices_keep_taking_turns() {


        assertEquals(
            listOf(
                LyricAlignment.Start, LyricAlignment.End, LyricAlignment.Start,
                LyricAlignment.End, LyricAlignment.Start,
            ),
            sides("v1", "v2", "v3", "v1", "v2"),
        )
    }



    @Test
    fun the_group_voice_stays_on_the_left_without_taking_a_turn() {


        assertEquals(
            listOf(
                LyricAlignment.Start, LyricAlignment.Start, LyricAlignment.End,
            ),
            sides("v1", "v1000", "v2"),
        )
    }

    @Test
    fun the_reserved_other_voice_starts_on_the_right() {


        assertEquals(
            listOf(LyricAlignment.End, LyricAlignment.Start),
            sides("v2000", "v1"),
        )
    }

    @Test
    fun a_declared_type_beats_the_reserved_id() {


        assertEquals(
            listOf(LyricAlignment.Start),
            sides("v2000", types = mapOf("v2000" to "group")),
        )
    }

    @Test
    fun a_declared_person_beats_the_default() {
        assertEquals(
            listOf(LyricAlignment.Start, LyricAlignment.End),
            sides("a", "b", types = mapOf("a" to "person", "b" to "person")),
        )
    }



    @Test
    fun a_song_that_opens_on_the_second_voice_is_flipped_back() {


        assertEquals(
            listOf(LyricAlignment.Start, LyricAlignment.End, LyricAlignment.Start),
            sides("v2", "v1", "v2"),
        )
    }

    @Test
    fun a_genuine_duet_is_not_flipped() {

        val duet = List(20) { if (it % 2 == 0) "v1" else "v2" }
        val out = sides(*duet.toTypedArray())
        assertEquals(10, out.count { it == LyricAlignment.End })
    }

    @Test
    fun a_duet_with_a_group_chorus_is_not_flipped() {





        val singers = buildList {
            repeat(10) { add(if (it % 2 == 0) "v1" else "v2") }
            repeat(4) { add("v1000") }
        }
        val out = sides(*singers.toTypedArray())
        assertEquals(5, out.count { it == LyricAlignment.End })
    }

    @Test
    fun a_duet_that_leans_heavily_right_is_still_flipped() {



        val singers = buildList {
            add("v2")
            repeat(20) { add("v1") }
        }
        val out = sides(*singers.toTypedArray())
        assertEquals(20, out.count { it == LyricAlignment.Start })
    }



    @Test
    fun an_empty_track_places_nothing() {
        assertEquals(emptyList(), sides())
    }

    @Test
    fun a_track_with_no_agents_at_all_is_left_alone() {


        assertEquals(
            listOf(LyricAlignment.Start, LyricAlignment.Start),
            sides(null, null),
        )
    }

    @Test
    fun the_number_of_sides_always_matches_the_number_of_lines() {


        for (count in 0..8) {
            val singers = List(count) { if (it % 2 == 0) "v1" else "v2" }
            assertEquals(count, sides(*singers.toTypedArray()).size)
        }
    }
}
