package com.bxane.nuvx.ui.player

import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import com.bxane.nuvx.playback.PlaybackPosition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun rememberCanvasArtwork(song: Song): CanvasArtwork? {
    val canvasEnabled by PlayerSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by PlayerSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val prioritizeSpotifyCanvas by PlayerSettings.prioritizeSpotifyCanvas.collectAsStateWithLifecycle()
    val meteredConnection by PlayerSettings.meteredConnection.collectAsStateWithLifecycle()
    val canvasAllowedNow = canvasEnabled && (meteredConnection != true || canvasOverCellular)
    var canvas by remember(song.title, song.artist) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(song.videoId, song.albumName, canvasAllowedNow, prioritizeSpotifyCanvas) {
        if (!canvasAllowedNow) {
            canvas = null
            return@LaunchedEffect
        }
        canvas = PlayerPlatform.host.cachedCanvas(song)
            ?: canvas?.takeIf { it.matches(song.title, song.artist, song.albumName) }

        if (canvas == null && song.albumName == null) delay(ALBUM_SETTLE_MS)
        canvas = PlayerPlatform.host.canvasFor(song) ?: canvas
    }

    return canvas
}

@Stable
internal class PlayerArtwork(val url: String?, private val context: PlatformContext) {
    var loaded by mutableStateOf(false)
        private set

    var attempt by mutableIntStateOf(0)
        private set

    internal var failed by mutableStateOf(false)

    val request: ImageRequest by derivedStateOf {
        val attempt = attempt
        ImageRequest.Builder(context)
            .data(url)
            .size(ART_PX)
            .apply { if (attempt > 0) memoryCacheKeyExtra("attempt", attempt.toString()) }
            .build()
    }

    fun onState(state: AsyncImagePainter.State) {
        loaded = state is AsyncImagePainter.State.Success
        if (state is AsyncImagePainter.State.Success) failed = false
        if (state is AsyncImagePainter.State.Error) failed = true
    }

    internal fun retry() {
        failed = false
        attempt++
    }
}

@Composable
internal fun rememberPlayerArtwork(remoteArt: String?): PlayerArtwork {
    val context = LocalPlatformContext.current
    val artUrl = remoteArt?.artworkAt(ART_PX)
    val art = remember(artUrl) { PlayerArtwork(artUrl, context) }
    LaunchedEffect(artUrl, art.failed) {
        if (artUrl == null || !art.failed || art.attempt >= ART_RETRIES) return@LaunchedEffect
        delay(ART_RETRY_DELAY_MS)
        art.retry()
    }
    return art
}

@Stable
internal class PlayerScrub {
    var scrubbing by mutableStateOf(false)
        private set
    var value by mutableFloatStateOf(0f)
        private set

    internal var pendingSeek by mutableStateOf<Float?>(null)

    fun shown(positionMs: Long, durationMs: Long): Float {
        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        return when {
            scrubbing -> value
            pendingSeek != null -> pendingSeek!!
            else -> fraction.coerceIn(0f, 1f)
        }
    }

    fun drag(to: Float) {
        scrubbing = true
        value = to
    }

    fun release(seekTo: (Float) -> Unit) {
        pendingSeek = value
        seekTo(value)
        scrubbing = false
    }
}

@Composable
internal fun rememberPlayerScrub(trackId: String, position: PlaybackPosition, durationMs: Long): PlayerScrub {
    val scrub = remember { PlayerScrub() }
    var pendingSeek by scrub::pendingSeek

    LaunchedEffect(durationMs, pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        if (durationMs <= 0) return@LaunchedEffect
        snapshotFlow { position.positionMs }.first { positionMs ->
            abs(positionMs - (target * durationMs).toLong()) < SEEK_SETTLE_TOLERANCE_MS
        }
        pendingSeek = null
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS)
        pendingSeek = null
    }
    LaunchedEffect(trackId) { pendingSeek = null }

    return scrub
}

@Stable
internal class PlayerVolume(
    private val system: SystemVolume,
    private val scope: CoroutineScope,
) {
    val level = Animatable(system.level.value)
    var dragging by mutableStateOf(false)
        private set

    fun drag(to: Float) {
        dragging = true
        scope.launch { level.snapTo(to) }
        system.set(to)
    }

    fun release() {
        dragging = false
    }
}

@Composable
internal fun rememberPlayerVolume(): PlayerVolume {
    val system = PlayerPlatform.host.volume
    val scope = rememberCoroutineScope()
    val volume = remember(system) { PlayerVolume(system, scope) }
    val systemVolume by system.level.collectAsStateWithLifecycle()

    LaunchedEffect(systemVolume) {
        if (!volume.dragging) {
            volume.level.animateTo(systemVolume, tween(durationMillis = 220, easing = FastOutSlowInEasing))
        }
    }

    return volume
}

