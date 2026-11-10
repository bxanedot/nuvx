package com.bxane.nuvx.playback

import android.net.Uri
import android.util.Log
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.StreamFormat
import com.bxane.nuvx.data.sources.StreamRequest
import com.bxane.nuvx.data.sources.TrackMatcher
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap

object QualityUpgrade {

    private const val TAG = "nuvx"

    const val MARKER = "q"
    private const val UPGRADED = "hifi"

    private const val ORIGINAL = "original"

    private data class Pending(
        val target: TrackMatcher.Target,
        val inFlight: Deferred<SourceStream?>? = null,
        val playing: StreamFormat? = null,
        val servedBy: String? = null,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val followUps = ConcurrentHashMap<String, Pending>()
    private val forced = ConcurrentHashMap<String, SourceStream>()

    internal fun needsLosslessFollowUp(format: StreamFormat): Boolean =
        SourceResolver.requestForNow() is StreamRequest.Lossless &&
            format.isLossless != true &&
            !format.isDolbyAtmos

    private val auditioning = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun beginAudition(mediaId: String) {
        auditioning += mediaId
    }

    fun endAudition(mediaId: String) {
        auditioning -= mediaId
    }

    fun isAuditioning(videoId: String?): Boolean = videoId != null && videoId in auditioning

    private val shelved = ConcurrentHashMap<String, SourceStream>()

    fun shelve(mediaId: String, stream: SourceStream) {
        shelved[mediaId] = stream
        asked -= mediaId
    }

    fun shelvedFor(mediaId: String): SourceStream? = shelved[mediaId]

    fun unshelve(mediaId: String) {
        shelved.remove(mediaId)
    }

    fun settledForLess(
        mediaId: String,
        target: TrackMatcher.Target,
        inFlight: Deferred<SourceStream?>? = null,
        playing: StreamFormat? = null,
        servedBy: String? = null,
    ): Boolean {
        if (target.title.isBlank() || target.isVideo ||
            mediaId in refused ||
            !SourceResolver.canSubstituteForYouTube()
        ) {
            inFlight?.cancel()
            return false
        }
        pending[mediaId] = Pending(target, inFlight, playing, servedBy)
        NerdStats.onLosslessRaceStart(mediaId)
        TrackLog.d(
            TAG,
            if (inFlight != null) {
                "'${target.title}' started on the fallback; its lookup is still running"
            } else {
                "below request for '${target.title}'; will look again during playback"
            },
            about = mediaId,
        )
        return true
    }

    fun isPending(mediaId: String?) = mediaId != null && pending.containsKey(mediaId)

    private val refused = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun refuseUpgrades(mediaId: String) {
        refused += mediaId
        TrackLog.d(TAG, "$mediaId broke on its upgrade; no more swaps for it", about = mediaId)
    }

    private val asked = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun couldStillUpgrade(mediaId: String, uri: Uri?): Boolean {
        if (uri == null || uri.getQueryParameter("v") == null ||
            uri.getQueryParameter("m") == "1"
        ) return false
        if (mediaId !in handAsked && uri.getQueryParameter(MARKER) != null) return false
        if (mediaId in asked || mediaId in refused || pending.containsKey(mediaId)) return false
        if (mediaId !in handAsked && DirectSourceGate.isActive() && !StreamChoice.isYouTube(mediaId)) {
            return false
        }
        return SourceResolver.canSubstituteForYouTube()
    }

    fun adoptUnresolved(
        mediaId: String,
        uri: Uri,
        target: TrackMatcher.Target,
        playingMime: String?,
        playing: StreamFormat?,
    ): Boolean {
        if (!couldStillUpgrade(mediaId, uri)) return false
        if (NerdStats.isLosslessMime(playingMime) || NerdStats.isDolbyAtmosMime(playingMime)) {
            asked += mediaId
            TrackLog.d(
                TAG,
                "'${target.title}' is already playing $playingMime from cache; no second look needed",
                about = mediaId,
            )
            return false
        }
        if (target.title.isBlank()) {
            asked += mediaId
            return false
        }
        pending[mediaId] = Pending(target, inFlight = null, playing = playing)
        NerdStats.onLosslessRaceStart(mediaId)
        TrackLog.d(
            TAG,
            "'${target.title}' is playing ${playing?.summary ?: "an unmeasured stream"} from cache " +
                "and was never resolved; looking for a better copy",
            about = mediaId,
        )
        return true
    }

    fun effectiveTargetDuration(expectedSec: Int?, playingSec: Int?): Int? {
        return if (expectedSec != null && playingSec != null &&
            TrackMatcher.isSevereMismatch(expectedSec, playingSec)
        ) {
            expectedSec
        } else {
            playingSec ?: expectedSec
        }
    }

    suspend fun lookAgain(mediaId: String, playingDurationSec: Int?): SourceStream? {
        val waiting = pending[mediaId] ?: return null
        var found: SourceStream? = null
        var answered = false
        val expectedSec = waiting.target.durationSec
        val effectiveDurationSec = effectiveTargetDuration(expectedSec, playingDurationSec)
        if (expectedSec != null && playingDurationSec != null &&
            TrackMatcher.isSevereMismatch(expectedSec, playingDurationSec)
        ) {
            TrackLog.w(
                TAG,
                "playing duration ($playingDurationSec s) drifted severely from catalogue duration ($expectedSec s); preserving catalogue duration for upgrade",
                about = mediaId,
            )
        }
        return try {
            waiting.inFlight?.let { lookup ->
                val late = runCatching { lookup.await() }.getOrNull()
                val slack = SourceResolver.upgradeSlackSec()
                val sameLength = late != null &&
                    SourceResolver.sameRecordingAs(late.durationSec, effectiveDurationSec, slack)
                if (late != null && !sameLength) {
                    TrackLog.d(
                        TAG,
                        "'${waiting.target.title}': the live lookup's ${late.format.summary} runs " +
                            "${late.durationSec ?: "?"}s against ${effectiveDurationSec ?: "?"}s playing, " +
                            "outside the ±${slack}s tolerance; searching again",
                        about = mediaId,
                    )
                }
                if (late != null && sameLength &&
                    SourceResolver.worthSwapping(late.format, waiting.playing)
                ) {
                    found = late
                    if (needsLosslessFollowUp(late.format)) {
                        followUps[mediaId] = waiting.copy(
                            target = waiting.target.copy(durationSec = effectiveDurationSec),
                            inFlight = null,
                            playing = late.format,
                            servedBy = late.sourceConfigId,
                        )
                    }
                    answered = true
                    return late
                }
            }
            SourceResolver.upgradeFor(
                waiting.target.copy(durationSec = effectiveDurationSec),
                playing = waiting.playing,
                servedBy = waiting.servedBy,
            ).also {
                found = it
                if (it != null && needsLosslessFollowUp(it.format)) {
                    followUps[mediaId] = waiting.copy(
                        target = waiting.target.copy(durationSec = effectiveDurationSec),
                        inFlight = null,
                        playing = it.format,
                        servedBy = it.sourceConfigId,
                    )
                }
                answered = true
            }
        } finally {
            if (answered) {
                pending.remove(mediaId)
                asked += mediaId
            }
            if (found == null) NerdStats.onLosslessRaceEnd(mediaId)
        }
    }

    fun onPlaybackStarted(mediaId: String, rendition: String?) {
        if (rendition != null) return
        asked -= mediaId
    }

    fun isKnownToBePlayingYouTubesOwn(
        mediaId: String,
        rendition: String?,
        cachedOrigin: AudioCache.Origin? = null,
    ): Boolean {
        if (rendition != null) return false
        if (StreamChoice.of(mediaId) != null) return !StreamChoice.isSubstitute(mediaId)
        return cachedOrigin == AudioCache.Origin.YOUTUBE
    }

    internal fun hasAnsweredFor(mediaId: String): Boolean = mediaId in asked

    internal fun markAnsweredForTest(mediaId: String) {
        asked += mediaId
    }

    fun forget(mediaId: String) {
        pending.remove(mediaId)?.inFlight?.cancel()
        followUps.remove(mediaId)?.inFlight?.cancel()
        forced.remove(mediaId)
        shelved.remove(mediaId)
        auditioning -= mediaId
        handAsked -= mediaId
        NerdStats.onLosslessRaceEnd(mediaId)
    }

    private val handAsked = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun askByHand(mediaId: String) {
        handAsked += mediaId
        asked -= mediaId
        refused -= mediaId
    }

    fun forgetLastSession() {
        (pending.keys + followUps.keys + forced.keys + shelved.keys + auditioning).forEach(::forget)
        asked.clear()
        refused.clear()
    }

    fun onCeilingLowered(keep: Set<String>, permitted: (SourceStream) -> Boolean) {
        pending.replaceAll { _, waiting ->
            waiting.inFlight?.cancel()
            waiting.copy(inFlight = null)
        }
        followUps.values.forEach { it.inFlight?.cancel() }
        followUps.clear()
        shelved.entries.removeIf { !permitted(it.value) }
        forced.entries.removeIf { (mediaId, stream) -> mediaId !in keep && !permitted(stream) }
    }

    fun force(mediaId: String, stream: SourceStream) {
        forced[mediaId] = stream
    }

    fun continueAfterLossySwap(mediaId: String): Boolean {
        val next = followUps.remove(mediaId) ?: return false
        pending[mediaId] = next
        asked -= mediaId
        NerdStats.onLosslessRaceStart(mediaId)
        return true
    }

    fun forcedStream(uri: Uri): SourceStream? {
        if (uri.getQueryParameter(MARKER)?.startsWith(UPGRADED) != true) return null
        return uri.getQueryParameter("v")?.let(forced::get)
    }

    fun upgradedUri(uri: String): String {
        val markerStart = "&$MARKER="
        val previous = uri.substringAfter(markerStart, missingDelimiterValue = "")
            .substringBefore('&')
            .ifBlank { null }
            ?.takeUnless { it == ORIGINAL }
        val previousGeneration = previous
            ?.removePrefix("$UPGRADED-")
            ?.toIntOrNull()
            ?: 1
        val tag = if (previous == null) UPGRADED else "$UPGRADED-${previousGeneration + 1}"
        return uri.substringBefore(markerStart)
            .replace("&$DIRECT_YOUTUBE_PARAMETER=1", "") + "$markerStart$tag"
    }

    fun cacheTag(uri: Uri): String? = uri.getQueryParameter(MARKER)
}
