package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PlaybackTracker {

    private const val TAG = "nuvx"

    private const val REPORT_INTERVAL_SECONDS = 30L

    private const val OPEN_ATTEMPTS = 3

    private const val OPEN_RETRY_DELAY_MS = 2_000L

    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

    private class Session(
        var videoId: String,
        val cpn: String,
        val tracking: Innertube.PlaybackTracking,
    ) {
        var reportedSeconds = 0L

        var flushingTo = 0L

        var atrSent = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _registeredPlays = MutableStateFlow(0)

    val registeredPlays: StateFlow<Int> = _registeredPlays.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var opening: String? = null

    fun onPlaying(videoId: String) {
        if (!VIDEO_ID.matches(videoId)) return
        if (Innertube.cookie == null) return
        if (AppSettings.meteredConnection.value == null) return
        if (session?.videoId == videoId || opening == videoId) return
        opening = videoId
        scope.launch(TrackLog.about(videoId)) {
            try {
                openWithRetries(videoId)
            } finally {
                if (opening == videoId) opening = null
            }
        }
    }

    fun onVersionSwapped(fromVideoId: String, toVideoId: String) {
        session?.takeIf { it.videoId == fromVideoId }?.videoId = toVideoId
        if (opening == fromVideoId) opening = toVideoId
    }

    fun onTrackChanged(positionSeconds: Long) {
        val closing = session ?: return
        session = null
        scope.launch(TrackLog.about(closing.videoId)) {
            runCatching { flush(closing, positionSeconds, final = true) }
                .onFailure {
                    TrackLog.w(TAG, "final watchtime ping failed for ${closing.videoId}: ${it.message}")
                }
        }
    }

    fun onProgress(videoId: String, positionSeconds: Long) {
        val current = session ?: return
        if (current.videoId != videoId) return
        if (!current.atrSent && positionSeconds >= current.tracking.atrAfterSeconds) {
            current.atrSent = true
            val atrUrl = current.tracking.atrUrl
            if (atrUrl != null) {
                scope.launch(TrackLog.about(videoId)) {
                    runCatching { Innertube.pingAtr(atrUrl, current.cpn) }
                        .onFailure { TrackLog.w(TAG, "atr ping failed for $videoId: ${it.message}") }
                }
            }
        }
        if (positionSeconds - maxOf(current.reportedSeconds, current.flushingTo) < REPORT_INTERVAL_SECONDS) return
        scope.launch(TrackLog.about(videoId)) {
            runCatching { flush(current, positionSeconds) }
                .onFailure { TrackLog.w(TAG, "watchtime ping failed for $videoId: ${it.message}") }
        }
    }

    fun onPlaybackFinished(positionSeconds: Long) {
        val closing = session ?: return
        session = null
        scope.launch(TrackLog.about(closing.videoId)) {
            withContext(NonCancellable) {
                runCatching { flush(closing, positionSeconds, final = true) }
                    .onFailure {
                        TrackLog.w(TAG, "closing watchtime ping failed for ${closing.videoId}: ${it.message}")
                    }
            }
        }
    }

    private suspend fun openWithRetries(videoId: String) {
        repeat(OPEN_ATTEMPTS) { attempt ->
            if (opening != videoId) return
            val settled = try {
                open(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TrackLog.w(
                    TAG,
                    "history registration failed for $videoId " +
                        "(attempt ${attempt + 1}/$OPEN_ATTEMPTS): ${e.message}",
                )
                false
            }
            if (settled) return
            if (attempt < OPEN_ATTEMPTS - 1) delay(OPEN_RETRY_DELAY_MS)
        }
    }

    private suspend fun open(videoId: String): Boolean = lock.withLock {
        val signatureTimestamp = StreamResolver.signatureTimestamp(videoId)
        if (signatureTimestamp == null) {
            TrackLog.w(TAG, "no signature timestamp yet; retrying history for $videoId")
            return@withLock false
        }
        val tracking = Innertube.playbackTracking(videoId, signatureTimestamp)
        if (tracking == null) {
            TrackLog.d(TAG, "no playback tracking for $videoId (guest, or the player declined)")
            return@withLock true
        }
        val fresh = Session(videoId, Innertube.newCpn(), tracking)
        val status = Innertube.pingPlayback(tracking.playbackUrl, fresh.cpn)
        session = fresh
        _registeredPlays.value++
        TrackLog.d(TAG, "history entry created for $videoId (HTTP $status)")
        true
    }

    private suspend fun flush(target: Session, positionSeconds: Long, final: Boolean = false) {
        val url = target.tracking.watchtimeUrl ?: return
        if (!final && positionSeconds <= target.reportedSeconds) return
        target.flushingTo = maxOf(target.flushingTo, positionSeconds)
        lock.withLock {
            val status = Innertube.pingWatchtime(url, target.cpn, positionSeconds, final)
            target.reportedSeconds = maxOf(target.reportedSeconds, positionSeconds)
            TrackLog.d(
                TAG,
                "watchtime ${positionSeconds}s reported for ${target.videoId}" +
                    "${if (final) " (final)" else ""} (HTTP $status)",
            )
        }
    }
}
