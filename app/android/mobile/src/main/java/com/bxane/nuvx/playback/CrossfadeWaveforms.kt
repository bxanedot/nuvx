package com.bxane.nuvx.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.MixDeck
import com.bxane.nuvx.data.settings.MixTransitionView
import com.bxane.nuvx.playback.smart.MixWaveform
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MIX_VIEW_CONTEXT = 0.24

internal class CrossfadeWaveforms(
    private val scope: CoroutineScope,
    private val mixWaveform: (MediaItem, Long, Double, Double, Int) -> MixWaveform.Bands?,
    private val tag: String,
) {
    private var mixViewJob: Job? = null

    private class DeckWave(val mediaId: String, val from: Double, val to: Double, val bands: MixWaveform.Bands) {
        fun covers(id: String, start: Double, end: Double) = id == mediaId && from <= start + 0.05 && to >= end - 0.05
    }

    private var outgoingWave: DeckWave? = null
    private var incomingWave: DeckWave? = null

    private var mixWavePair: String? = null

    private val pendingWaveIds = mutableSetOf<String>()

    private val failedWaveAt = mutableMapOf<String, Long>()

    private fun waveFailedRecently(id: String) =
        failedWaveAt[id]?.let { SystemClock.elapsedRealtime() - it < MIX_WAVE_RETRY_MS } == true

    fun prefetchMixWaves(
        outItem: MediaItem,
        inItem: MediaItem,
        outDurationMs: Long,
        inDurationMs: Long,
        outStart: Double,
        outEnd: Double,
        inStart: Double,
        ratio: Double,
        wantOut: Boolean = true,
        wantIn: Boolean = true,
    ) {
        val span = (outEnd - outStart).coerceAtLeast(0.5)
        val pad = span * MIX_VIEW_CONTEXT + MIX_PREFETCH_PAD_SECONDS
        val outFrom = (outStart - pad).coerceAtLeast(0.0)
        val outTo = outEnd + pad
        val inFrom = (inStart - pad * ratio).coerceAtLeast(0.0)
        val inTo = inStart + (span + pad) * ratio
        val pair = "${outItem.mediaId}->${inItem.mediaId}"
        if (mixWavePair != null && mixWavePair != pair) failedWaveAt.clear()
        val outReady = !wantOut || waveFailedRecently(outItem.mediaId) ||
            outgoingWave?.covers(outItem.mediaId, outFrom + MIX_PREFETCH_PAD_SECONDS, outTo - MIX_PREFETCH_PAD_SECONDS) == true
        val inReady = !wantIn || waveFailedRecently(inItem.mediaId) ||
            incomingWave?.covers(inItem.mediaId, inFrom + MIX_PREFETCH_PAD_SECONDS, inTo - MIX_PREFETCH_PAD_SECONDS) == true
        if (outReady && inReady) return
        if (mixViewJob?.isActive == true && mixWavePair == pair) return
        mixViewJob?.cancel()
        mixWavePair = pair
        pendingWaveIds.clear()
        if (!outReady) pendingWaveIds += outItem.mediaId
        if (!inReady) pendingWaveIds += inItem.mediaId
        republishWaves()
        mixViewJob = scope.launch {
            suspend fun wave(item: MediaItem, durationMs: Long, from: Double, to: Double): DeckWave? =
                withContext(Dispatchers.IO) {
                    val began = SystemClock.elapsedRealtime()
                    val bins = ((to - from) * MIX_WAVE_BINS_PER_SECOND).toInt().coerceIn(64, MIX_WAVE_MAX_BINS)
                    val bands = runCatching { mixWaveform(item, durationMs, from, to, bins) }
                        .onFailure { Log.w(tag, "mix waveform failed for ${item.mediaId}", it) }
                        .getOrNull()
                    Log.d(
                        tag,
                        "mix waveform ${item.mediaId} " +
                            String.format(Locale.ROOT, "%.1f-%.1fs", from, to) +
                            ": ${if (bands == null) "none" else "ok"} in ${SystemClock.elapsedRealtime() - began}ms",
                    )
                    bands?.let { DeckWave(item.mediaId, from, to, it) }
                }
            if (!outReady) {
                outgoingWave = wave(outItem, outDurationMs, outFrom, outTo)
                if (outgoingWave == null) failedWaveAt[outItem.mediaId] = SystemClock.elapsedRealtime()
                pendingWaveIds -= outItem.mediaId
                republishWaves()
            }
            if (!inReady) {
                incomingWave = wave(inItem, inDurationMs, inFrom, inTo)
                if (incomingWave == null) failedWaveAt[inItem.mediaId] = SystemClock.elapsedRealtime()
                pendingWaveIds -= inItem.mediaId
                republishWaves()
            }
        }
    }

    fun republishWaves() {
        AppSettings.smartMixView.value?.let { AppSettings.smartMixView.value = withWaves(it, mixViewIds) }
        AppSettings.smartMixPreview.value?.let { AppSettings.smartMixPreview.value = withWaves(it, mixPreviewIds) }
    }

    fun withWaves(view: MixTransitionView, ids: Pair<String, String>?): MixTransitionView {
        fun MixDeck.with(wave: DeckWave?, id: String?): MixDeck {
            val decoded = if (wave != null && wave.mediaId == id) {
                withWave(wave.bands.low, wave.bands.mid, wave.bands.high, wave.from, wave.to)
            } else {
                this
            }
            return decoded.withDecoding(id != null && id in pendingWaveIds)
        }
        return view.withDecks(
            view.outgoing.with(outgoingWave, ids?.first),
            view.incoming.with(incomingWave, ids?.second),
        )
    }

    var mixViewIds: Pair<String, String>? = null

    var mixPreviewIds: Pair<String, String>? = null

    fun clearMixView() {
        mixViewJob?.cancel()
        mixViewJob = null
        mixWavePair = null
        mixViewIds = null
        outgoingWave = null
        incomingWave = null
        pendingWaveIds.clear()
        failedWaveAt.clear()
        AppSettings.smartMixView.value = null
    }

    private companion object {
        const val MIX_WAVE_BINS_PER_SECOND = 40.0
        const val MIX_WAVE_MAX_BINS = 3_000

        const val MIX_PREFETCH_PAD_SECONDS = 3.0

        const val MIX_WAVE_RETRY_MS = 5_000L

    }
}
