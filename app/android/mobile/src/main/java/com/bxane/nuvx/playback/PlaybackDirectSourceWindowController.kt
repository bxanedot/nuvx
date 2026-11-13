package com.bxane.nuvx.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class PlaybackDirectSourceWindowController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val spare: () -> ExoPlayer?,
    private val crossfade: () -> CrossfadeController?,
    private val streamUpgrade: StreamUpgradeController,
    private val errorRecovery: PlaybackErrorRecovery,
) {
    private var lengthCheckedUri: String? = null
    private var lastSeekAt = 0L

    fun onSeek() {
        lastSeekAt = SystemClock.elapsedRealtime()
    }

    fun watch() {
        scope.launch {
            while (isActive) {
                drive()
                delay(DIRECT_WINDOW_STEP_MS)
            }
        }
    }

    fun drive() {
        val exoPlayer = player() ?: return
        checkSubstituteLength(exoPlayer)
        val audible = buildSet {
            exoPlayer.currentMediaItem?.mediaId?.let(::add)
            if (crossfade()?.isTransitioning() == true) spare()?.currentMediaItem?.mediaId?.let(::add)
        }
        if (DirectSourceGate.onPlaying(audible)) AudioCache.cancelDirect()
        if (!DirectSourceGate.isActive() || !exoPlayer.isPlaying) return
        if (SystemClock.elapsedRealtime() - lastSeekAt < SEEK_SETTLE_MS) return
        val current = exoPlayer.currentMediaItem ?: return
        val nextIndex = exoPlayer.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val next = exoPlayer.getMediaItemAt(nextIndex)
        if (MusicOnlyPlayback.wants(next)) return
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val boundary = crossfade()?.plannedTransitionFor(current.mediaId) ?: duration
        val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(MIN_WINDOW_SPEED)
        val remaining = ((boundary - exoPlayer.currentPosition) / speed).toLong()
        if (remaining > DirectSourceGate.WINDOW_MS) return
        if (!DirectSourceGate.openNext(next.mediaId)) return
        TrackLog.d(
            "nuvx",
            "direct window open: ${next.mediaId} with ${remaining}ms to the transition",
            about = next.mediaId,
        )
        val song = next.toSong()
        AudioCache.noteSongs(listOf(song))
        AudioCache.prefetchDirect(AudioCache.Upcoming(song.videoId, TrackMatcher.targetOf(song)))
    }

    private fun checkSubstituteLength(exoPlayer: ExoPlayer) {
        val item = exoPlayer.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (uri.scheme != "nuvx" || uri.authority == "source") return
        if (QualityUpgrade.cacheTag(uri) != null) return
        val key = uri.toString()
        if (key == lengthCheckedUri) return
        if (exoPlayer.playbackState != Player.STATE_READY) return
        val decodedMs = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        lengthCheckedUri = key
        val videoId = uri.getQueryParameter("v") ?: return
        val expectedSec = SourceResolver.targetIn(uri).durationSec
            ?: DirectSourceGate.knownLength(videoId)
            ?: return
        val expectedMs = expectedSec * 1_000L
        val substituted = StreamChoice.isSubstitute(videoId)
        val unattributed = StreamChoice.of(videoId) == null && SourceResolver.canSubstituteForYouTube()
        val wrong = when {
            substituted -> abs(decodedMs - expectedMs) > streamUpgrade.upgradeLengthSlackMs + 1_000L
            unattributed -> decodedMs < expectedMs / 2
            else -> false
        }
        if (!wrong) return
        TrackLog.w(
            "nuvx",
            "'${item.mediaMetadata.title}' decodes to ${decodedMs}ms against ${expectedSec}s listed " +
                "(tolerance ±${AppSettings.upgradeLengthSlackSeconds.value}s) on " +
                if (substituted) "an addon copy" else "a cached copy of unknown origin",
            about = item.mediaId,
        )
        errorRecovery.replaceWithYouTube(
            item = item,
            playbackUri = uri,
            position = 0L,
            keepComplete = false,
            why = "is the wrong length on the copy it started on",
        )
    }

    private companion object {
        const val DIRECT_WINDOW_STEP_MS = 500L
        const val SEEK_SETTLE_MS = 2_000L
        const val MIN_WINDOW_SPEED = 0.25f
    }
}
