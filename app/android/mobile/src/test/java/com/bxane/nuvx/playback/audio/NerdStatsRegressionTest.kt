// SPDX-License-Identifier: Apache-2.0
package com.bxane.nuvx.playback.audio

import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TelemetryProvenance
import com.bxane.nuvx.data.sources.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NerdStatsRegressionTest {

    @Before
    fun setUp() {
        NerdStats.forgetLastSession()
    }

    private fun snapshot(mediaId: String?, mime: String = "audio/opus", kbps: Int? = 160) =
        NerdStats.Snapshot(
            mimeType = mime,
            bitrateKbps = kbps,
            sampleRateHz = 48_000,
            channels = 2,
            mediaId = mediaId,
        )

    @Test
    fun trackTransition_clearsSnapshotUntilTheNewTrackPublishes() {
        NerdStats.onTrackTransition("trackA")
        assertTrue(NerdStats.publish(snapshot("trackA"), NerdStats.currentGeneration))
        assertNotNull(NerdStats.current.value)

        NerdStats.onTrackTransition("trackB")
        assertNull(NerdStats.current.value)

        assertTrue(NerdStats.publish(snapshot("trackB", "audio/flac", null), NerdStats.currentGeneration))
        assertEquals("trackB", NerdStats.current.value?.mediaId)
    }

    @Test
    fun publishOvertakenByTrackChange_isDropped() {
        NerdStats.onTrackTransition("track1")
        val startedUnder = NerdStats.currentGeneration
        NerdStats.onTrackTransition("track2")

        assertFalse(NerdStats.publish(snapshot("track1"), startedUnder))
        assertNull(NerdStats.current.value)
    }

    @Test
    fun snapshotForAnotherItem_cannotReplaceThePlayingOne() {
        NerdStats.onTrackTransition("playing")
        val gen = NerdStats.currentGeneration
        assertTrue(NerdStats.publish(snapshot("playing", "audio/flac", null), gen))

        assertFalse(NerdStats.publish(snapshot("incoming"), gen))
        assertEquals("playing", NerdStats.current.value?.mediaId)
    }

    @Test
    fun forgetLastSession_invalidatesPublishesStartedBeforeIt() {
        NerdStats.onTrackTransition("track")
        val oldGen = NerdStats.currentGeneration
        NerdStats.forgetLastSession()

        assertTrue(NerdStats.currentGeneration > oldGen)
        assertFalse(NerdStats.publish(snapshot("track"), oldGen))
        assertNull(NerdStats.current.value)
    }

    @Test
    fun readAheadDeclaration_survivesTheTrackBecomingCurrent() {
        NerdStats.onSourceStream("next", StreamFormat(codec = "eac3-joc", kbps = 768), "Addon")
        NerdStats.onStreamPicked("next", 160)

        NerdStats.onTrackTransition("next")

        assertTrue(NerdStats.declaredFormat("next")?.isDolbyAtmos == true)
        assertEquals("Addon", NerdStats.sourceFor("next"))
        assertEquals(160, NerdStats.pickedBitrateKbps("next"))
    }

    @Test
    fun losslessUpgradeWithoutABitrate_doesNotInheritThePickedOpus() {
        NerdStats.onStreamPicked("track", 160, "YouTube")
        NerdStats.onSourceStream("track", StreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24), "Tidal")

        assertNull(NerdStats.sourceBitrate("track") { 4_000 })
    }

    @Test
    fun revertedUpgrade_fallsBackToThePickedStream() {
        NerdStats.onStreamPicked("track", 160, "YouTube")
        NerdStats.onSourceStream("track", StreamFormat(codec = "flac"), "Tidal")

        NerdStats.clearDeclared("track")

        assertEquals(160 to TelemetryProvenance.AUTHORITATIVE, NerdStats.sourceBitrate("track") { null })
    }

    @Test
    fun statedBitrate_isAuthoritativeAndNotMeasured() {
        NerdStats.onSourceStream("track", StreamFormat(codec = "mp3", kbps = 256), "Addon")

        var measured = false
        val result = NerdStats.sourceBitrate("track") { measured = true; 999 }

        assertEquals(256 to TelemetryProvenance.AUTHORITATIVE, result)
        assertFalse(measured)
    }

    @Test
    fun tierGuessedBitrate_givesWayToTheMeasuredOne() {
        NerdStats.onStreamPicked("track", 160, "YouTube")
        NerdStats.onSourceStream("track", StreamFormat(codec = "mp3", kbps = 320, kbpsInferred = true), "Addon")

        assertEquals(129 to TelemetryProvenance.MEASURED, NerdStats.sourceBitrate("track") { 129 })
    }

    @Test
    fun tierGuessedBitrate_isOnlyDerivedUntilMeasured() {
        NerdStats.onStreamPicked("track", 160, "YouTube")
        NerdStats.onSourceStream("track", StreamFormat(codec = "mp3", kbps = 320, kbpsInferred = true), "Addon")

        assertEquals(320 to TelemetryProvenance.DERIVED, NerdStats.sourceBitrate("track") { null })
    }

    @Test
    fun nothingRecorded_usesTheMeasuredSize() {
        assertEquals(170 to TelemetryProvenance.MEASURED, NerdStats.sourceBitrate("cached") { 170 })
        assertNull(NerdStats.sourceBitrate("cached") { null })
    }

    @Test
    fun hiResFollowsTheMeasuredRate() {
        for (rate in listOf(44_100, 48_000, 96_000, 176_400, 192_000)) {
            val s = NerdStats.Snapshot(
                mimeType = "audio/flac",
                bitrateKbps = null,
                sampleRateHz = rate,
                channels = 2,
                bitDepth = 16,
            )
            assertTrue(s.isLossless)
            assertEquals("$rate Hz", rate > 48_000, s.isHiRes)
        }
    }
}
