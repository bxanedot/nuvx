package com.bxane.nuvx.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Test

class NuvxIntroTest {
    @Test
    fun longFrameAdvancesIntroToItsEnd() {
        assertEquals(1950f, advanceIntroClock(0f, 12_000_000_000L), 0f)
    }
}
