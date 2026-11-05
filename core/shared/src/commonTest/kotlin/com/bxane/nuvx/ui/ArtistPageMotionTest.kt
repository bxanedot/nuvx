package com.bxane.nuvx.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistPageMotionTest {
    @Test fun artistVideoFilterTransitionRespectsReducedMotion() {
        assertTrue(artistVideoFilterUsesTransition(reduceMotion = false))
        assertFalse(artistVideoFilterUsesTransition(reduceMotion = true))
    }
}
