package com.bxane.nuvx.playback

import android.net.Uri
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.TrackMatcher
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class AudioCacheReadAheadController(
    private val scope: CoroutineScope,
    private val pendingQueue: () -> List<String>,
    private val setPendingQueue: (List<String>) -> Unit,
    private val tag: String,
    private val cacheKeyOf: (Uri) -> String,
    private val heldByYouTube: (String?) -> Boolean,
    private val dropYouTubeEntry: (String, String) -> Unit,
    private val fetch: suspend (String, Long, Long) -> Unit,
    private val fetchWhole: suspend (String) -> Unit,
    private val preloadBytes: Long,
    private val prefetchDelayMs: Long,
    private val queueLookahead: Int,
    private val queueResolveStaggerMs: Long,
) {
    private var job: Job? = null
    private var directJob: Job? = null

    fun prefetchQueue(upcoming: List<AudioCache.Upcoming>) {
        val mediaIds = upcoming.map { it.mediaId }
        if (mediaIds == pendingQueue()) return
        android.util.Log.d(
            "BCFetchDebug",
            "prefetchQueue: head ${pendingQueue().firstOrNull()} -> ${mediaIds.firstOrNull()}",
        )
        setPendingQueue(mediaIds)
        job?.cancel()
        val videoIds = mediaIds.filter { SourceRegistry.parseTrackKey(it) == null }
            .filter { it !in Downloads.saved.value }
        val substitutable = SourceResolver.canSubstituteForYouTube()
        job = videoIds.firstOrNull()?.let { next ->
            val target = upcoming.firstOrNull { it.mediaId == next }?.target
            val pinnedToYouTube = OriginalVersion.isPinned(next)
            scope.launch {
                val youTubeFilled = heldByYouTube(cacheKeyOf(Uri.parse("nuvx://watch?v=$next")))
                val warmed = if (substitutable && target != null && !pinnedToYouTube && !youTubeFilled) {
                    runCatching { SourceResolver.prefetchSubstitute(target) }
                        .onFailure { TrackLog.d(tag, "warm-up substitute failed for $next: ${it.message}", about = next) }
                        .getOrNull()
                        ?.also { StreamChoice.remember(next, it, substituted = true) }
                } else {
                    null
                }
                val cacheBytes = (!substitutable || warmed != null) && !pinnedToYouTube
                if (cacheBytes) {
                    launch(TrackLog.about(next)) {
                        delay(prefetchDelayMs)
                        fetch(next, 0, preloadBytes)
                        fetchWhole(next)
                    }
                }
                launch {
                    delay(prefetchDelayMs)
                    for (id in videoIds.take(queueLookahead + 1).let { if (cacheBytes) it.drop(1) else it }) {
                        if (id == next && warmed != null) continue
                        runCatching { StreamResolver.resolve(id) }
                            .onFailure { TrackLog.d(tag, "queue warm-up skipped $id: ${it.message}", about = id) }
                        delay(queueResolveStaggerMs)
                    }
                }
            }
        }
    }

    fun prefetchDirect(upcoming: AudioCache.Upcoming) {
        directJob?.cancel()
        val id = upcoming.mediaId
        if (SourceRegistry.parseTrackKey(id) != null || id in Downloads.saved.value || OriginalVersion.isPinned(id)) {
            directJob = null
            return
        }
        directJob = scope.launch(TrackLog.about(id)) {
            if (StreamChoice.of(id) == null) {
                val addon = runCatching { DirectSourceGate.lookup(id, upcoming.target) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                val stream = addon ?: runCatching {
                    val url = StreamResolver.resolve(id)
                    SourceStream(url, headers = StreamResolver.mediaHeadersFor(url))
                }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        TrackLog.d(tag, "direct read-ahead could not resolve $id: ${it.message}", about = id)
                    }
                    .getOrNull()
                    ?: return@launch
                if (StreamChoice.of(id) == null) {
                    val key = cacheKeyOf(Uri.parse("nuvx://watch?v=$id"))
                    if (addon != null && heldByYouTube(key)) dropYouTubeEntry(key, id)
                    if (addon != null && heldByYouTube(key)) return@launch
                    StreamChoice.remember(id, stream, substituted = addon != null)
                    if (addon == null) DirectSourceGate.startedOnYouTube(id, upcoming.target)
                }
            }
            fetch(id, 0, preloadBytes)
        }
    }

    fun cancelDirect() {
        directJob?.cancel()
        directJob = null
    }

    fun cancelQueue() {
        job?.cancel()
        job = null
    }
}
