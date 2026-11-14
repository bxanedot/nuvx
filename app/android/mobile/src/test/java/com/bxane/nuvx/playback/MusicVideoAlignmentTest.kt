package com.bxane.nuvx.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVideoAlignmentTest {

    private fun signal(length: Int, seed: Int): FloatArray {
        var state = seed
        return FloatArray(length) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            state.toUInt().toFloat() / UInt.MAX_VALUE.toFloat()
        }
    }

    @Test
    fun findsALaterVideoStartWithADifferentDuration() {
        val source = signal(700, 1)
        var target = signal(65, 2)
        target += source
        target += signal(90, 3)
        assertEquals(650L, musicVideoAlignmentOffsetMs(source, target))
    }

    @Test
    fun findsAnEarlierVideoStart() {
        val source = signal(765, 4)
        val target = source.copyOfRange(65, source.size)
        assertEquals(-650L, musicVideoAlignmentOffsetMs(source, target))
    }

    @Test
    fun findsALongVideoIntroAndUsesTheOverlappingAudio() {
        val source = signal(6_500, 10)
        var target = signal(4_500, 11)
        target += source
        target += signal(200, 12)
        assertEquals(45_000L, musicVideoAlignmentOffsetMs(source, target))
    }

    @Test
    fun rejectsRepeatedAudioWhenMultipleOffsetsFitEqually() {
        val pattern = signal(200, 13)
        val source = FloatArray(pattern.size * 6) { pattern[it % pattern.size] }
        var target = signal(65, 14)
        target += pattern
        target += source
        target += signal(90, 15)
        assertNull(musicVideoAlignmentOffsetMs(source, target))
    }

    @Test
    fun rejectsSilence() {
        assertNull(musicVideoAlignmentOffsetMs(FloatArray(700), FloatArray(800)))
    }

    @Test
    fun rejectsUnrelatedAudio() {
        assertNull(musicVideoAlignmentOffsetMs(signal(700, 8), signal(800, 9)))
    }

    @Test
    fun keepsOffsetsSeparateForEachAudioVideoPair() {
        MusicVideoAlignment.remember("audio-a", "video-a", 650)
        MusicVideoAlignment.remember("audio-b", "video-b", -1_250)
        assertEquals(650L, MusicVideoAlignment.cached("audio-a", "video-a"))
        assertEquals(-650L, MusicVideoAlignment.cached("video-a", "audio-a"))
        assertEquals(-1_250L, MusicVideoAlignment.cached("audio-b", "video-b"))
        assertNull(MusicVideoAlignment.cached("audio-a", "video-b"))
        assertEquals(0L, MusicVideoAlignment.cached("same", "same"))
    }

    @Test
    fun createsAMonoEnergyEnvelopeFromStereoPcm() {
        val samples = FloatArray(200) { 0.5f }
        val envelope = musicVideoEnergyEnvelope(samples, 2, 1_000)
        assertEquals(7, envelope.size)
        assertTrue(envelope.all { kotlin.math.abs(it - 0.5f) < 1e-6f })
    }

    @Test
    fun clampsTheVideoTargetToTheStartOfTheFile() {
        assertEquals(5_650L, musicVideoTargetMs(5_000, 650))
        assertEquals(4_350L, musicVideoTargetMs(5_000, -650))
        assertEquals(0L, musicVideoTargetMs(300, -650))
    }
}
