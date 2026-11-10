package com.bxane.nuvx

import com.bxane.nuvx.data.canvas.normalizeForMatch
import com.bxane.nuvx.data.model.isExactArtistMatch
import com.bxane.nuvx.data.model.normalizedArtistName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistNameMatchTest {
    private fun matches(answer: String, wanted: String) = isExactArtistMatch(answer, wanted)

    @Test
    fun `the same name spelled the same way matches`() {
        assertTrue(matches("Monday Waxie", "Monday Waxie"))
        assertTrue(matches("White 2115", "White 2115"))
        assertTrue(matches("Bedoes 2115", "Bedoes 2115"))
        assertTrue(matches("vkie", "vkie"))
        assertTrue(matches("slowez", "slowez"))
    }

    @Test
    fun `case and surrounding air do not decide it`() {
        assertTrue(matches("mata", "Mata"))
        assertTrue(matches("MONDAY WAXIE", "Monday Waxie"))
        assertTrue(matches("  Monday Waxie  ", "Monday Waxie"))
    }

    @Test
    fun `spacing inside a name does not decide it`() {
        assertTrue(matches("Monday  Waxie", "Monday Waxie"))
        assertTrue(matches("Jay   Z", "Jay Z"))
    }

    @Test
    fun `accents are folded away`() {
        assertTrue(matches("Beyonce", "Beyoncé"))
        assertTrue(matches("Björk", "Bjork"))
        assertTrue(matches("Mötley Crüe", "Motley Crue"))
    }

    @Test
    fun `punctuation is not folded away`() {
        assertFalse(matches("Jay-Z", "Jay Z"))
        assertFalse(matches("Mata.", "Mata"))
        assertTrue(matches("  Mata  ", "Mata"))
    }

    @Test
    fun `a name that is a fragment of the answer does not match`() {
        for (other in listOf(
            "White 2115",
            "Bedoes 2115",
            "Blacha 2115",
            "Kuqe 2115",
        )) {
            assertFalse("opened $other for a tap on 2115", matches(other, "2115"))
        }
    }

    @Test
    fun `a longer name is not accepted for a shorter one`() {
        assertFalse(matches("Mate", "Mata"))
        assertFalse(matches("Mata", "Mate"))
        assertFalse(matches("vkie fan", "vkie"))
    }

    @Test
    fun `a name written without spaces is compared whole`() {
        assertFalse(matches("美嘉", "中島美嘉"))
        assertFalse(matches("春樹", "村上春樹"))
        assertFalse(matches("林檎", "椎名林檎"))
        assertTrue(matches("中島美嘉", "中島美嘉"))
    }

    @Test
    fun `a name in another script is not folded into another`() {
        assertFalse(matches("Мата", "Мета"))
        assertFalse(matches("माता", "मेता"))
        assertFalse(matches("Μάτα", "Μέτα"))
        assertTrue(matches("Мата", "Мата"))
    }

    @Test
    fun `the normaliser does not collapse two real artists into one`() {
        assertNotEquals(normalizedArtistName("Mata"), normalizedArtistName("Mate"))
        assertNotEquals(normalizedArtistName("2115"), normalizedArtistName("White 2115"))
        assertNotEquals(normalizedArtistName("Bedoes 2115"), normalizedArtistName("2115"))
    }

    @Test
    fun `a blank name is refused rather than folded`() {
        assertEquals("", normalizedArtistName("  "))
        assertFalse(matches("", ""))
        assertFalse(matches("   ", ""))
        assertFalse(matches("Monday Waxie", "  "))
        assertFalse(matches("  ", "Monday Waxie"))
    }

    @Test
    fun `a name in a script the canvas matcher cannot read is still readable here`() {
        val empty = ""
        for (name in listOf("Мата", "Мета", "中島美嘉", "माता")) {
            assertEquals("$name folds to nothing there", empty, name.normalizeForMatch())
        }

        assertNotEquals(normalizedArtistName("Мата"), normalizedArtistName("Мета"))
        assertNotEquals(normalizedArtistName("中島美嘉"), normalizedArtistName("椎名林檎"))
        assertNotEquals(normalizedArtistName("माता"), normalizedArtistName("मेता"))
        assertTrue(matches("Мата", "Мата"))
    }
}