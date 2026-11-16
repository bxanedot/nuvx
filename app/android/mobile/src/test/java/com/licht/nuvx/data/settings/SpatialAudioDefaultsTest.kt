package com.licht.nuvx.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAudioDefaultsTest {
    @Test
    fun spatialWideningIsOffByDefaultOnlyForAuto() {
        assertFalse(defaultSpatialAudioEnabled("com.licht.nuvx.auto"))
        assertTrue(defaultSpatialAudioEnabled("com.licht.nuvx"))
        assertTrue(defaultSpatialAudioEnabled("com.licht.nuvx.tv"))
        assertTrue(defaultSpatialAudioEnabled("com.licht.nuvx.dev"))
    }
}
