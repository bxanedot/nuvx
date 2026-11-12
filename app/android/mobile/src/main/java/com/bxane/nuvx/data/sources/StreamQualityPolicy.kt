package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.model.Song

internal object StreamQualityPolicy {
    private const val YOUTUBE_BEST_AAC_KBPS = 256
    private const val UPGRADE_MIN_GAIN_KBPS = 96

    fun beatsYouTubeAac(candidate: StreamFormat): Boolean =
        (candidate.kbps ?: 0) > YOUTUBE_BEST_AAC_KBPS

    fun worthSwapping(candidate: StreamFormat, playing: StreamFormat?): Boolean {
        if (playing?.isDolbyAtmos == true && !candidate.isDolbyAtmos) return false
        if (candidate.isLossless == true || candidate.isDolbyAtmos) return true
        val gain = (candidate.kbps ?: return false) - (playing?.kbps ?: return false)
        return gain >= UPGRADE_MIN_GAIN_KBPS
    }

    fun preferred(
        matches: List<Song>,
        target: TrackMatcher.Target,
        wantsLossless: Boolean,
        sameLengthSec: Int,
    ): List<Song> {
        val sameLength = matches.filter { TrackMatcher.withinSeconds(it, target, sameLengthSec) }
        val eligible = sameLength.ifEmpty { matches }
        if (DeviceCodecs.atmosWanted) {
            val immersiveFirst = eligible.sortedByDescending { it.sourceQuality == ModuleSource.DOLBY }
            if (immersiveFirst.firstOrNull()?.sourceQuality == ModuleSource.DOLBY) return immersiveFirst
        }
        if (!wantsLossless) return eligible
        return eligible.sortedByDescending {
            when {
                it.sourceQuality != ModuleSource.LOSSLESS -> 0
                it.sourceHiRes -> 2
                else -> 1
            }
        }
    }

    fun isBetter(candidate: StreamFormat, current: StreamFormat?): Boolean {
        if (current == null) return true
        if (candidate.isDolbyAtmos != current.isDolbyAtmos) return candidate.isDolbyAtmos
        if (candidate.isLossless != current.isLossless) return candidate.isLossless == true
        if (candidate.isLossless == true) {
            val depth = (candidate.bitDepth ?: 0).compareTo(current.bitDepth ?: 0)
            if (depth != 0) return depth > 0
            val rate = (candidate.sampleRateHz ?: 0).compareTo(current.sampleRateHz ?: 0)
            if (rate != 0) return rate > 0
        }
        return (candidate.kbps ?: 0) > (current.kbps ?: 0)
    }

    fun betterOf(current: SourceStream?, candidate: SourceStream): SourceStream =
        if (current == null || isBetter(candidate.format, current.format)) candidate else current
}
