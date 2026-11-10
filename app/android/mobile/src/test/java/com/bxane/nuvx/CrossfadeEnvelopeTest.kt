package com.bxane.nuvx

import com.bxane.nuvx.playback.CrossfadeEnvelope
import com.bxane.nuvx.playback.CrossfadeRender
import com.bxane.nuvx.playback.TransitionFilters
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeEnvelopeTest {

    @Test
    fun `equal power gains keep their endpoints and midpoint`() {
        val envelope = CrossfadeEnvelope(TransitionFilters.None, { false }, { CrossfadeRender() })

        assertEquals(0f, envelope.mixRise(0f), 0.000001f)
        assertEquals(0.70710677f, envelope.mixRise(0.5f), 0.000001f)
        assertEquals(1f, envelope.mixRise(1f), 0.000001f)
        assertEquals(1f, envelope.mixFall(0f), 0.000001f)
        assertEquals(0.70710677f, envelope.mixFall(0.5f), 0.000001f)
        assertEquals(0f, envelope.mixFall(1f), 0.000001f)
    }

    @Test
    fun `filter lead times follow the live handoff state`() {
        var handedOff = false
        val envelope = CrossfadeEnvelope(RecordingFilters(), { handedOff }, { CrossfadeRender() })

        assertEquals(31L, envelope.tracks.incomingLeadMs())
        assertEquals(17L, envelope.tracks.outgoingLeadMs())
        handedOff = true
        assertEquals(17L, envelope.tracks.incomingLeadMs())
        assertEquals(31L, envelope.tracks.outgoingLeadMs())
    }

    @Test
    fun `different deck positions apply each filter once on both sides of handoff`() {
        var handedOff = false
        val filters = RecordingFilters()
        val render = CrossfadeRender(vocalOverlap = 0.5)
        val envelope = CrossfadeEnvelope(filters, { handedOff }, { render })

        envelope.rideFilters(incomingAt = 0.25f, outgoingAt = 0.75f)
        assertEquals(listOf("outgoing", "incoming"), filters.calls)
        filters.calls.clear()
        handedOff = true
        envelope.rideFilters(incomingAt = 0.25f, outgoingAt = 0.75f)
        assertEquals(listOf("incoming", "outgoing"), filters.calls)
    }

    private class RecordingFilters : TransitionFilters {
        val calls = mutableListOf<String>()

        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            calls += "incoming"
        }

        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            calls += "outgoing"
        }

        override fun incomingLeadMs(): Long = 17L

        override fun outgoingLeadMs(): Long = 31L
    }
}
