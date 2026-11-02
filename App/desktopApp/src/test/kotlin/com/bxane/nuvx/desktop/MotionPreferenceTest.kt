package com.bxane.nuvx.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionPreferenceTest {
    @Test fun systemDisabledAnimationsCannotBeOverriddenOff() {
        assertTrue(desktopReducedMotion("false", false))
    }
    @Test fun explicitOptInWorksWithoutOsSupport() {
        assertTrue(desktopReducedMotion("true", null))
    }
    @Test fun missingOrInvalidOverrideDoesNotDisableAnimations() {
        assertFalse(desktopReducedMotion(null, null))
        assertFalse(desktopReducedMotion("invalid", true))
    }
}
