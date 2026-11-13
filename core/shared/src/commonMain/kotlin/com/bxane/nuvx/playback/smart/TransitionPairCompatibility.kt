

package com.bxane.nuvx.playback.smart

import kotlin.math.min

internal object TransitionPairCompatibility {
    private val KEY_INDEX = mapOf(
        "C" to 0, "C♯" to 1, "D♭" to 1, "D" to 2, "D♯" to 3, "E♭" to 3,
        "E" to 4, "F" to 5, "F♯" to 6, "G♭" to 6, "G" to 7, "G♯" to 8,
        "A♭" to 8, "A" to 9, "A♯" to 10, "B♭" to 10, "B" to 11,
    )

    const val VOCAL_CONFLICT_PROBABILITY = 0.62

    fun isUnmixablePair(analysis: TrackAnalysis, nextAnalysis: TrackAnalysis): Boolean {
        val currentBpm = analysis.bpm.orZero()
        val nextBpm = nextAnalysis.bpm.orZero()
        if (currentBpm <= 0 || nextBpm <= 0) return false
        if (stretchReachable(currentBpm, nextBpm)) return false
        val left = trustedKey(analysis)
        val right = trustedKey(nextAnalysis)
        val keyClash = left.isNotEmpty() && right.isNotEmpty() && !harmonicallyCompatible(left, right)
        val vocalConflict = analysis.vocalProbability >= VOCAL_CONFLICT_PROBABILITY &&
            nextAnalysis.vocalProbability >= VOCAL_CONFLICT_PROBABILITY
        return keyClash || vocalConflict
    }

    fun normalizedTempoRatio(currentBpm: Double, nextBpm: Double): Double {
        if (currentBpm <= 0 || nextBpm <= 0) return 1.0
        var ratio = nextBpm / currentBpm
        while (ratio > 1.5) ratio /= 2
        while (ratio < 0.67) ratio *= 2
        return ratio
    }

    private fun splitKey(key: String): Pair<Int?, String?> {
        val parts = key.trim().split(' ')
        return KEY_INDEX[parts.firstOrNull()] to parts.getOrNull(1)
    }

    fun keyDistance(left: String, right: String): Int? {
        val (leftIndex, leftMode) = splitKey(left)
        val (rightIndex, rightMode) = splitKey(right)
        if (leftIndex == null || rightIndex == null) return null
        val pitchDistance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
        return pitchDistance + if (leftMode != null && rightMode != null && leftMode != rightMode) 1 else 0
    }

    fun harmonicallyCompatible(left: String, right: String): Boolean {
        val (leftIndex, leftMode) = splitKey(left)
        val (rightIndex, rightMode) = splitKey(right)
        if (leftIndex == null || rightIndex == null) return false
        val distance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
        if (leftMode != null && rightMode != null && leftMode != rightMode) return distance <= 1
        return distance <= 2 || distance == 5
    }

    fun trustedKey(analysis: TrackAnalysis): String =
        if (analysis.key.isBlank() || analysis.keyConfidence < 0.25) "" else analysis.key

    class PairTraits(analysis: TrackAnalysis, nextAnalysis: TrackAnalysis) {
        private val currentBpm = analysis.bpm.orZero()
        private val nextBpm = nextAnalysis.bpm.orZero()

        val tempoFar = currentBpm > 0 && nextBpm > 0 && !stretchReachable(currentBpm, nextBpm)
        val keyClash: Boolean = run {
            val left = TransitionPairCompatibility.trustedKey(analysis)
            val right = TransitionPairCompatibility.trustedKey(nextAnalysis)
            left.isNotEmpty() && right.isNotEmpty() &&
                !TransitionPairCompatibility.harmonicallyCompatible(left, right)
        }
        val vocalConflict = analysis.vocalProbability >= VOCAL_CONFLICT_PROBABILITY &&
            nextAnalysis.vocalProbability >= VOCAL_CONFLICT_PROBABILITY

        val phaseLockable = currentBpm > 0 && nextBpm > 0 && !tempoFar &&
            analysis.beatConfidence.orZero() >= MIN_BEATMATCH_CONFIDENCE &&
            nextAnalysis.beatConfidence.orZero() >= MIN_BEATMATCH_CONFIDENCE

        val outgoingGrid = currentBpm > 0 && analysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE
        val outgoingMeanEnergy = meanEnergy(analysis.energyCurve)
        val incomingMeanEnergy = meanEnergy(nextAnalysis.energyCurve)
    }

    private fun meanEnergy(curve: List<EnergySample>): Double {
        var sum = 0.0
        var count = 0
        for (point in curve) {
            if (point.energy.isFinite() && point.energy >= 0) {
                sum += point.energy
                count++
            }
        }
        return if (count > 0) sum / count else 0.0
    }
}
