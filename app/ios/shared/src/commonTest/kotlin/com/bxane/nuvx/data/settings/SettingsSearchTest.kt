package com.bxane.nuvx.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue









class SettingsSearchTest {

    private val playback = listOf(
        "crossfade", "gapless", "automix", "autoplay", "skip silence",
        "playback speed", "spatial audio", "fade",
    )



    @Test
    fun `the exact title is the best possible match`() {
        assertEquals(
            SettingsMatch.EXACT,
            SettingsSearch.match("Playback", "Playback", playback),
        )
    }

    @Test
    fun `a title match ignores case`() {



        assertEquals(
            SettingsMatch.EXACT,
            SettingsSearch.match("PLAYBACK", "Playback", playback),
        )
    }

    @Test
    fun `a partial title is still a title match`() {

        assertEquals(
            SettingsMatch.TITLE,
            SettingsSearch.match("play", "Playback", playback),
        )
    }

    @Test
    fun `a title match outranks a term match`() {


        assertTrue(
            SettingsSearch.match("play", "Playback", emptyList()).strength >
                SettingsSearch.match("play", "Storage", listOf("playback queue")).strength,
        )
    }

    @Test
    fun `strength ranks the matches strongest first`() {



        val ranked = listOf(SettingsMatch.NONE, SettingsMatch.TERM, SettingsMatch.TITLE, SettingsMatch.EXACT)
        assertEquals(
            listOf(SettingsMatch.EXACT, SettingsMatch.TITLE, SettingsMatch.TERM, SettingsMatch.NONE),
            ranked.sortedByDescending { it.strength },
        )
    }



    @Test
    fun `a term finds the section that holds the setting`() {

        assertEquals(
            SettingsMatch.TERM,
            SettingsSearch.match("crossfade", "Playback", playback),
        )
    }

    @Test
    fun `a partial term matches`() {

        assertEquals(
            SettingsMatch.TERM,
            SettingsSearch.match("cross", "Playback", playback),
        )
    }

    @Test
    fun `a term matches ignoring case`() {
        assertEquals(
            SettingsMatch.TERM,
            SettingsSearch.match("SPATIAL", "Playback", playback),
        )
    }

    @Test
    fun `a term that is a substring of an unrelated word still matches`() {



        assertEquals(
            SettingsMatch.TERM,
            SettingsSearch.match("art", "Playback", listOf("Artist")),
        )
    }



    @Test
    fun `an unrelated word matches nothing`() {
        assertEquals(
            SettingsMatch.NONE,
            SettingsSearch.match("helicopter", "Playback", playback),
        )
        assertFalse(SettingsSearch.matches("helicopter", "Playback", playback))
    }

    @Test
    fun `an empty query shows everything`() {

        assertEquals(
            SettingsMatch.TITLE,
            SettingsSearch.match("", "Playback", playback),
        )
        assertTrue(SettingsSearch.matches("   ", "Playback", playback))
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {


        assertEquals(
            SettingsMatch.EXACT,
            SettingsSearch.match("  Playback  ", "Playback", playback),
        )
    }

    @Test
    fun `a section with no terms is found by its title alone`() {
        assertTrue(SettingsSearch.matches("about", "About", emptyList()))
        assertFalse(SettingsSearch.matches("crossfade", "About", emptyList()))
    }



    @Test
    fun `the best of several entries is the titled one`() {
        val entries = listOf(
            "Storage" to listOf("cache", "downloads", "folder"),
            "Playback" to listOf("crossfade"),
            "Appearance" to listOf("theme", "dark mode"),
        )
        val best = SettingsSearch.bestOf("play", entries)


        assertEquals(1, best?.first)
        assertEquals(SettingsMatch.TITLE, best?.second)
        assertNull(SettingsSearch.bestOf("nothing here", entries))
    }

    @Test
    fun `no entry matching means no best`() {
        assertNull(
            SettingsSearch.bestOf("helicopter", listOf("Playback" to listOf("crossfade"))),
        )
    }

    @Test
    fun `an empty entry list has no best`() {
        assertNull(SettingsSearch.bestOf("play", emptyList()))
    }

    @Test
    fun `the first entry wins a tie`() {


        val entries = listOf("One" to listOf("x"), "Two" to listOf("x"))
        assertEquals(0, SettingsSearch.bestOf("x", entries)?.first)
    }
}
