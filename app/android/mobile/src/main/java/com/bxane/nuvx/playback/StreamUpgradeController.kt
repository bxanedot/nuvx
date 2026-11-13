package com.bxane.nuvx.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.StreamFormat
import com.bxane.nuvx.data.sources.TrackMatcher
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class ManualUpgradeThrottle(private val cooldownMs: Long) {
    private var lastAcceptedAtMs: Long? = null

    @Synchronized
    fun tryAcquire(nowMs: Long): Long {
        val previous = lastAcceptedAtMs
        if (previous != null) {
            val remaining = cooldownMs - (nowMs - previous)
            if (remaining > 0) return remaining
        }
        lastAcceptedAtMs = nowMs
        return 0L
    }
}

@UnstableApi
internal class StreamUpgradeController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val crossfade: () -> CrossfadeController?,
    private val audioFormatFor: () -> String?,
    private val buildAuditionPlayer: (BandwidthMeter) -> ExoPlayer,
    private val onSwapping: (String) -> Unit,
    private val onSwapCut: () -> Unit,
) {

    private val manualUpgradeThrottle = ManualUpgradeThrottle(MANUAL_UPGRADE_COOLDOWN_MS)

    private var upgradeJob: Job? = null

    private var upgradeFor: String? = null

    fun cancelStaleUpgradeJob() {
        if (QualityUpgrade.isAuditioning(upgradeFor)) return
        upgradeJob?.cancel()
    }

    fun lookForBetterCopy(player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val alreadyPending = QualityUpgrade.isPending(mediaId)
        val shelved = QualityUpgrade.shelvedFor(mediaId)
        if (shelved == null && !alreadyPending && !QualityUpgrade.couldStillUpgrade(mediaId, uri)) return
        if (upgradeJob?.isActive == true) {
            if (upgradeFor == mediaId) return
            cancelStaleUpgradeJob()
        }
        upgradeFor = mediaId
        if (alreadyPending) {
            TrackLog.d("nuvx", "looking again for a better copy of $mediaId", about = mediaId)
        }
        upgradeJob = scope.launch(TrackLog.about(mediaId)) {
            if (shelved != null) {
                TrackLog.d("nuvx", "re-offering the upgrade already proved for $mediaId")
                NerdStats.onLosslessRaceStart(mediaId)
                try {
                    swapIn(mediaId, shelved)
                } finally {
                    NerdStats.onLosslessRaceEnd(mediaId)
                }
                return@launch
            }
            val playingSeconds = withTimeoutOrNull(DURATION_SETTLE_MS) {
                while (true) {
                    val ms = withContext(Dispatchers.Main) {
                        this@StreamUpgradeController.player()
                            ?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                            ?.duration
                            ?: 0L
                    }
                    if (ms > 0) return@withTimeoutOrNull ((ms + 500) / 1000).toInt()
                    delay(UPGRADE_PROVE_STEP_MS)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            if (!QualityUpgrade.isPending(mediaId) &&
                (uri == null || !adoptCachedTrack(mediaId, uri, playingSeconds))
            ) {
                return@launch
            }
            val better = withContext(Dispatchers.IO) {
                QualityUpgrade.lookAgain(mediaId, playingSeconds)
            } ?: return@launch
            try {
                swapIn(mediaId, better)
            } finally {
                NerdStats.onLosslessRaceEnd(mediaId)
            }
        }
    }

    fun upgradeQualityNow() {
        val player = player() ?: return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (mediaId in NerdStats.racingLossless.value ||
            (upgradeJob?.isActive == true && upgradeFor == mediaId)
        ) {
            TrackLog.d("nuvx", "manual upgrade ignored for $mediaId: upgrade already running", about = mediaId)
            return
        }
        val remaining = manualUpgradeThrottle.tryAcquire(SystemClock.elapsedRealtime())
        if (remaining > 0) {
            TrackLog.d(
                "nuvx",
                "manual upgrade ignored for $mediaId: ${remaining}ms cooldown remaining",
                about = mediaId,
            )
            return
        }
        OriginalVersion.unpin(mediaId)
        SourceRegistry.clearCompletedAddonTrackCalls()
        QualityUpgrade.askByHand(mediaId)
        TrackLog.d("nuvx", "upgrade asked for by hand for $mediaId", about = mediaId)
        lookForBetterCopy(player)
    }

    private suspend fun adoptCachedTrack(mediaId: String, uri: Uri, durationSec: Int?): Boolean {
        val format = withContext(Dispatchers.Main) {
            player()
                ?.takeIf { it.currentMediaItem?.mediaId == mediaId && audioFormatFor() == mediaId }
                ?.audioFormat
        } ?: return false
        val mime = format.sampleMimeType ?: return false
        val videoId = uri.getQueryParameter("v") ?: return false
        val downloaded = com.bxane.nuvx.download.Downloads.savedUri(context, videoId) != null
        if (downloaded) return false
        val target = SourceResolver.targetIn(uri)
        val expectedSec = target.durationSec
        if (TrackMatcher.isSevereMismatch(expectedSec, durationSec)) {
            TrackLog.w(
                "nuvx",
                "cached rendition duration mismatch: playing ${durationSec}s vs catalogue ${expectedSec}s for $videoId; discarding cache rendition",
                about = mediaId,
            )
            withContext(Dispatchers.IO) { AudioCache.discardRendition(uri) }
        }
        return QualityUpgrade.adoptUnresolved(
            mediaId = mediaId,
            uri = uri,
            target = target,
            playingMime = mime,
            playing = withContext(Dispatchers.IO) { cachedFloor(uri, format, durationSec) },
        )
    }

    private fun cachedFloor(uri: Uri, format: Format, durationSec: Int?): StreamFormat? {
        format.bitrate.takeIf { it != Format.NO_VALUE && it > 0 }?.let {
            return StreamFormat(kbps = it / 1000)
        }
        val seconds = durationSec?.takeIf { it > 0 } ?: return null
        val bytes = AudioCache.contentLengthOf(uri).takeIf { it > 0 } ?: return null
        return StreamFormat(kbps = (bytes * 8 / seconds / 1000).toInt())
    }

    private class SwapPoint(
        val item: MediaItem,
        val uri: String,
        val position: Long,
        val duration: Long,
    )

    private suspend fun swapIn(mediaId: String, stream: SourceStream) {
        if (!SourceRegistry.permitsForPlayback(stream.sourceConfigId)) {
            TrackLog.d("nuvx", "upgrade dropped: the quality ceiling no longer allows its source", about = mediaId)
            return
        }
        val at = withContext(Dispatchers.Main) { swapPointFor(mediaId) } ?: return
        if (at.duration > 0 && at.duration - at.position < UPGRADE_MIN_REMAINING_MS) {
            TrackLog.d("nuvx", "upgrade abandoned: only ${at.duration - at.position}ms of the track left")
            return
        }

        val upgradedUri = QualityUpgrade.upgradedUri(at.uri)
        val alreadyFilled = QualityUpgrade.forcedStream(Uri.parse(upgradedUri))?.url == stream.url
        QualityUpgrade.force(mediaId, stream)
        val warmedThrough = auditionUpgrade(mediaId, at, upgradedUri, stream, alreadyFilled)
            ?.takeIf { SourceRegistry.permitsForPlayback(stream.sourceConfigId) }
        if (warmedThrough == null) {
            QualityUpgrade.forget(mediaId)
            withContext(Dispatchers.IO) { AudioCache.discardRendition(Uri.parse(upgradedUri)) }
            return
        }

        var waitedForCrossfade = 0L
        while (withContext(Dispatchers.Main) { crossfade()?.isTransitioning() } == true &&
            waitedForCrossfade < UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS
        ) {
            delay(UPGRADE_CROSSFADE_POLL_MS)
            waitedForCrossfade += UPGRADE_CROSSFADE_POLL_MS
        }

        withContext(Dispatchers.Main) { crossfade()?.msSinceTransition() }?.let { since ->
            if (since < UPGRADE_AFTER_CROSSFADE_MS) {
                val settle = UPGRADE_AFTER_CROSSFADE_MS - since
                TrackLog.d("nuvx", "upgrade for $mediaId holding ${settle}ms; a transition just ended")
                delay(settle)
            }
        }

        withContext(Dispatchers.Main) {
            val now = swapPointFor(mediaId)
            if (crossfade()?.isTransitioning() == true) {
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("nuvx", "upgrade for $mediaId shelved: a crossfade was still running")
                return@withContext
            }
            if (now == null) {
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("nuvx", "upgrade for $mediaId proved but the queue moved on; shelved")
                return@withContext
            }
            val player = player() ?: return@withContext
            if (now.duration > 0 && now.duration - now.position < UPGRADE_MIN_REMAINING_MS) {
                TrackLog.d("nuvx", "upgrade abandoned: only ${now.duration - now.position}ms of the track left")
                QualityUpgrade.forget(mediaId)
                return@withContext
            }
            if (QualityUpgrade.forcedStream(Uri.parse(upgradedUri)) == null) {
                TrackLog.d("nuvx", "upgrade abandoned: its stream was dropped while it was being proved")
                return@withContext
            }
            if (now.position > warmedThrough) {
                TrackLog.d(
                    "nuvx",
                    "upgrade landing at ${now.position}ms, past the ${warmedThrough}ms warmed for it",
                )
            }

            val previousFormat = NerdStats.declaredFormat(mediaId)
            onSwapping(mediaId)
            onSwapCut()
            val upgradedMetadata = now.item.mediaMetadata.buildUpon()
                .setExtras(Bundle(now.item.mediaMetadata.extras ?: Bundle()).apply {
                    putBoolean(EXTRA_QUALITY_UPGRADED, true)
                })
                .build()
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                now.item.buildUpon()
                    .setUri(upgradedUri)
                    .setMediaMetadata(upgradedMetadata)
                    .withResolvedStreamType(stream.url)
                    .build(),
            )
            val upgradedSourceName = stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: "Upgrade"
            NerdStats.onSourceStream(mediaId, stream.format, upgradedSourceName)
            NerdStats.recordSource(mediaId, upgradedSourceName)
            player.seekTo(player.currentMediaItemIndex, now.position)
            player.prepare()
            QualityUpgrade.unshelve(mediaId)
            TrackLog.d("nuvx", "upgraded to ${stream.format.summary} at ${now.position}ms ($upgradedSourceName)")
            watchUpgrade(mediaId, now.uri, now.position, now.duration, previousFormat)
            if (QualityUpgrade.continueAfterLossySwap(mediaId)) {
                val firstPass = upgradeJob
                scope.launch {
                    firstPass?.join()
                    lookForBetterCopy(player)
                }
            }
            if (!ListenTogether.state.value.inParty) {
                launch(Dispatchers.IO) {
                    AudioCache.warmRange(Uri.parse(upgradedUri), 0, ANALYSIS_HEAD_BYTES)
                }
            }
        }
    }

    private fun swapPointFor(mediaId: String): SwapPoint? {
        val player = player() ?: return null
        val item = player.currentMediaItem ?: return null
        if (item.mediaId != mediaId) return null
        val uri = item.localConfiguration?.uri?.toString() ?: return null
        if (uri.contains("${QualityUpgrade.MARKER}=hifi-")) return null
        if (OriginalVersion.isPinned(mediaId)) {
            TrackLog.d("nuvx", "no swap for $mediaId: it is held on the original", about = mediaId)
            return null
        }
        return SwapPoint(item, uri, player.currentPosition, player.duration)
    }

    private suspend fun auditionUpgrade(
        mediaId: String,
        at: SwapPoint,
        upgradedUri: String,
        stream: SourceStream,
        renditionAlreadyFilled: Boolean,
    ): Long? {
        QualityUpgrade.beginAudition(mediaId)
        val startedAt = SystemClock.elapsedRealtime()
        val meter = CountingBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context))
        withContext(Dispatchers.IO) {
            if (!renditionAlreadyFilled) AudioCache.discardRendition(Uri.parse(upgradedUri))
            AudioCache.warmRange(Uri.parse(upgradedUri), 0, UPGRADE_HEADER_BYTES)
        }
        val audition = withContext(Dispatchers.Main) {
            buildAuditionPlayer(meter).apply {
                setMediaItem(
                    at.item.buildUpon()
                        .setUri(upgradedUri)
                        .withResolvedStreamType(stream.url)
                        .build(),
                )
                seekTo(at.position)
                prepare()
            }
        }
        val warmedThrough: Long?
        try {
            warmedThrough = withTimeoutOrNull(UPGRADE_AUDITION_MS) {
                while (true) {
                    val verdict = withContext(Dispatchers.Main) {
                        auditionVerdict(audition, at.duration, stream, meter.bytes.get())
                    }
                    when (verdict) {
                        is Audition.Ready -> return@withTimeoutOrNull verdict.bufferedTo
                        is Audition.Rejected -> {
                            TrackLog.w("nuvx", "upgrade dropped before it was heard: ${verdict.why}")
                            return@withTimeoutOrNull null
                        }
                        Audition.Waiting -> delay(UPGRADE_PROVE_STEP_MS)
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { audition.release() }
            QualityUpgrade.endAudition(mediaId)
        }
        val took = SystemClock.elapsedRealtime() - startedAt
        if (warmedThrough == null) {
            TrackLog.d("nuvx", "upgrade for $mediaId never proved itself in ${took}ms")
            return null
        }
        TrackLog.d(
            "nuvx",
            "upgrade to ${stream.format.summary} proved in ${took}ms, buffered through ${warmedThrough}ms",
        )
        delay(AUDITION_RELEASE_MS)
        TrackLog.d("nuvx", AudioCache.cachedSummary(Uri.parse(upgradedUri)))
        return warmedThrough
    }

    private fun MediaItem.Builder.withResolvedStreamType(streamUrl: String): MediaItem.Builder =
        StreamContainer.manifestMimeOf(streamUrl)?.let(::setMimeType) ?: this

    private sealed interface Audition {
        data object Waiting : Audition

        class Ready(val bufferedTo: Long) : Audition

        class Rejected(val why: String) : Audition
    }

    val upgradeLengthSlackMs: Long
        get() = AppSettings.upgradeLengthSlackSeconds.value * 1_000L

    private fun auditionVerdict(
        audition: ExoPlayer,
        previousDuration: Long,
        stream: SourceStream,
        bytesLoaded: Long,
    ): Audition {
        audition.playerError?.let {
            return Audition.Rejected("${it.errorCodeName} opening ${stream.format.summary}")
        }
        if (audition.playbackState == Player.STATE_ENDED) {
            return Audition.Rejected("replacement ended immediately")
        }
        if (audition.playbackState != Player.STATE_READY) return Audition.Waiting
        val length = audition.duration
        if (length <= 0) return Audition.Waiting
        if (previousDuration > 0 && abs(length - previousDuration) > upgradeLengthSlackMs) {
            return Audition.Rejected("replacement is ${length}ms against ${previousDuration}ms")
        }
        val mime = audition.audioFormat?.sampleMimeType
        if (mime != null && stream.format.isLossless == true && !NerdStats.isLosslessMime(mime)) {
            return Audition.Rejected("promised ${stream.format.summary}, decoder was handed $mime")
        }
        val buffered = audition.bufferedPosition
        val wantedThrough = (player()?.currentPosition ?: 0L) + UPGRADE_PREBUFFER_MS
        if (buffered >= wantedThrough || audition.bufferedPercentage >= 100) {
            if (bytesLoaded < MIN_PROVEN_LOAD_BYTES) return Audition.Waiting
            return Audition.Ready(buffered)
        }
        return Audition.Waiting
    }

    private fun watchUpgrade(
        mediaId: String,
        previousUri: String,
        position: Long,
        previousDuration: Long,
        previousFormat: StreamFormat?,
    ) {
        if (previousDuration <= 0) return
        scope.launch(TrackLog.about(mediaId)) {
            val agreed = withTimeoutOrNull(UPGRADE_PROVE_MS) {
                while (true) {
                    val current = player()?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                        ?: return@withTimeoutOrNull false
                    val now = current.duration
                    if (now > 0) return@withTimeoutOrNull abs(now - previousDuration) <= upgradeLengthSlackMs
                    if (current.playbackState == Player.STATE_ENDED) return@withTimeoutOrNull false
                    delay(UPGRADE_PROVE_STEP_MS)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (agreed == true) return@launch
            val player = player() ?: return@launch
            val item = player.currentMediaItem ?: return@launch
            if (item.mediaId != mediaId) return@launch
            TrackLog.w(
                "nuvx",
                "upgrade reverted: replacement reports ${player.duration}ms against " +
                    "${previousDuration}ms (state=${player.playbackState}, " +
                    "buffered=${player.bufferedPosition}ms)",
            )
            QualityUpgrade.forget(mediaId)
            if (previousFormat != null) {
                val prevSource = StreamChoice.of(mediaId)?.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: if (StreamChoice.isSubstitute(mediaId)) "Module" else "YouTube"
                NerdStats.onSourceStream(mediaId, previousFormat, prevSource)
            } else {
                NerdStats.clearDeclared(mediaId)
            }
            onSwapping(mediaId)
            val abandoned = item.localConfiguration?.uri
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon()
                    .setUri(previousUri)
                    .setMimeType(null)
                    .build(),
            )
            player.seekTo(player.currentMediaItemIndex, position)
            player.prepare()
            abandoned?.let {
                launch(Dispatchers.IO) {
                    delay(RECOVERY_DELAY_MS)
                    AudioCache.discardRendition(it)
                }
            }
        }
    }

    private class CountingBandwidthMeter(private val delegate: BandwidthMeter) : BandwidthMeter by delegate {
        val bytes = AtomicLong()

        private val counter = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.transferListener?.onTransferInitializing(source, dataSpec, isNetwork)
            }

            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.transferListener?.onTransferStart(source, dataSpec, isNetwork)
            }

            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) {
                bytes.addAndGet(bytesTransferred.toLong())
                delegate.transferListener?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
            }

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.transferListener?.onTransferEnd(source, dataSpec, isNetwork)
            }
        }

        override fun getTransferListener(): TransferListener = counter
    }

    private companion object {
        const val UPGRADE_MIN_REMAINING_MS = 20_000L

        const val MANUAL_UPGRADE_COOLDOWN_MS = 30_000L

        const val UPGRADE_CROSSFADE_POLL_MS = 250L

        const val UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS = 20_000L

        const val UPGRADE_AFTER_CROSSFADE_MS = 5_000L

        const val UPGRADE_PROVE_MS = 10_000L
        const val UPGRADE_PROVE_STEP_MS = 200L

        const val UPGRADE_AUDITION_MS = 25_000L

        const val UPGRADE_PREBUFFER_MS = 12_000L

        const val UPGRADE_HEADER_BYTES = 1L * 1024 * 1024

        const val MIN_PROVEN_LOAD_BYTES = UPGRADE_HEADER_BYTES

        const val ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024

        const val AUDITION_RELEASE_MS = 250L

        const val DURATION_SETTLE_MS = 8_000L
    }

}
