package com.bxane.nuvx.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.MixBlend
import com.bxane.nuvx.data.settings.TransitionWindow
import com.bxane.nuvx.playback.loopmix.LoopMixPort
import com.bxane.nuvx.playback.loopmix.LoopMixRenderer
import com.bxane.nuvx.playback.loopmix.LoopMixSession
import com.bxane.nuvx.playback.loopmix.LoopRegion
import com.bxane.nuvx.playback.smart.LoopMix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

internal class CrossfadeLoopMixController(
    private val scope: CoroutineScope,
    private val standby: () -> ExoPlayer,
    private val loopPortOf: (ExoPlayer) -> LoopMixPort?,
    private val loopRegion: (MediaItem, Long, Double, Double, Int) -> LoopRegion?,
    private val loudnessGain: (String) -> Float,
    private val endSpeedEase: (Boolean) -> Unit,
    private val filters: TransitionFilters,
    private val onArmIncoming: (MediaItem, String?) -> Unit,
    private val onBlendHeadroom: (Float) -> Unit,
    private val onMixPositionJump: (Boolean) -> Unit,
    private val planner: CrossfadeLoopPlanner,
    private val isArming: () -> Boolean,
    private val isRunning: () -> Boolean,
    private val markArming: () -> Unit,
    private val markRunning: () -> Unit,
    private val activateSmartFade: () -> Unit,
    private val outgoing: () -> ExoPlayer?,
    private val setOutgoing: (ExoPlayer?) -> Unit,
    private val incoming: () -> ExoPlayer?,
    private val setIncoming: (ExoPlayer?) -> Unit,
    private val handedOff: () -> Boolean,
    private val setHandedOff: (Boolean) -> Unit,
    private val setOutgoingItemIndex: (Int) -> Unit,
    private val setQueuedItemCount: (Int) -> Unit,
    private val setArmDeadline: (Long) -> Unit,
    private val holdAtEnd: (ExoPlayer) -> Unit,
    private val handOff: (ExoPlayer, ExoPlayer) -> Unit,
    private val finish: () -> Unit,
    private val phaseLabel: () -> String,
) {
    private var loopSession: LoopMixSession? = null
    private var loopBuildJob: Job? = null
    private var loopAlignJob: Job? = null
    private var loopIncomingStarted = false
    private var lastLoopPositionMs = -1L
    private var lastLoopAnnounceAt = 0L
    private var lastLoopBeat = -1

    val session: LoopMixSession? get() = loopSession

    fun begin(
        out: ExoPlayer,
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
    ) {
        val into = standby()
        if (out === into) return
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val outPort = loopPortOf(out) ?: return
        val inPort = loopPortOf(into) ?: return
        endSpeedEase(false)
        markArming()
        activateSmartFade()
        setHandedOff(false)
        setOutgoing(out)
        setIncoming(into)
        setOutgoingItemIndex(out.currentMediaItemIndex)
        setArmDeadline(SystemClock.elapsedRealtime() + LOOP_BUILD_TIMEOUT_MS)
        holdAtEnd(out)
        val sampleRate = outPort.sampleRate
        val inLevel = (loudnessGain(inItem.mediaId) / loudnessGain(outItem.mediaId))
            .takeIf { it.isFinite() && it > 0f } ?: 1f
        Log.d(
            TAG,
            "loop build ${outItem.mediaId}->${inItem.mediaId} at ${sampleRate}Hz, loop at " +
                "${"%.2f".format(Locale.ROOT, plan.out.loopStart)}s, playhead ${out.currentPosition}ms",
        )
        loopBuildJob = scope.launch {
            val began = SystemClock.elapsedRealtime()
            val session = withContext(Dispatchers.Default) {
                runCatching {
                    LoopMixSession.build(plan, outItem.mediaId, inItem.mediaId, sampleRate, inLevel) { side, from, to ->
                        if (side == LoopMix.Side.OUT) {
                            loopRegion(outItem, durationMs, from, to, sampleRate)
                        } else {
                            loopRegion(inItem, nextDurationMs, from, to, sampleRate)
                        }
                    }
                }.onFailure { Log.w(TAG, "loop build failed", it) }.getOrNull()
            }
            if (!isArming() || outgoing() !== out) {
                session?.close()
                return@launch
            }
            if (session == null) {
                loopFallBack("the decks would not build")
                return@launch
            }
            Log.d(TAG, "loop decks built in ${SystemClock.elapsedRealtime() - began}ms")
            armLoop(session, out, into, nextIndex, outPort, inPort)
        }
    }

    private fun armLoop(
        session: LoopMixSession,
        out: ExoPlayer,
        into: ExoPlayer,
        nextIndex: Int,
        outPort: LoopMixPort,
        inPort: LoopMixPort,
    ) {
        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        setQueuedItemCount(items.size)
        into.skipSilenceEnabled = false
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = out.shuffleModeEnabled
        into.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        into.volume = 1f
        out.volume = 1f
        filters.open()
        onBlendHeadroom(1f)
        into.setMediaItems(items, nextIndex, (session.inStartTrack * 1000).roundToLong())
        items.getOrNull(nextIndex)?.let { item ->
            val after = into.nextMediaItemIndex
            val afterId = if (after == C.INDEX_UNSET) null else items.getOrNull(after)?.mediaId
            onArmIncoming(item, afterId)
        }
        into.playWhenReady = false
        inPort.armIncoming(LoopMixPort.InArm((session.handoverTrack * 1_000_000).roundToLong()))
        into.prepare()
        session.attach(outPort, inPort)
        loopSession = session
        loopIncomingStarted = false
        lastLoopPositionMs = -1L
        lastLoopBeat = -1
        Log.d(
            TAG,
            "loop armed: takeover at ${session.takeoverUs}us (stream offset ${outPort.streamOffsetUs}us), " +
                "incoming from ${"%.3f".format(Locale.ROOT, session.inStartTrack)}s, handover at " +
                "${"%.3f".format(Locale.ROOT, session.handoverTrack)}s, mix ${"%.1f".format(Locale.ROOT, session.clock.elementAt)}s",
        )
    }

    fun driveArming(armDeadline: Long) {
        val out = outgoing() ?: return bail("no outgoing player")
        if (!out.playWhenReady) return bail("paused before the loop")
        val session = loopSession
        if (session == null) {
            if (SystemClock.elapsedRealtime() > armDeadline) loopFallBack("the decks took too long")
            return
        }
        if (session.outPort.interrupted) return bail("the outgoing sink was flushed", settle = false)
        if (session.outPort.tookOver) {
            markRunning()
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = true
            scope.launch(Dispatchers.Default) {
                session.outPort.spliceLag()?.let { (lag, score) ->
                    Log.d(
                        TAG,
                        "loop splice: deck ${"%+.2f".format(Locale.ROOT, lag * 1000.0 / session.sampleRate)}ms " +
                            "against its song (r=${"%.3f".format(Locale.ROOT, score)})",
                    )
                }
            }
            return
        }
        val m = session.mixSeconds(System.nanoTime(), out.isPlaying) ?: return
        if (m > 1.0) bail("the sink never reached the loop")
    }

    fun driveMix() {
        val session = loopSession ?: return finish()
        val out = outgoing() ?: return bail("no outgoing player")
        val into = incoming() ?: return bail("no incoming player")
        val sessionPlayer = if (handedOff()) into else out
        if (session.outPort.interrupted || (loopIncomingStarted && session.inPort.interrupted)) {
            return bail("a sink was flushed", settle = false)
        }
        if (!sessionPlayer.playWhenReady) return bail("paused", settle = true)
        val now = System.nanoTime()
        val m = session.mixSeconds(now, out.isPlaying) ?: return
        if (!loopIncomingStarted && m >= session.inStartAt) {
            into.playWhenReady = true
            loopIncomingStarted = true
        }
        if (loopIncomingStarted && loopAlignJob == null && into.isPlaying &&
            session.inPort.positionUs != C.TIME_UNSET && m >= session.inStartAt + LOOP_ALIGN_SETTLE_SECONDS
        ) {
            startLoopAlignment(session)
        }
        if (!handedOff() && m >= session.clock.handoffAt) {
            handOff(out, into)
        }
        publishLoopBeat(session, m, now)
        announceLoopPosition()
        if (m >= session.clock.elementAt + LOOP_END_MARGIN_SECONDS) finishLoop()
    }

    private fun startLoopAlignment(session: LoopMixSession) {
        loopAlignJob = scope.launch {
            repeat(LOOP_ALIGN_ROUNDS) { round ->
                val samples = ArrayList<Double>(LOOP_ALIGN_SAMPLES)
                repeat(LOOP_ALIGN_SAMPLES) {
                    session.offsetNow(System.nanoTime())?.let(samples::add)
                    delay(LOOP_ALIGN_SAMPLE_MS)
                }
                if (samples.size < LOOP_ALIGN_SAMPLES / 2) return@launch
                samples.sort()
                val error = samples[samples.size / 2]
                val spread = samples.last() - samples.first()
                Log.d(
                    TAG,
                    "loop align $round: incoming ${"%+.2f".format(Locale.ROOT, error * 1000)}ms against the deck " +
                        "(spread ${"%.2f".format(Locale.ROOT, spread * 1000)}ms)",
                )
                session.recordAligned(error)
                if (LoopMixSession.aligned(error)) return@launch
                val port = session.inPort
                val lead = if (port.processedUs != C.TIME_UNSET && port.positionUs != C.TIME_UNSET) {
                    ((port.processedUs - port.positionUs).coerceAtLeast(0L)) / 1e6
                } else {
                    1.0
                }
                session.correct(error)
                delay(((lead + abs(error) + 0.4) * 1000).roundToLong().coerceIn(300L, 12_000L))
            }
            session.offsetNow(System.nanoTime())?.let {
                session.recordAligned(it)
                Log.d(TAG, "loop align: left at ${"%+.2f".format(Locale.ROOT, it * 1000)}ms")
            }
        }
    }

    private fun publishLoopBeat(session: LoopMixSession, m: Double, now: Long) {
        if (m < 0) return
        val (beat, _) = session.clock.beatAt(m)
        val times = session.clock.beatTimes
        if (beat == lastLoopBeat || beat !in 0 until times.size - 1) return
        lastLoopBeat = beat
        val beatMs = ((times[beat + 1] - times[beat]) * 1000).toFloat()
        val anchor = now - ((m - times[beat]) * 1e9).roundToLong()
        AppSettings.smartMixBlend.value = MixBlend(beatMs = beatMs, beatAnchorNanos = anchor, playing = true)
    }

    private fun announceLoopPosition() {
        val position = loopPositionMs() ?: return
        val now = SystemClock.elapsedRealtime()
        val wrapped = lastLoopPositionMs >= 0 && position < lastLoopPositionMs - LOOP_WRAP_MS
        lastLoopPositionMs = position
        if (wrapped || now - lastLoopAnnounceAt >= LOOP_ANNOUNCE_MS) {
            lastLoopAnnounceAt = now
            onMixPositionJump(wrapped)
        }
    }

    fun loopPositionMs(): Long? {
        if (!isRunning()) return null
        val session = loopSession ?: return null
        val playing = outgoing()?.isPlaying ?: return null
        val m = session.mixSeconds(System.nanoTime(), playing) ?: return null
        if (m < 0) return null
        val side = if (handedOff()) LoopMix.Side.IN else LoopMix.Side.OUT
        val position = LoopMix.positionAt(session.clock, side, m) ?: return null
        return (position * 1000).roundToLong()
    }

    private fun finishLoop() {
        val session = loopSession
        Log.d(
            TAG,
            "loop mix done: incoming ${"%+.2f".format(Locale.ROOT, session?.alignedErrorMs ?: Double.NaN)}ms " +
                "against the deck at the handover",
        )
        loopAlignJob?.cancel()
        loopAlignJob = null
        loopSession = null
        session?.outPort?.disarm()
        session?.inPort?.disarm()
        finish()
        session?.renderer?.let(::closeLater)
    }

    fun bail(reason: String, settle: Boolean = true) {
        val session = loopSession
        Log.d(TAG, "loop mix stops (${phaseLabel()}): $reason")
        loopBuildJob?.cancel()
        loopBuildJob = null
        loopAlignJob?.cancel()
        loopAlignJob = null
        loopSession = null
        val out = outgoing()
        val into = incoming()
        val wasHandedOff = handedOff()
        val m = session?.mixSeconds(System.nanoTime(), playing = false)
        val tookOver = session?.outPort?.tookOver == true
        session?.outPort?.disarm()
        session?.inPort?.disarm()
        val target: Long? = when {
            session == null -> null
            wasHandedOff -> m?.let { LoopMix.positionAt(session.clock, LoopMix.Side.IN, it) }?.let { (it * 1000).roundToLong() }
            tookOver -> (m?.takeIf { it >= 0 }?.let { LoopMix.positionAt(session.clock, LoopMix.Side.OUT, it) }
                ?: session.plan.out.loopStart).let { (it * 1000).roundToLong() }
            else -> null
        }
        finish()
        session?.renderer?.let(::closeLater)
        val sessionPlayer = if (wasHandedOff) into else out
        if (sessionPlayer != null && target != null && (settle || (!wasHandedOff && tookOver))) {
            sessionPlayer.seekTo(target)
        }
    }

    private fun loopFallBack(reason: String) {
        Log.d(TAG, "loop mix falls back to a blend: $reason")
        planner.clear(reason)
        bail(reason, settle = false)
    }

    fun release() {
        loopBuildJob?.cancel()
        loopAlignJob?.cancel()
        loopSession?.let {
            it.outPort.disarm()
            it.inPort.disarm()
            closeLater(it.renderer)
        }
        loopSession = null
    }

    private fun closeLater(renderer: LoopMixRenderer) {
        scope.launch {
            delay(LOOP_RENDERER_CLOSE_DELAY_MS)
            renderer.close()
        }
    }

    private companion object {
        const val TAG = "NuvxCrossfade"
        const val LOOP_BUILD_TIMEOUT_MS = 8_000L
        const val LOOP_ALIGN_SETTLE_SECONDS = 1.2
        const val LOOP_ALIGN_ROUNDS = 3
        const val LOOP_ALIGN_SAMPLES = 15
        const val LOOP_ALIGN_SAMPLE_MS = 30L
        const val LOOP_END_MARGIN_SECONDS = 0.3
        const val LOOP_WRAP_MS = 400L
        const val LOOP_ANNOUNCE_MS = 2_000L
        const val LOOP_RENDERER_CLOSE_DELAY_MS = 1_500L
    }
}
