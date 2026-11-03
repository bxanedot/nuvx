package com.bxane.nuvx.desktop

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasVideoTest {
    @Test fun rv32BufferConvertsPaddedBgrPixels() {
        // One pixel per row, 8-byte pitch: row 0 pixel, 4 bytes of junk padding, row 1 pixel.
        val image = rv32Image(ByteBuffer.wrap(byteArrayOf(3, 2, 1, 0, 9, 9, 9, 9, 6, 5, 4, 0)), 1, 2, 8)
        assertEquals(0xff010203.toInt(), image.getRGB(0, 0))
        assertEquals(0xff040506.toInt(), image.getRGB(0, 1))
    }
}
