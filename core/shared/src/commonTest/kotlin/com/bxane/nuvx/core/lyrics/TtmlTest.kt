package com.bxane.nuvx.core.lyrics

import com.bxane.nuvx.core.Lyrics
import com.bxane.nuvx.core.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TtmlTest {
    @Test fun parsesOnlySuppliedLineAndWordTimes() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00:01.000" end="00:00:03.000"><span begin="00:00:01.000" end="00:00:01.700">silver </span><span begin="00:00:01.700" end="00:00:03.000">comet &amp; light</span></p>
        </div></body></tt>"""

        val lyrics = requireNotNull(Ttml.parse(xml))
        assertTrue(lyrics.wordSynced)
        assertEquals(1_000L, lyrics.lines.single().startMs)
        assertEquals("silver comet & light", lyrics.lines.single().text)
        assertEquals(listOf("silver ", "comet & light"), lyrics.lines.single().words.map { it.text })
        assertEquals(listOf(1_000L, 1_700L), lyrics.lines.single().words.map { it.startMs })
    }

    @Test fun lineOnlyTtmlDoesNotClaimWordSync() {
        val lyrics = Ttml.parse("""<tt><body><p begin="0:02.500">fictional phrase</p></body></tt>""")

        assertEquals(Lyrics.Synced(listOf(LyricLine(2_500, "fictional phrase")), wordSynced = false), lyrics)
    }

    @Test fun untimedTextRemainsVisibleWithoutBorrowingAnotherWordsTimestamp() {
        val xml = """<tt><body><p begin="00:00:01.000"><span begin="00:00:01.000">timed </span>untimed</p></body></tt>"""

        val line = requireNotNull(Ttml.parse(xml)).lines.single()
        assertEquals("timed untimed", line.text)
        assertEquals(listOf("timed "), line.words.map { it.text })
        assertEquals(listOf(1_000L), line.words.map { it.startMs })
    }

    @Test fun offsetsApplyToSourceTimesAndUnsafeOrUnsupportedXmlIsRejected() {
        val xml = """<tt><body><p begin="00:00:02.000"><span begin="00:00:02.000">placeholder</span></p></body></tt>"""
        assertEquals(1_500L, Ttml.parse(xml, -500)?.lines?.single()?.words?.single()?.startMs)
        assertNull(Ttml.parse("""<!DOCTYPE tt [<!ENTITY x SYSTEM "file:///secret">]><tt><p begin="1s">&x;</p></tt>"""))
        assertNull(Ttml.parse("""<tt><body><p begin="not-a-time">placeholder</p></body></tt>"""))
        assertNull(Ttml.parse("<tt>${"x".repeat(1_048_577)}</tt>"))
    }

    @Test fun rejectsOffsetsThatWouldCreateNegativeSourceTimes() {
        val xml = """<tt><body><p begin="00:00:01.000"><span begin="00:00:01.000">placeholder</span></p></body></tt>"""

        assertNull(Ttml.parse(xml, -1_001))
    }
}
