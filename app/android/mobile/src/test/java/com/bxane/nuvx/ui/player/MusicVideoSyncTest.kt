package com.bxane.nuvx.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVideoSyncTest {
    @Test
    fun seekThresholdDoesNotExceed50Milliseconds() {
        assertFalse(musicVideoNeedsSeek(50L))
        assertTrue(musicVideoNeedsSeek(51L))
        assertTrue(musicVideoNeedsSeek(-51L))
        assertTrue(musicVideoNeedsSeek(Long.MIN_VALUE))
    }
}
