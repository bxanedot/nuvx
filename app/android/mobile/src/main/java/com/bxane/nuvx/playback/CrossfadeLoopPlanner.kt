package com.bxane.nuvx.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.SmartAnalysis
import com.bxane.nuvx.data.settings.TrackAnalysisState
import com.bxane.nuvx.playback.loopmix.LoopMixPort
import com.bxane.nuvx.playback.loopmix.LoopMixRenderer
import com.bxane.nuvx.playback.smart.LoopMix
import com.bxane.nuvx.playback.smart.TrackAnalysis
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class CrossfadeLoopPlanner(
    private val scope: CoroutineScope,
    private val syncedLines: suspend (MediaItem, Long) -> List<LyricLine>?,
    private val loopPortOf: (ExoPlayer) -> LoopMixPort?,
    private val standby: () -> ExoPlayer,
) {
    private var loopPlanKey: String? = null
    private var loopPlanned: LoopMix.Plan? = null
    private var loopRefusal: String? = null
    private var lastLoopVerdict = ""

    private class LyricSpans(val spans: List<LoopMix.Span>?)

    private val lyricSpans = ConcurrentHashMap<String, LyricSpans>()
    private val lyricsAsked = ConcurrentHashMap.newKeySet<String>()

    val planned: LoopMix.Plan? get() = loopPlanned
    val refusal: String? get() = loopRefusal

    fun isPlannedFor(currentId: String, nextId: String): Boolean =
        loopPlanKey?.startsWith("$currentId->$nextId|") == true

    fun planFor(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
        state: SmartAnalysis,
    ): LoopMix.Plan? {
        val pair = "${current.mediaId}->${next.mediaId}"
        loopRefusalFor(player, current, next, state)?.let { why ->
            noteLoopVerdict(pair, null, why)
            return null
        }
        askLyrics(current, durationMs)
        askLyrics(next, nextDurationMs)
        val outLyrics = lyricSpans[current.mediaId]
        val inLyrics = lyricSpans[next.mediaId]
        if (outLyrics == null || inLyrics == null) {
            noteLoopVerdict(pair, null, "waiting on lyrics")
            return null
        }
        val key = "$pair|${System.identityHashCode(currentAnalysis)}|${System.identityHashCode(nextAnalysis)}"
        val cached = loopPlanned
        if (key == loopPlanKey && (cached == null || cached.out.loopStart * 1000 - player.currentPosition >= LOOP_MIN_LEAD_MS)) {
            return cached
        }
        val why = StringBuilder()
        val plan = LoopMix.plan(
            out = currentAnalysis,
            outDuration = durationMs / 1000.0,
            inn = nextAnalysis,
            inDuration = nextDurationMs / 1000.0,
            earliestOutSec = player.currentPosition / 1000.0 + LOOP_SETUP_SECONDS,
            sungOut = outLyrics.spans,
            sungIn = inLyrics.spans,
            why = why,
        )
        loopPlanKey = key
        loopPlanned = plan
        val lyricsNote = "lyrics ${if (outLyrics.spans != null) "out" else "-"}/${if (inLyrics.spans != null) "in" else "-"}"
        noteLoopVerdict(pair, plan, if (plan == null) "$why; $lyricsNote" else null)
        return plan
    }

    fun clear(reason: String) {
        loopPlanned = null
        loopRefusal = reason
    }

    fun describeLoop(plan: LoopMix.Plan): String {
        val from = plan.out.bpm.roundToInt()
        val to = plan.targetBpm.roundToInt()
        val tempo = if (from == to) "$from BPM" else "$from → $to BPM over ${plan.rampBars} bars"
        val fold = when {
            plan.fold > 1 -> " (next in double time)"
            plan.fold < 1 -> " (next in half time)"
            else -> ""
        }
        fun side(pick: LoopMix.Pick): String {
            val by = when (pick.checkedBy) {
                LoopMix.Check.LYRICS -> "lyrics"
                LoopMix.Check.MASK -> "mask"
                LoopMix.Check.LYRICS_AND_MASK -> "lyrics + mask"
            }
            return "${pick.bars}-bar, clear by $by"
        }
        return "out ${side(plan.out)} · in ${side(plan.inn)} · $tempo$fold"
    }

    private fun askLyrics(item: MediaItem, durationMs: Long) {
        val id = item.mediaId
        if (!lyricsAsked.add(id)) return
        scope.launch(Dispatchers.IO) {
            val lines = runCatching { syncedLines(item, durationMs) }.getOrNull()
            val spans = lines?.takeIf { found -> found.count { it.timeMs > 0 } >= 4 }?.let { found ->
                LoopMix.sungSpans(
                    found.map { line ->
                        val end = line.sungUntilMs ?: line.words.lastOrNull()?.endMs
                        LoopMix.Line(line.timeMs, end, blank = line.isGap || line.text.isBlank())
                    },
                )
            }
            lyricSpans[id] = LyricSpans(spans)
        }
    }

    private fun loopRefusalFor(player: ExoPlayer, current: MediaItem, next: MediaItem, state: SmartAnalysis): String? = when {
        !LoopMixRenderer.available -> "no renderer"
        AppSettings.playbackSpeed.value != 1f -> "playback speed is not 1x"
        player.skipSilenceEnabled -> "skip silence is on"
        SleepTimer.afterTrack.value -> "sleep timer"
        state.current != TrackAnalysisState.ANALYSED || state.next != TrackAnalysisState.ANALYSED -> "waiting on analysis"
        isSourceBacked(current) || isSourceBacked(next) -> "analysed off another recording"
        loopPortOf(player)?.supportsLoopMix != true -> "this output can't take it"
        loopPortOf(standby()) == null -> "no standby sink"
        else -> null
    }

    private fun isSourceBacked(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        return uri.getQueryParameter("v") == null && uri.authority == "source"
    }

    private fun noteLoopVerdict(pair: String, plan: LoopMix.Plan?, refusal: String?) {
        loopRefusal = refusal
        val verdict = "$pair: " + (plan?.let { "loop mix at ${"%.2f".format(Locale.ROOT, it.out.loopStart)}s · ${describeLoop(it)}" } ?: "no loop mix ($refusal)")
        if (verdict == lastLoopVerdict) return
        lastLoopVerdict = verdict
        Log.d(TAG, "loop $verdict")
    }

    private companion object {
        const val TAG = "NuvxCrossfade"
        const val LOOP_SETUP_SECONDS = 15.0
        const val LOOP_MIN_LEAD_MS = 9_000L
    }
}
