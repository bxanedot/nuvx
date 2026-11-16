package com.licht.nuvx.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAudioDefaultsTest {
    @Test
    fun spatialWideningIsOffByDefaultOnlyForAuto() {
        assertFalse(defaultSpatialAudioEnabled("com.bxane.nuvx.auto"))
        assertTrue(defaultSpatialAudioEnabled("com.bxane.nuvx"))
        assertTrue(defaultSpatialAudioEnabled("com.bxane.nuvx.tv"))
        assertTrue(defaultSpatialAudioEnabled("com.bxane.nuvx.dev"))
    }
}
