package com.bxane.nuvx.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioBlockTest {

    @Test
    fun `pcm16 round trip preserves full scale, zero, and small amplitude values`() {
        val testValues = shortArrayOf(
            Short.MIN_VALUE,
            -32767,
            -1000,
            -1,
            0,
            1,
            1000,
            32766,
            Short.MAX_VALUE
        )
        val channels = 1
        val frames = testValues.size
        val inBuf = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in testValues) inBuf.putShort(v)
        inBuf.flip()

        val block = AudioBlock(channelCount = channels, capacityFrames = frames)
        val decodedFrames = PcmBoundary.decode(inBuf, PcmEncoding.PCM_16BIT, block)
        assertEquals(frames, decodedFrames)
        assertEquals(frames, block.frameCount)

        assertEquals(-1.0f, block.samples[0], 0.00001f)
        assertEquals(0.0f, block.samples[4], 0.00001f)
        assertEquals(32767.0f / 32768.0f, block.samples[8], 0.00001f)

        val outBuf = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        val encodedFrames = PcmBoundary.encode(block, PcmEncoding.PCM_16BIT, outBuf)
        assertEquals(frames, encodedFrames)
        outBuf.flip()

        for (i in testValues.indices) {
            val result = outBuf.short
            assertEquals("Mismatch at index $i for value ${testValues[i]}", testValues[i], result)
        }
    }

    @Test
    fun `pcm24 packed round trip preserves full scale, zero, and small amplitude values`() {
        val testValues = intArrayOf(
            -8388608,
            -8388607,
            -100000,
            -1,
            0,
            1,
            100000,
            8388606,
            8388607
        )
        val channels = 1
        val frames = testValues.size
        val inBuf = ByteBuffer.allocate(frames * 3).order(ByteOrder.LITTLE_ENDIAN)
        for (v in testValues) {
            inBuf.put((v and 0xFF).toByte())
            inBuf.put(((v ushr 8) and 0xFF).toByte())
            inBuf.put(((v ushr 16) and 0xFF).toByte())
        }
        inBuf.flip()

        val block = AudioBlock(channelCount = channels, capacityFrames = frames)
        val decodedFrames = PcmBoundary.decode(inBuf, PcmEncoding.PCM_24BIT_PACKED, block)
        assertEquals(frames, decodedFrames)

        assertEquals(-1.0f, block.samples[0], 0.0000001f)
        assertEquals(0.0f, block.samples[4], 0.0000001f)
        assertEquals(8388607.0f / 8388608.0f, block.samples[8], 0.0000001f)

        val outBuf = ByteBuffer.allocate(frames * 3).order(ByteOrder.LITTLE_ENDIAN)
        val encodedFrames = PcmBoundary.encode(block, PcmEncoding.PCM_24BIT_PACKED, outBuf)
        assertEquals(frames, encodedFrames)
        outBuf.flip()

        for (i in testValues.indices) {
            val b0 = outBuf.get().toInt() and 0xFF
            val b1 = outBuf.get().toInt() and 0xFF
            val b2 = outBuf.get().toInt()
            val result = (b2 shl 16) or (b1 shl 8) or b0
            assertEquals("Mismatch at index $i for 24-bit value ${testValues[i]}", testValues[i], result)
        }
    }

    @Test
    fun `pcm32 round trip preserves full scale, zero, and small amplitude values`() {
        val testValues = intArrayOf(
            Int.MIN_VALUE,
            -1000000,
            0,
            1000000,
            Int.MAX_VALUE
        )
        val channels = 1
        val frames = testValues.size
        val inBuf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in testValues) inBuf.putInt(v)
        inBuf.flip()

        val block = AudioBlock(channelCount = channels, capacityFrames = frames)
        val decodedFrames = PcmBoundary.decode(inBuf, PcmEncoding.PCM_32BIT, block)
        assertEquals(frames, decodedFrames)

        assertEquals(-1.0f, block.samples[0], 0.00001f)
        assertEquals(0.0f, block.samples[2], 0.00001f)
        assertEquals(1.0f, block.samples[4], 0.00001f)

        val outBuf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val encodedFrames = PcmBoundary.encode(block, PcmEncoding.PCM_32BIT, outBuf)
        assertEquals(frames, encodedFrames)
        outBuf.flip()

        for (i in testValues.indices) {
            val result = outBuf.int
            assertEquals("Mismatch at index $i for 32-bit value ${testValues[i]}", testValues[i], result)
        }
    }

    @Test
    fun `pcm_float round trip preserves floating point values`() {
        val testValues = floatArrayOf(-1.0f, -0.75f, -0.25f, 0.0f, 0.25f, 0.75f, 1.0f)
        val channels = 1
        val frames = testValues.size
        val inBuf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in testValues) inBuf.putFloat(v)
        inBuf.flip()

        val block = AudioBlock(channelCount = channels, capacityFrames = frames)
        val decodedFrames = PcmBoundary.decode(inBuf, PcmEncoding.PCM_FLOAT, block)
        assertEquals(frames, decodedFrames)

        for (i in testValues.indices) {
            assertEquals(testValues[i], block.samples[i], 0.000001f)
        }

        val outBuf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val encodedFrames = PcmBoundary.encode(block, PcmEncoding.PCM_FLOAT, outBuf)
        assertEquals(frames, encodedFrames)
        outBuf.flip()

        for (i in testValues.indices) {
            assertEquals(testValues[i], outBuf.float, 0.000001f)
        }
    }

    @Test
    fun `interleaved stereo ordering preserves left and right channels`() {
        val frames = 4
        val channels = 2
        val inBuf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)

        inBuf.putShort(1000); inBuf.putShort(-1000)
        inBuf.putShort(2000); inBuf.putShort(-2000)
        inBuf.putShort(3000); inBuf.putShort(-3000)
        inBuf.putShort(4000); inBuf.putShort(-4000)
        inBuf.flip()

        val block = AudioBlock(channelCount = channels, capacityFrames = frames)
        PcmBoundary.decode(inBuf, PcmEncoding.PCM_16BIT, block)

        assertEquals(frames, block.frameCount)
        assertEquals(frames * channels, block.sampleCount)

        assertEquals(1000.0f / 32768.0f, block.samples[0], 0.00001f)
        assertEquals(-1000.0f / 32768.0f, block.samples[1], 0.00001f)
        assertEquals(2000.0f / 32768.0f, block.samples[2], 0.00001f)
        assertEquals(-2000.0f / 32768.0f, block.samples[3], 0.00001f)
        assertEquals(3000.0f / 32768.0f, block.samples[4], 0.00001f)
        assertEquals(-3000.0f / 32768.0f, block.samples[5], 0.00001f)
        assertEquals(4000.0f / 32768.0f, block.samples[6], 0.00001f)
        assertEquals(-4000.0f / 32768.0f, block.samples[7], 0.00001f)

        val outBuf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        PcmBoundary.encode(block, PcmEncoding.PCM_16BIT, outBuf)
        outBuf.flip()

        assertEquals(1000.toShort(), outBuf.short)
        assertEquals((-1000).toShort(), outBuf.short)
        assertEquals(2000.toShort(), outBuf.short)
        assertEquals((-2000).toShort(), outBuf.short)
        assertEquals(3000.toShort(), outBuf.short)
        assertEquals((-3000).toShort(), outBuf.short)
        assertEquals(4000.toShort(), outBuf.short)
        assertEquals((-4000).toShort(), outBuf.short)
    }

    @Test
    fun `multi-frame buffer handles large audio blocks without distortion`() {
        val frames = 1024
        val channels = 2
        val inBuf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            inBuf.putShort((i % 30000).toShort())
            inBuf.putShort((-(i % 30000)).toShort())
        }
        inBuf.flip()

        val block = AudioBlock(channelCount = channels, capacityFrames = frames)
        val decoded = PcmBoundary.decode(inBuf, PcmEncoding.PCM_16BIT, block)
        assertEquals(frames, decoded)
        assertEquals(frames, block.frameCount)

        val outBuf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        val encoded = PcmBoundary.encode(block, PcmEncoding.PCM_16BIT, outBuf)
        assertEquals(frames, encoded)
        outBuf.flip()

        for (i in 0 until frames) {
            assertEquals((i % 30000).toShort(), outBuf.short)
            assertEquals((-(i % 30000)).toShort(), outBuf.short)
        }
    }

    @Test
    fun `incomplete trailing bytes are preserved in input buffer`() {
        val channels = 2
        val inBuf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        inBuf.putShort(100)
        inBuf.putShort(200)
        inBuf.put(0xAA.toByte())
        inBuf.put(0xBB.toByte())
        inBuf.put(0xCC.toByte())
        inBuf.flip()

        val block = AudioBlock(channelCount = channels, capacityFrames = 10)
        val decoded = PcmBoundary.decode(inBuf, PcmEncoding.PCM_16BIT, block)

        assertEquals(1, decoded)
        assertEquals(1, block.frameCount)
        assertEquals(3, inBuf.remaining())
        assertEquals(0xAA.toByte(), inBuf.get())
        assertEquals(0xBB.toByte(), inBuf.get())
        assertEquals(0xCC.toByte(), inBuf.get())
    }

    @Test
    fun `buffer with fewer bytes than one full frame decodes zero frames`() {
        val channels = 2
        val inBuf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        inBuf.put(1); inBuf.put(2); inBuf.put(3)
        inBuf.flip()

        val block = AudioBlock(channelCount = channels, capacityFrames = 10)
        val decoded = PcmBoundary.decode(inBuf, PcmEncoding.PCM_16BIT, block)

        assertEquals(0, decoded)
        assertEquals(0, block.frameCount)
        assertEquals(3, inBuf.remaining())
    }

    @Test
    fun `nan and infinity are defensively sanitized and clamped`() {
        val block = AudioBlock(channelCount = 1, capacityFrames = 6)
        block.samples[0] = Float.NaN
        block.samples[1] = Float.POSITIVE_INFINITY
        block.samples[2] = Float.NEGATIVE_INFINITY
        block.samples[3] = 2.5f
        block.samples[4] = -3.0f
        block.samples[5] = 0.5f
        block.reset(6)

        val buf16 = ByteBuffer.allocate(6 * 2).order(ByteOrder.LITTLE_ENDIAN)
        PcmBoundary.encode(block, PcmEncoding.PCM_16BIT, buf16)
        buf16.flip()

        assertEquals(0.toShort(), buf16.short)
        assertEquals(Short.MAX_VALUE, buf16.short)
        assertEquals(Short.MIN_VALUE, buf16.short)
        assertEquals(Short.MAX_VALUE, buf16.short)
        assertEquals(Short.MIN_VALUE, buf16.short)
        assertEquals(16384.toShort(), buf16.short)

        val buf24 = ByteBuffer.allocate(6 * 3).order(ByteOrder.LITTLE_ENDIAN)
        PcmBoundary.encode(block, PcmEncoding.PCM_24BIT_PACKED, buf24)
        buf24.flip()

        fun read24(buf: ByteBuffer): Int {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt()
            return (b2 shl 16) or (b1 shl 8) or b0
        }

        assertEquals(0, read24(buf24))
        assertEquals(8388607, read24(buf24))
        assertEquals(-8388608, read24(buf24))
        assertEquals(8388607, read24(buf24))
        assertEquals(-8388608, read24(buf24))

        val bufFloat = ByteBuffer.allocate(6 * 4).order(ByteOrder.LITTLE_ENDIAN)
        PcmBoundary.encode(block, PcmEncoding.PCM_FLOAT, bufFloat)
        bufFloat.flip()

        assertEquals(0.0f, bufFloat.float, 0.0f)
        assertEquals(1.0f, bufFloat.float, 0.0f)
        assertEquals(-1.0f, bufFloat.float, 0.0f)
        assertEquals(2.5f, bufFloat.float, 0.0f)
        assertEquals(-3.0f, bufFloat.float, 0.0f)
        assertEquals(0.5f, bufFloat.float, 0.0f)
    }

    @Test
    fun `audio block copy and frame slicing`() {
        val src = AudioBlock(channelCount = 2, capacityFrames = 10)
        for (i in 0 until 10 * 2) {
            src.samples[i] = i.toFloat()
        }
        src.reset(10)

        val dst = AudioBlock(channelCount = 2, capacityFrames = 10)
        dst.copyFrom(src, sourceStartFrame = 2, destStartFrame = 1, frames = 4)

        assertEquals(5, dst.frameCount)
        assertEquals(0.0f, dst.samples[0], 0.0f)
        assertEquals(0.0f, dst.samples[1], 0.0f)

        assertEquals(4.0f, dst.samples[2], 0.0f)
        assertEquals(5.0f, dst.samples[3], 0.0f)
        assertEquals(6.0f, dst.samples[4], 0.0f)
        assertEquals(7.0f, dst.samples[5], 0.0f)
    }

    @Test
    fun `audio block reuses preallocated memory without allocation`() {
        val block = AudioBlock(channelCount = 2, capacityFrames = 256)
        val initialArray = block.samples

        val inBuf = ByteBuffer.allocate(64 * 4).order(ByteOrder.LITTLE_ENDIAN)
        PcmBoundary.decode(inBuf, PcmEncoding.PCM_16BIT, block)
        assertEquals(64, block.frameCount)
        assertTrue("Underlying array must not be reallocated", block.samples === initialArray)

        block.reset(0)
        assertEquals(0, block.frameCount)
        assertTrue("Underlying array must remain identical after reset", block.samples === initialArray)
    }
}
