package com.bxane.nuvx.ui

import com.bxane.nuvx.core.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkRestingScaleTest {
    @Test fun onlyPausedAndroidSongsContract() {
        assertEquals(0.86f, artworkRestingScale(true, false, false, MediaKind.SONG))
        assertEquals(1f, artworkRestingScale(true, false, true, MediaKind.SONG))
        assertEquals(1f, artworkRestingScale(true, false, false, MediaKind.VIDEO))
        assertEquals(1f, artworkRestingScale(true, false, false, null))
        assertEquals(1f, artworkRestingScale(true, true, false, MediaKind.SONG))
        assertEquals(1f, artworkRestingScale(false, false, false, MediaKind.SONG))
    }
}
