package com.bxane.nuvx

import com.bxane.nuvx.data.model.ArtistNameIndex
import com.bxane.nuvx.data.model.ArtistRef
import com.bxane.nuvx.data.model.completeArtistCredits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JoinerLanguageTest {
    @Before
    fun forget() = ArtistNameIndex.forget()

    private fun stated(name: String, id: String) = ArtistRef(name, id)

    private fun learn(linked: List<Pair<String, String>>, line: String) {
        ArtistNameIndex.record(linked.map { (name, id) -> stated(name, id) })
        completeArtistCredits(line, linked.map { (name, id) -> stated(name, id) })
    }

    @Test
    fun `a mark joiner is believed the first time it is seen`() {
        learn(listOf("A" to "UC_a", "B" to "UC_b"), "A, B, C")
        assertTrue(ArtistNameIndex.isJoiner(","))
    }

    @Test
    fun `a word joiner in any language is learned from seeing it twice`() {
        for (word in listOf("and", "und", "et", "y", "e", "och", "i", "a")) {
            ArtistNameIndex.forget()
            val line = "Alpha $word Beta"
            learn(listOf("Alpha" to "UC_a", "Beta" to "UC_b"), line)
            learn(listOf("Alpha" to "UC_a", "Beta" to "UC_b"), line)

            assertTrue("`$word` was not learned as a joiner", ArtistNameIndex.isJoiner(word))
            val completed = completeArtistCredits(line, listOf(stated("Alpha", "UC_a")))
            assertEquals(
                "`$word` did not let Beta through",
                listOf("Alpha", "Beta"),
                completed.map { it.name },
            )
            assertEquals("UC_b", completed.first { it.name == "Beta" }.browseId)
        }
    }

    @Test
    fun `a name written without spaces is not learned as a joiner from one sighting`() {
        ArtistNameIndex.record(listOf(stated("宇多田ヒカル", "UC_uta"), stated("中島美嘉", "UC_naka")))
        completeArtistCredits(
            "宇多田ヒカル・YOASOBI・中島美嘉",
            listOf(stated("宇多田ヒカル", "UC_uta"), stated("YOASOBI", "UC_y")),
        )

        assertFalse(
            "a single sighting was enough to call a name a joiner",
            ArtistNameIndex.isJoiner("中島美嘉"),
        )
        val completed = completeArtistCredits(
            "宇多田ヒカル・YOASOBI・中島美嘉",
            listOf(stated("宇多田ヒカル", "UC_uta"), stated("YOASOBI", "UC_y")),
        )
        assertEquals("UC_naka", completed.first { it.name == "中島美嘉" }.browseId)
    }

    @Test
    fun `a repeated unlinked name is learned after the second sighting, and no sooner`() {
        ArtistNameIndex.record(listOf(stated("A", "UC_a"), stated("Zed", "UC_zed")))
        val line = "A and Zed"
        completeArtistCredits(line, listOf(stated("A", "UC_a"), stated("Zed", "UC_z")))
        assertFalse("learned from one sighting", ArtistNameIndex.isJoiner("and"))
    }

    @Test
    fun `a phrase is never learned as a joiner however often it is seen`() {
        ArtistNameIndex.record(listOf(stated("A", "UC_a"), stated("Zed", "UC_z")))
        repeat(5) {
            completeArtistCredits("A and Zed", listOf(stated("A", "UC_a"), stated("Zed", "UC_z")))
        }
        assertFalse(ArtistNameIndex.isJoiner("and Zed"))
    }

    @Test
    fun `a cjk name and a cjk joiner of the same width are told apart by repetition`() {
        ArtistNameIndex.record(listOf(stated("中島美嘉", "UC_naka"), stated("大橋トリオ", "UC_oh")))
        repeat(3) {
            completeArtistCredits(
                "中島美嘉・大橋トリオ・椎名林檎",
                listOf(stated("中島美嘉", "UC_naka"), stated("大橋トリオ", "UC_oh")),
            )
        }
        assertTrue(ArtistNameIndex.isJoiner("・"))
        assertFalse(ArtistNameIndex.isJoiner("椎名林檎"))
    }
}