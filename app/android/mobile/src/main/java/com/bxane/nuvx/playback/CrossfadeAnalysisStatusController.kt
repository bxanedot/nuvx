package com.bxane.nuvx.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.SmartAnalysis
import com.bxane.nuvx.data.settings.TrackAnalysisState
import com.bxane.nuvx.playback.smart.TrackAnalysis

internal class CrossfadeAnalysisStatusController(
    private val active: () -> ExoPlayer,
    private val analysisFor: (MediaItem) -> TrackAnalysis,
    private val analysisRunningFor: (MediaItem) -> Boolean,
) {
    fun publish() {
        val player = active()
        val currentItem = player.currentMediaItem
        val nextIndex = player.nextMediaItemIndex
        val nextItem = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        AppSettings.smartAnalysis.value = SmartAnalysis(
            current = currentItem?.let { stateOf(it, analysisFor(it)) } ?: TrackAnalysisState.WAITING,
            next = nextItem?.let { stateOf(it, analysisFor(it)) } ?: TrackAnalysisState.WAITING,
        )
    }

    fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState = when {
        analysis.isUsable ->
            if (analysisRunningFor(item)) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        analysisRunningFor(item) -> TrackAnalysisState.ANALYSING
        analysis.status == TrackAnalysis.STATUS_READY -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }
}
