package com.bxane.nuvx

import com.bxane.nuvx.data.model.ArtistNameIndex
import com.bxane.nuvx.data.model.ArtistRef
import com.bxane.nuvx.data.model.completeArtistCredits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArtistNameIndexTest {
    @Before
    fun forget() = ArtistNameIndex.forget()

    private fun stated(name: String, id: String) = ArtistRef(name, id)

    @Test
    fun `a channel stated by one row fills a name another row left unlinked`() {
        ArtistNameIndex.record(listOf(stated("White 2115", "UC_white")))

        val completed = completeArtistCredits(
            "CBW, White 2115, slowez",
            listOf(stated("CBW", "UC_cbw"), stated("slowez", "UC_slowez")),
        )

        assertEquals(
            listOf("CBW", "White 2115", "slowez"),
            completed.map { it.name },
        )
        assertEquals("UC_white", completed.first { it.name == "White 2115" }.browseId)
    }

    @Test
    fun `a word joining two credited artists is punctuation and stays punctuation`() {
        ArtistNameIndex.record(
            listOf(stated("Bedoes 2115", "UC_b"), stated("Lanek", "UC_l")),
        )
        repeat(2) {
            completeArtistCredits(
                "Bedoes 2115 and Lanek",
                listOf(stated("Bedoes 2115", "UC_b"), stated("Lanek", "UC_l")),
            )
        }
        assertTrue(ArtistNameIndex.isJoiner("and"))

        val completed = completeArtistCredits(
            "Bedoes 2115 and Lanek",
            listOf(stated("Bedoes 2115", "UC_b")),
        )

        assertEquals(listOf("Bedoes 2115", "Lanek"), completed.map { it.name })
        assertEquals("UC_l", completed.first { it.name == "Lanek" }.browseId)
    }

    @Test
    fun `a leftover nobody has ever linked is left for the reader to search`() {
        val completed = completeArtistCredits("CBW and Monday Waxie", listOf(stated("CBW", "UC_cbw")))
        assertEquals(listOf("CBW"), completed.map { it.name })
    }

    @Test
    fun `a name already carrying a channel is not looked up again`() {
        ArtistNameIndex.record(listOf(stated("Monday Waxie", "UC_stale")))
        val completed = completeArtistCredits(
            "CBW, Monday Waxie",
            listOf(stated("CBW", "UC_cbw"), stated("Monday Waxie", "UC_fresh")),
        )
        assertEquals(2, completed.size)
        assertEquals("UC_fresh", completed.first { it.name == "Monday Waxie" }.browseId)
    }

    @Test
    fun `two channels under one name are never resolved to either`() {
        ArtistNameIndex.record(listOf(stated("Mate", "UC_one")))
        ArtistNameIndex.record(listOf(stated("Mate", "UC_two")))

        assertNull(ArtistNameIndex.lookup("Mate"))
        val completed = completeArtistCredits("Bedoes & Mate", listOf(stated("Bedoes", "UC_b")))
        assertEquals(listOf("Bedoes"), completed.map { it.name })
    }

    @Test
    fun `the same channel seen twice is still one answer`() {
        ArtistNameIndex.record(listOf(stated("Mata", "UC_mata")))
        ArtistNameIndex.record(listOf(stated("Mata", "UC_mata")))
        assertEquals("UC_mata", ArtistNameIndex.lookup("Mata"))
    }

    @Test
    fun `lookup ignores case and surrounding air`() {
        ArtistNameIndex.record(listOf(stated("Monday Waxie", "UC_mw")))
        assertEquals("UC_mw", ArtistNameIndex.lookup("monday waxie"))
        assertEquals("UC_mw", ArtistNameIndex.lookup("  Monday Waxie "))
    }

    @Test
    fun `nothing learned means nothing offered`() {
        assertNull(ArtistNameIndex.lookup("Monday Waxie"))
        assertFalse(ArtistNameIndex.isJoiner("and"))
    }

    @Test
    fun `a blank line keeps what it was given rather than dropping it`() {
        ArtistNameIndex.record(listOf(stated("CBW", "UC_cbw")))
        assertEquals(
            listOf("CBW"),
            completeArtistCredits("   ", listOf(stated("CBW", "UC_cbw"))).map { it.name },
        )
        assertEquals(emptyList<ArtistRef>(), completeArtistCredits("", emptyList()))
    }

    @Test
    fun `an album and a credit never borrow each other's channel`() {
        ArtistNameIndex.record(listOf(stated("CBW", "UC_cbw")))
        val completed = completeArtistCredits("CBW • Undercover", listOf(stated("CBW", "UC_cbw")))
        assertEquals(listOf("CBW"), completed.map { it.name })
    }

    @Test
    fun `a completion never invents a second name`() {
        ArtistNameIndex.record(listOf(stated("A", "UC_a"), stated("B", "UC_b")))
        val completed = completeArtistCredits("A", listOf(stated("A", "UC_a")))
        assertTrue("completed past what the line says", completed.all { it.name in listOf("A") })
    }
}