package com.bxane.nuvx.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
internal class PlaybackErrorRecovery(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val onSwapping: (String) -> Unit,
) {
    private var audibleMediaId: String? = null

    private var consecutiveErrorSkips = 0

    private val recoveries = mutableMapOf<String, Int>()

    private var retryingMediaId: String? = null

    fun onAudible(mediaId: String?) {
        audibleMediaId = mediaId
        consecutiveErrorSkips = 0
    }

    fun onTrackChanged(becameCurrent: String?) {
        if (becameCurrent == null || becameCurrent != retryingMediaId) {
            recoveries.clear()
        }
        retryingMediaId = null
    }

    private fun isNetworkFault(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            AudioCache.isOffline()

    fun recoverFrom(error: PlaybackException, player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val position = player.currentPosition.coerceAtLeast(0L)
        val attempts = recoveries.getOrDefault(mediaId, 0) + 1
        recoveries[mediaId] = attempts
        TrackLog.w(
            "nuvx",
            "playback failed for $mediaId at ${position}ms (${error.errorCodeName}), attempt $attempts",
            error,
            about = mediaId,
        )
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
            restreamMissingLocalFile(player, item, uri, position)
        ) {
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
            replayAsManifest(player, item, uri, position)
        ) {
            return
        }
        if (fallbackFailedAlternativeToYouTube(item, uri, position, error)) return
        val verdict = permanentReason(error)
        val givingUp = verdict != null || attempts > MAX_RECOVERIES
        val neverStarted = audibleMediaId != mediaId
        if (verdict != null) {
            TrackLog.w("nuvx", "$mediaId cannot be played: $verdict", about = mediaId)
        } else if (givingUp) {
            TrackLog.w("nuvx", "$mediaId has failed $attempts times; leaving it alone", about = mediaId)
        }
        QualityUpgrade.forget(mediaId)
        val diedOnModuleStream = uri?.let(QualityUpgrade::cacheTag)
            ?.let { it == "hifi" || it.startsWith("hifi-") } == true
        if (diedOnModuleStream) {
            QualityUpgrade.refuseUpgrades(mediaId)
        }
        NerdStats.clearDeclared(mediaId)
        uri?.getQueryParameter("v")
            ?.takeIf { diedOnModuleStream || StreamChoice.isSubstitute(it) }
            ?.let { videoId ->
                StreamChoice.refuseSubstitutes(videoId)
                TrackLog.w(
                    "nuvx",
                    "$videoId broke on a substituted stream; YouTube serves it for now",
                    about = mediaId,
                )
                QualityUpgrade.refuseUpgrades(videoId)
            }
        uri?.getQueryParameter("v")?.let(StreamChoice::forget)
        val networkFault = isNetworkFault(error)
        scope.launch(TrackLog.about(mediaId)) {
            delay(RECOVERY_DELAY_MS)
            uri?.let { withContext(Dispatchers.IO) { AudioCache.discard(it, keepComplete = networkFault) } }
            if (givingUp) {
                skipReason(verdict, error, neverStarted, attempts)?.let { reason ->
                    withContext(Dispatchers.Main) { skipPastUnplayable(mediaId, reason) }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val player = this@PlaybackErrorRecovery.player() ?: return@withContext
                if (player.currentMediaItem?.mediaId != mediaId) return@withContext
                TrackLog.d("nuvx", "retrying $mediaId from ${position}ms")
                retryingMediaId = mediaId
                player.seekTo(player.currentMediaItemIndex, position)
                player.prepare()
            }
        }
    }

    private fun fallbackFailedAlternativeToYouTube(
        item: MediaItem,
        uri: Uri?,
        position: Long,
        error: PlaybackException,
    ): Boolean {
        val playbackUri = uri ?: return false
        if (playbackUri.authority == "source") return false
        val videoId = playbackUri.getQueryParameter("v")
        val substituted = videoId?.let(StreamChoice::isSubstitute) == true
        if (!PlaybackFallback.isAlternative(playbackUri.toString(), substituted)) return false
        return replaceWithYouTube(
            item = item,
            playbackUri = playbackUri,
            position = position,
            keepComplete = isNetworkFault(error),
            why = "failed on a higher-quality source",
        )
    }

    fun replaceWithYouTube(
        item: MediaItem,
        playbackUri: Uri,
        position: Long,
        keepComplete: Boolean,
        why: String,
    ): Boolean {
        val videoId = playbackUri.getQueryParameter("v")
        val fallback = item.toYouTubeFallbackMediaItem() ?: return false
        val mediaId = item.mediaId

        QualityUpgrade.forget(mediaId)
        QualityUpgrade.refuseUpgrades(mediaId)
        videoId?.let {
            StreamChoice.refuseSubstitutes(it)
            StreamChoice.forget(it)
        }
        NerdStats.clearDeclared(mediaId)
        recoveries.remove(mediaId)
        TrackLog.w(
            "nuvx",
            "$mediaId $why; falling back to YouTube audio",
            about = mediaId,
        )

        scope.launch(TrackLog.about(mediaId)) {
            delay(RECOVERY_DELAY_MS)
            withContext(Dispatchers.IO) { AudioCache.discard(playbackUri, keepComplete = keepComplete) }
            withContext(Dispatchers.Main) {
                val live = player() ?: return@withContext
                if (live.currentMediaItem?.mediaId != mediaId) return@withContext
                onSwapping(mediaId)
                live.replaceMediaItem(live.currentMediaItemIndex, fallback)
                live.seekTo(live.currentMediaItemIndex, position)
                live.prepare()
            }
        }
        return true
    }

    private fun restreamMissingLocalFile(
        player: ExoPlayer,
        item: MediaItem,
        uri: Uri?,
        position: Long,
    ): Boolean {
        val scheme = uri?.scheme
        if (scheme != "file" && scheme != "content") return false
        val mediaId = item.mediaId

        Downloads.forgetMissing(mediaId)
        val restreamed = item.toSong().copy(localUri = null, localPath = null).toMediaItem()
        if (restreamed.localConfiguration?.uri == uri) {
            TrackLog.w(
                "nuvx",
                "$mediaId is a local file that is gone and has no stream behind it",
                about = mediaId,
            )
            return false
        }

        TrackLog.w(
            "nuvx",
            "$mediaId was downloaded but $uri is gone; streaming it instead",
            about = mediaId,
        )
        recoveries.remove(mediaId)
        player.replaceMediaItem(player.currentMediaItemIndex, restreamed)
        player.seekTo(player.currentMediaItemIndex, position)
        player.prepare()
        return true
    }

    private fun replayAsManifest(
        player: ExoPlayer,
        item: MediaItem,
        uri: Uri?,
        position: Long,
    ): Boolean {
        val mediaId = item.mediaId
        if (item.localConfiguration?.mimeType != null) return false
        val mime = StreamContainer.manifestServing(mediaId) ?: return false
        val playbackUri = uri ?: return false
        val reopenedUri = StreamContainer.markedForManifestReopen(playbackUri.toString(), mime)
        if (reopenedUri == playbackUri.toString()) return false

        TrackLog.w(
            "nuvx",
            "$mediaId was served a manifest through a progressive source; reopening it as $mime",
            about = mediaId,
        )
        recoveries.remove(mediaId)
        val declared = item.buildUpon()
            .setUri(reopenedUri)
            .setMimeType(mime)
            .build()
        scope.launch(TrackLog.about(mediaId)) {
            withContext(Dispatchers.IO) { AudioCache.discard(playbackUri) }
            withContext(Dispatchers.Main) {
                val live = player() ?: return@withContext
                if (live.currentMediaItem?.mediaId != mediaId) return@withContext
                live.replaceMediaItem(live.currentMediaItemIndex, declared)
                live.seekTo(live.currentMediaItemIndex, position)
                live.prepare()
            }
        }
        return true
    }

    private fun skipReason(
        verdict: String?,
        error: PlaybackException,
        neverStarted: Boolean,
        attempts: Int,
    ): String? {
        if (verdict != null) return verdict
        if (!neverStarted) return null
        if (consecutiveErrorSkips >= MAX_CONSECUTIVE_SKIPS) {
            TrackLog.w(
                "nuvx",
                "$MAX_CONSECUTIVE_SKIPS tracks in a row failed to start; " +
                    "this is the queue or the connection, not the track — stopping here",
            )
            return null
        }
        consecutiveErrorSkips++
        return "failed to start after $attempts attempts (${error.errorCodeName})"
    }

    private fun skipPastUnplayable(mediaId: String, reason: String) {
        val exoPlayer = player() ?: return
        if (exoPlayer.currentMediaItem?.mediaId != mediaId) return
        if (!exoPlayer.hasNextMediaItem()) {
            TrackLog.w("nuvx", "$reason — and nothing after it in the queue", about = mediaId)
            return
        }
        TrackLog.w("nuvx", "$reason — skipping to the next track", about = mediaId)
        exoPlayer.seekToNextMediaItem()
        exoPlayer.prepare()
    }

    private fun permanentReason(error: Throwable?): String? {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < PERMANENT_CAUSE_DEPTH) {
            if (cause is StreamResolver.PermanentlyUnplayableException) {
                return cause.message ?: "This track cannot be played"
            }
            cause = cause.cause?.takeIf { it !== cause }
        }
        return null
    }

    private companion object {
        const val MAX_RECOVERIES = 2

        const val MAX_CONSECUTIVE_SKIPS = 4

        const val PERMANENT_CAUSE_DEPTH = 8
    }
}

@UnstableApi
internal class PermanentAwareLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isPermanent(loadErrorInfo.exception)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    private fun isPermanent(error: Throwable?): Boolean {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < CAUSE_DEPTH) {
            if (cause is StreamResolver.PermanentlyUnplayableException) return true
            cause = cause.cause?.takeIf { it !== cause }
        }
        return false
    }

    private companion object {
        const val CAUSE_DEPTH = 8
    }
}
