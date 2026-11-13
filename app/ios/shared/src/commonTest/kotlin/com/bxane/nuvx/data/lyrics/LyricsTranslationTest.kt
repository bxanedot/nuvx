package com.bxane.nuvx.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue








class LyricsTranslationTest {

    private fun line(text: String, words: Int = 0) = LyricLineDto(
        timeMs = 0,
        text = text,
        words = if (words == 0) emptyList() else (1..words).map {
            LyricWordDto(it * 100L, it * 100L + 100L, "w$it")
        },
    )



    @Test
    fun `the same language is recognised across spellings`() {
        assertTrue(LyricsTranslation.sameLanguage("en", "en"))
        assertTrue(LyricsTranslation.sameLanguage("EN", "en"))
        assertTrue(LyricsTranslation.sameLanguage("en-US", "en-GB"))
    }

    @Test
    fun `a region does not make a different language`() {



        assertTrue(LyricsTranslation.sameLanguage("zh-CN", "zh"))
    }

    @Test
    fun `two scripts of one language count as the same language`() {





        assertTrue(LyricsTranslation.sameLanguage("zh-CN", "zh-TW"))
    }

    @Test
    fun `legacy codes are canonicalised`() {
        assertEquals("he", LyricsTranslation.canonicalLanguage("iw"))
        assertEquals("id", LyricsTranslation.canonicalLanguage("in"))
        assertEquals("yi", LyricsTranslation.canonicalLanguage("ji"))
        assertEquals("ro", LyricsTranslation.canonicalLanguage("mo"))

        assertEquals("no", LyricsTranslation.canonicalLanguage("nb"))
        assertEquals("no", LyricsTranslation.canonicalLanguage("nn"))
    }

    @Test
    fun `an underscore separator is a dash for this purpose`() {
        assertEquals("pt", LyricsTranslation.canonicalLanguage("pt_BR"))
    }

    @Test
    fun `a blank language is never the same as anything`() {


        assertFalse(LyricsTranslation.sameLanguage("", "en"))
        assertFalse(LyricsTranslation.sameLanguage("en", ""))
    }



    @Test
    fun `a bracketed section name is a header`() {
        assertTrue(LyricsTranslation.isSectionHeader("[Verse 1]"))
        assertTrue(LyricsTranslation.isSectionHeader("[Chorus]"))
        assertTrue(LyricsTranslation.isSectionHeader("[Bridge]"))
        assertTrue(LyricsTranslation.isSectionHeader("[Intro]"))
        assertTrue(LyricsTranslation.isSectionHeader("[Pre-Chorus]"))
    }

    @Test
    fun `a bracketed lyric line is not a header`() {
        assertFalse(LyricsTranslation.isSectionHeader("[oh oh oh]"))
        assertFalse(LyricsTranslation.isSectionHeader("[I said what I said]"))
    }

    @Test
    fun `a stanza is not a header`() {

        assertFalse(LyricsTranslation.isSectionHeader("[first line\nsecond line]"))
    }

    @Test
    fun `an unbracketed line is never a header`() {
        assertFalse(LyricsTranslation.isSectionHeader("Verse 1"))
        assertFalse(LyricsTranslation.isSectionHeader("Just a line"))
        assertFalse(LyricsTranslation.isSectionHeader(""))
    }



    @Test
    fun `latin letters are recognised across the blocks`() {


        assertTrue(isLatinLetter('a'))
        assertTrue(isLatinLetter('Z'))
        assertTrue(isLatinLetter('é'))
        assertTrue(isLatinLetter('ñ'))
        assertTrue(isLatinLetter('ł'))
        assertTrue(isLatinLetter('ā'))
    }

    @Test
    fun `other scripts are not latin`() {
        assertFalse(isLatinLetter('日'))
        assertFalse(isLatinLetter('本'))
        assertFalse(isLatinLetter('한'))
        assertFalse(isLatinLetter('д'))
        assertFalse(isLatinLetter('א'))
        assertFalse(isLatinLetter('ع'))
    }

    @Test
    fun `a symbol is neither latin nor a letter`() {


        assertFalse(isLetter('1'))
        assertFalse(isLetter('['))
        assertFalse(isNonLatinLetter('1'))
    }

    @Test
    fun `a wholly latin lyric is not worth romanising`() {
        assertFalse(isRomanisable("There is a light that never goes out"))
    }

    @Test
    fun `a lyric with any non-latin letter is worth romanising`() {
        assertTrue(isRomanisable("灯花"))
        assertTrue(isRomanisable("君の名は"))
        assertTrue(isRomanisable("Привет"))

        assertTrue(isRomanisable("Hello 世界"))
    }

    @Test
    fun `a lyric of only digits and symbols is not worth romanising`() {


        assertFalse(isRomanisable("--- 4 ---"))
    }
}
