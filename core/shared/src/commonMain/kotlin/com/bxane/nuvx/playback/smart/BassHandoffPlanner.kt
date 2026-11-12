// SPDX-License-Identifier: AGPL-3.0-or-later

package com.bxane.nuvx.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal object BassHandoffPlanner {
    private const val DEFAULT_BASS_SWAP_FRACTION = 0.7

    private const val MAX_BASS_SWAP_FRACTION = 0.85

    private const val MIN_BASS_STRUCTURE_SCORE = 0.25

    private const val BASS_SWAP_MAX_SECONDS = 6.0

    fun averageEnergy(curve: List<EnergySample>, from: Double, until: Double): Double? {
        if (until <= from) return null
        var index = curve.binarySearchBy(from) { it.time }.let { if (it >= 0) it else -it - 1 }
        var sum = 0.0
        var count = 0
        while (index < curve.size && curve[index].time < until) {
            val point = curve[index++]
            if (point.time.isFinite() && point.energy.isFinite() && point.energy >= 0) {
                sum += point.energy
                count++
            }
        }
        return if (count > 0) sum / count else null
    }

    fun lowEnergyReference(curve: List<EnergySample>): Double? {
        val energies = curve.map { it.energy }.filter { it.isFinite() && it >= 0 }.sorted()
        if (energies.isEmpty()) return null
        val upperDecile = energies[(energies.lastIndex * 0.9).toInt()]
        val reference = max(upperDecile, (energies.lastOrNull() ?: 0.0) * 0.25)
        return reference.takeIf { it > 1e-9 }
    }

    private fun lowEnergyResolution(curve: List<EnergySample>): Double {
        val gaps = curve.zipWithNext { left, right -> right.time - left.time }
            .filter { it.isFinite() && it > 0 }
            .sorted()
        return gaps.getOrNull(gaps.size / 2) ?: 0.0
    }

    private fun lowEnergyChange(
        curve: List<EnergySample>,
        reference: Double?,
        at: Double,
        windowSeconds: Double,
    ): Double? {
        if (curve.isEmpty() || reference == null || windowSeconds <= 0) return null
        val before = averageEnergy(curve, at - windowSeconds, at) ?: return null
        val after = averageEnergy(curve, at, at + windowSeconds) ?: return null
        return (after / reference).coerceIn(0.0, 1.5) -
            (before / reference).coerceIn(0.0, 1.5)
    }

    fun bassSwapFractionFor(
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        transitionStart: Double,
        incomingCueTime: Double,
        outgoingBeatSeconds: Double,
        incomingBeatSeconds: Double,
        overlapSeconds: Double,
        overlapBeats: Int,
    ): Double {
        if (overlapSeconds <= 0) return DEFAULT_BASS_SWAP_FRACTION

        val latestFraction = min(MAX_BASS_SWAP_FRACTION, BASS_SWAP_MAX_SECONDS / overlapSeconds)
            .coerceIn(0.0, 1.0)
        val prior = min(DEFAULT_BASS_SWAP_FRACTION, latestFraction)
        if (overlapBeats < 2) return prior

        val earliestFraction = min(HANDOFF_FRACTION, latestFraction)
        val earliestBeat = ceil(earliestFraction * overlapBeats - 1e-9).toInt()
            .coerceIn(1, overlapBeats - 1)
        val latestBeat = floor(latestFraction * overlapBeats + 1e-9).toInt()
            .coerceIn(earliestBeat, overlapBeats - 1)
        val candidates = (earliestBeat..latestBeat).toList()
        val fallbackBeat = candidates.minWithOrNull(
            compareBy<Int> { abs(it.toDouble() / overlapBeats - prior) }
                .thenBy { if (it % 4 == 0) 0 else 1 },
        ) ?: return prior

        data class BassCandidate(val beat: Int, val score: Double)

        val outgoingReference = lowEnergyReference(analysis.lowEnergyCurve)
        val incomingReference = lowEnergyReference(nextAnalysis.lowEnergyCurve)
        val outgoingWindow = max(
            outgoingBeatSeconds,
            lowEnergyResolution(analysis.lowEnergyCurve) * 1.1,
        )
        val incomingWindow = max(
            incomingBeatSeconds,
            lowEnergyResolution(nextAnalysis.lowEnergyCurve) * 1.1,
        )
        val strongest = candidates.mapNotNull { beat ->
            val outgoingAt = transitionStart + beat * outgoingBeatSeconds
            val incomingAt = incomingCueTime + beat * incomingBeatSeconds
            val incomingChange = lowEnergyChange(
                nextAnalysis.lowEnergyCurve,
                incomingReference,
                incomingAt,
                incomingWindow,
            )
            val outgoingChange = lowEnergyChange(
                analysis.lowEnergyCurve,
                outgoingReference,
                outgoingAt,
                outgoingWindow,
            )
            if (incomingChange == null && outgoingChange == null) return@mapNotNull null
            BassCandidate(beat, (incomingChange ?: 0.0) - (outgoingChange ?: 0.0))
        }.maxWithOrNull(
            compareBy<BassCandidate> { it.score }
                .thenBy { if (it.beat % 4 == 0) 1 else 0 }
                .thenBy { -abs(it.beat.toDouble() / overlapBeats - prior) },
        )

        val chosenBeat = strongest?.takeIf { it.score >= MIN_BASS_STRUCTURE_SCORE }?.beat
            ?: fallbackBeat
        return chosenBeat.toDouble() / overlapBeats
    }
}
