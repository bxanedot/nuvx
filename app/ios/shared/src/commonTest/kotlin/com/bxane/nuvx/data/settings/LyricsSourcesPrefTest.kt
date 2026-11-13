package com.bxane.nuvx.data.settings

import com.bxane.nuvx.data.lyrics.LyricsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue










class LyricsSourcesPrefTest {


    private class Store(initial: Map<String, String> = emptyMap()) {
        val values = initial.toMutableMap()
        fun string(key: String, fallback: String) = values[key] ?: fallback
        fun put(key: String, value: String) { values[key] = value }
        fun has(key: String) = key in values
    }










    private fun adopt(current: Set<LyricsSource>, known: Set<LyricsSource>): Pair<Set<LyricsSource>, Set<LyricsSource>> {
        val everything = LyricsSource.entries.toSet()
        if (known.containsAll(everything)) return current to known
        val newlyEnabled = (everything - known) - LyricsSource.SIMP_MUSIC
        return (current + newlyEnabled) to everything
    }

    private fun names(set: Set<LyricsSource>) = set.map { it.name }.toSet()

    @Test
    fun `a source an upgrade added is switched on`() {
        val (enabled, _) = adopt(
            current = setOf(LyricsSource.LYRICS_PLUS, LyricsSource.PAXSENIX),
            known = setOf(LyricsSource.LYRICS_PLUS, LyricsSource.PAXSENIX),
        )
        assertTrue(LyricsSource.BINI_LYRICS in enabled)
    }

    @Test
    fun `every default source except blocked SimpMusic is reached on a first upgrade`() {
        val (enabled, known) = adopt(
            current = setOf(LyricsSource.LRCLIB),
            known = setOf(LyricsSource.LRCLIB),
        )
        assertEquals(names(defaultEnabledLyricsSources()), names(enabled))
        assertEquals(names(LyricsSource.entries.toSet()), names(known))
        assertFalse(LyricsSource.SIMP_MUSIC in enabled)
    }

    @Test
    fun `the ISRC pass is switched on by that same upgrade`() {


        val (enabled, _) = adopt(
            current = setOf(LyricsSource.LRCLIB),
            known = LyricsSource.entries.toSet() - LyricsSource.BINI_LYRICS,
        )
        assertTrue(LyricsSource.BINI_LYRICS in enabled)
    }

    @Test
    fun `a source the user switched off stays off`() {




        val everything = LyricsSource.entries.toSet()
        val decided = setOf(LyricsSource.LYRICS_PLUS, LyricsSource.LRCLIB, LyricsSource.KUGOU)
        val (enabled, _) = adopt(current = decided, known = everything)
        assertEquals(names(decided), names(enabled))
        assertFalse(LyricsSource.PAXSENIX in enabled)
        assertFalse(LyricsSource.BETTER_LYRICS in enabled)
    }

    @Test
    fun `an explicit SimpMusic choice survives later source adoption`() {
        val everything = LyricsSource.entries.toSet()
        val (enabled, _) = adopt(
            current = setOf(LyricsSource.LRCLIB, LyricsSource.SIMP_MUSIC),
            known = everything - LyricsSource.BINI_LYRICS,
        )
        assertTrue(LyricsSource.SIMP_MUSIC in enabled)
        assertTrue(LyricsSource.BINI_LYRICS in enabled)
    }

    @Test
    fun `fresh defaults do not enable SimpMusic`() {
        assertFalse(LyricsSource.SIMP_MUSIC in defaultEnabledLyricsSources())
        assertTrue(LyricsSource.BINI_LYRICS in defaultEnabledLyricsSources())
    }

    @Test
    fun `a second upgrade with nothing new changes nothing`() {
        val everything = LyricsSource.entries.toSet()
        val (enabled, known) = adopt(current = everything, known = everything)
        assertEquals(names(everything), names(enabled))
        assertEquals(names(everything), names(known))
    }

    @Test
    fun `adopting does not re-enable a source the user removed in the same release`() {



        val known = LyricsSource.entries.toSet()
        val (enabled, _) = adopt(current = emptySet(), known = known)
        assertTrue(enabled.isEmpty())
    }
}
