package com.bxane.nuvx.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunityCanvasIndexTest {
    @Test
    fun readsNuvxIndexWithoutExpectingAnItemsWrapper() {
        val entries = CommunityCanvas.parseIndex("""{"song title|the artist":"abcDEF123_-"}""")
        assertEquals(1, entries.size)
        assertEquals("song title", entries.single().song)
        assertEquals("the artist", entries.single().artist)
        assertEquals("https://canvas.nuvx.lol/abcDEF123_-.mp4", entries.single().url)
    }

    @Test
    fun rejectsMalformedKeysAndNonAssetValues() {
        val entries = CommunityCanvas.parseIndex(
            """{"missing separator":"abcDEF123_-","song|artist":"https://example.invalid/clip","|artist":"abcDEF123_-"}"""
        )
        assertTrue(entries.isEmpty())
    }
}
