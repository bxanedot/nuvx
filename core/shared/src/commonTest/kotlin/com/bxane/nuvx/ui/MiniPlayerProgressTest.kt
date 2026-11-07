package com.bxane.nuvx.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MiniPlayerProgressTest {
    @Test fun unknownDurationHasNoProgress() {
        assertEquals(0f, miniPlayerProgressFraction(500, 0))
        assertEquals(0f, miniPlayerProgressFraction(500, -1))
    }

    @Test fun positionMapsToClampedFraction() {
        assertEquals(0f, miniPlayerProgressFraction(0, 100))
        assertEquals(0f, miniPlayerProgressFraction(-10, 100))
        assertEquals(0.5f, miniPlayerProgressFraction(50, 100))
        assertEquals(1f, miniPlayerProgressFraction(150, 100))
    }

    @Test fun miniPlayerGesturesRespectDistanceAndDirection() {
        assertEquals(MiniPlayerGestureAction.NONE, miniPlayerGestureAction(-71f, 0f, 72f))
        assertEquals(MiniPlayerGestureAction.NEXT, miniPlayerGestureAction(-73f, 0f, 72f))
        assertEquals(MiniPlayerGestureAction.PREVIOUS, miniPlayerGestureAction(73f, 0f, 72f))
        assertEquals(MiniPlayerGestureAction.OPEN, miniPlayerGestureAction(40f, -72f, 72f))
        assertEquals(MiniPlayerGestureAction.NONE, miniPlayerGestureAction(60f, -80f, 72f))
        assertEquals(MiniPlayerGestureAction.NONE, miniPlayerGestureAction(0f, 72f, 72f))
    }
}
