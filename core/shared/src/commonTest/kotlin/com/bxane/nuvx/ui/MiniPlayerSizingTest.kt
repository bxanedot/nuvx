package com.bxane.nuvx.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MiniPlayerSizingTest {
    @Test fun compactPlayerUsesTheSameHeightAcrossFormFactors() {
        assertEquals(56, MINI_PLAYER_HEIGHT_DP)
    }
}