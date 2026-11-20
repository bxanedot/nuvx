package com.licht.nuvx.ui.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFocusPolicyTest {
    @Test
    fun dpadFocusRestoresThePreviousSurfaceAfterModalDismissal() {
        assertEquals(TvFocusTarget.Scrubber, tvFocusTarget(true, false, false))
        assertNull(tvFocusTarget(true, false, true), "menu should retain focus")
        assertEquals(TvFocusTarget.Scrubber, tvFocusTarget(true, false, false), "focus returns to transport")
        assertEquals(TvFocusTarget.UpNext, tvFocusTarget(true, true, false))
        assertNull(tvFocusTarget(true, true, true), "sheet overrides queue focus")
        assertEquals(TvFocusTarget.UpNext, tvFocusTarget(true, true, false), "queue focus returns on dismiss")
        assertEquals(TvFocusTarget.Stage, tvFocusTarget(false, false, false), "hidden controls focus the stage")
        assertNull(tvFocusTarget(false, false, true))
    }

    @Test
    fun staleQueueStateDoesNotFocusHiddenQueue() {
        assertEquals(TvFocusTarget.Stage, tvFocusTarget(false, true, false))
        assertEquals(TvFocusTarget.Scrubber, tvFocusTarget(true, false, false))
    }
}
