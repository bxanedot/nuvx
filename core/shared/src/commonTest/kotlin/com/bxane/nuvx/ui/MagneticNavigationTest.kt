package com.bxane.nuvx.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MagneticNavigationTest {
    @Test
    fun selectedIconGrowsWithoutMoving() {
        val values = navigationMagnetValues(itemIndex = 2, selectedIndex = 2)

        assertEquals(0f, values.offsetDp)
        assertTrue(values.scale > 1f)
    }

    @Test
    fun neighboringIconsMoveAwayFromSelection() {
        val left = navigationMagnetValues(itemIndex = 1, selectedIndex = 2)
        val right = navigationMagnetValues(itemIndex = 3, selectedIndex = 2)

        assertTrue(left.offsetDp < 0f)
        assertTrue(right.offsetDp > 0f)
        assertEquals(-left.offsetDp, right.offsetDp)
    }

    @Test
    fun magneticPushFadesWithDistance() {
        val near = navigationMagnetValues(itemIndex = 3, selectedIndex = 2)
        val far = navigationMagnetValues(itemIndex = 5, selectedIndex = 2)

        assertTrue(far.offsetDp > 0f)
        assertTrue(far.offsetDp < near.offsetDp)
    }
}
