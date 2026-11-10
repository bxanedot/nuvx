package com.bxane.nuvx

import com.bxane.nuvx.ui.player.SHARE_CARD_CHAR_BUDGET
import com.bxane.nuvx.ui.player.fitsOnCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsShareCardTest {
    private val shortLine = "Nie dzwoń do mnie teraz, nie przeszkadzaj mi"

    @Test fun fiveShortLinesStillFit() {
        var taken = 0
        repeat(5) {
            assertTrue("line ${it + 1} must be pickable", fitsOnCard(taken, shortLine))
            taken += shortLine.length
        }
    }

    @Test fun theSixthLineIsRefusedRatherThanShrinkingTheType() {
        val taken = shortLine.length * 5
        assertFalse(fitsOnCard(taken, shortLine))
    }

    @Test fun aLineOverTheBudgetOnItsOwnIsRefused() {
        assertFalse(fitsOnCard(0, "x".repeat(SHARE_CARD_CHAR_BUDGET + 1)))
    }

    @Test fun theBudgetIsInclusiveByOneCharacter() {
        assertTrue(fitsOnCard(SHARE_CARD_CHAR_BUDGET, ""))
        assertFalse(fitsOnCard(SHARE_CARD_CHAR_BUDGET, "x"))
    }

    @Test fun aLineThePanelHasNoWordsForCostsNothing() {
        assertTrue(fitsOnCard(SHARE_CARD_CHAR_BUDGET, null))
    }
}
