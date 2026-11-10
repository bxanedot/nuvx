package com.bxane.nuvx.playback

import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.StreamFormat
import com.bxane.nuvx.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

object DirectSourceGate {

    private const val TAG = "nuvx"

    const val WINDOW_MS = 25_000L

    const val LOOKUP_TIMEOUT_MS = 15_000L

    fun isActive(): Boolean =
        AppSettings.directSourceStream.value && !ListenTogether.state.value.inParty &&
            SourceResolver.directAddons().isNotEmpty()

    private data class Open(val playing: Set<String>, val next: String?)

    private val open = MutableStateFlow(Open(emptySet(), null))

    fun onPlaying(mediaIds: Set<String>): Boolean {
        val now = open.value
        if (now.playing == mediaIds) return false
        open.value = Open(mediaIds, now.next?.takeIf { it in mediaIds })
        return true
    }

    fun openNext(mediaId: String): Boolean {
        val now = open.value
        if (now.next == mediaId || mediaId in now.playing) return false
        open.value = now.copy(next = mediaId)
        return true
    }

    private fun Open.admits(mediaId: String) = mediaId in playing || mediaId == next

    suspend fun awaitOpen(mediaId: String) {
        if (bypass.get() == true || !isActive() || open.value.admits(mediaId)) return
        TrackLog.d(TAG, "holding $mediaId until it is within ${WINDOW_MS / 1000}s of playing", about = mediaId)
        combine(open, AppSettings.directSourceStream, ListenTogether.state) { state, on, party ->
            !on || party.inParty || state.admits(mediaId)
        }
            .first { it }
        TrackLog.d(TAG, "released $mediaId", about = mediaId)
    }

    private const val READY_BYTES = 512L * 1024

    private const val READY_RECHECK_MS = 1_000L

    private var readyCheck: Triple<String, Boolean, Long>? = null

    fun isStreamReady(item: androidx.media3.common.MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        if (uri.scheme != "nuvx") return true
        val now = android.os.SystemClock.elapsedRealtime()
        readyCheck?.let { (id, ready, at) ->
            if (id == item.mediaId && (ready || now - at < READY_RECHECK_MS)) return ready
        }
        val ready = AudioCache.cachedPrefixBytes(uri) >= READY_BYTES || AudioCache.isFullyCached(uri)
        readyCheck = Triple(item.mediaId, ready, now)
        return ready
    }

    private val bypass = ThreadLocal<Boolean>()

    fun <T> ungated(block: () -> T): T {
        val was = bypass.get()
        bypass.set(true)
        try {
            return block()
        } finally {
            bypass.set(was)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private class Lookup(val work: Deferred<SourceStream?>, val startedAtMs: Long)

    private val lookups = ConcurrentHashMap<String, Lookup>()

    suspend fun lookup(videoId: String, target: TrackMatcher.Target): SourceStream? {
        val fresh = Lookup(
            work = scope.async(TrackLog.about(videoId), start = CoroutineStart.LAZY) {
                SourceResolver.directStream(target, lengthOf = { youTubeLength(videoId) })
            },
            startedAtMs = android.os.SystemClock.elapsedRealtime(),
        )
        val running = lookups.putIfAbsent(videoId, fresh)
        if (running != null) {
            fresh.work.cancel()
        } else {
            fresh.work.invokeOnCompletion { lookups.remove(videoId, fresh) }
            fresh.work.start()
        }
        val lookup = running ?: fresh
        val waited = android.os.SystemClock.elapsedRealtime() - lookup.startedAtMs
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS - waited) { lookup.work.await() }
    }

    private val lengths = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > LENGTHS_KEPT
    }

    fun knownLength(videoId: String): Int? = synchronized(lengths) { lengths[videoId] }

    private suspend fun youTubeLength(videoId: String): Int? {
        knownLength(videoId)?.let { return it }
        val seconds = withTimeoutOrNull(LENGTH_TIMEOUT_MS) {
            YtMusicRepository.trackLinks(videoId).getOrNull()
                ?.durationText
                ?.let(TrackMatcher::secondsOf)
        }
        if (seconds == null) {
            TrackLog.d(TAG, "no YouTube runtime for $videoId; matching addons on title and artist", about = videoId)
            return null
        }
        synchronized(lengths) { lengths[videoId] = seconds }
        TrackLog.d(TAG, "YouTube runtime for $videoId is ${seconds}s; matching addons against it", about = videoId)
        return seconds
    }

    private const val LENGTHS_KEPT = 256

    private const val LENGTH_TIMEOUT_MS = 5_000L

    fun stillLooking(videoId: String): Deferred<SourceStream?>? = lookups[videoId]?.work

    fun startedOnYouTube(videoId: String, target: TrackMatcher.Target) {
        val pending = QualityUpgrade.settledForLess(
            mediaId = videoId,
            target = target,
            inFlight = stillLooking(videoId),
            playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
        )
        if (!pending) NerdStats.onLosslessRaceEnd(videoId)
    }
}
