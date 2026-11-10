package com.bxane.nuvx

import com.bxane.nuvx.data.canvas.CommunityCanvas
import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityCanvasTest {
    @Test
    fun keyMatchesWorker() {
        assertEquals("blinding lights|the weeknd", CommunityCanvas.key("  Blinding   Lights ", "The Weeknd - Topic"))
        assertEquals("a|b - topic x", CommunityCanvas.key("A", "B - Topic X"))
    }
}
