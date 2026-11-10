package com.bxane.nuvx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.data.listentogether.PartyMember
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.settings.LastPlayerScreen
import com.bxane.nuvx.data.settings.PlayerTheme
import com.bxane.nuvx.data.settings.SmartAnalysis
import com.bxane.nuvx.data.settings.MixBlend
import com.bxane.nuvx.data.settings.MixTransitionView
import com.bxane.nuvx.data.settings.TransitionWindow
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface PlayerHost {
    val settings: PlayerSettingsSource

    fun cachedCanvas(song: Song): CanvasArtwork?

    suspend fun canvasFor(song: Song): CanvasArtwork?

    @Composable
    fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier)

    val musicVideoSupported: Boolean get() = false

    @Composable
    fun MusicVideo(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier) = Unit

    fun showsMusicVideoPicture(videoId: String): Boolean = true

    val musicVideoAspect: StateFlow<Float> get() = DefaultVideoAspect

    val musicVideoQuality: StateFlow<MusicVideoQualityUi> get() = NoVideoQuality

    fun setMusicVideoQuality(height: Int?) = Unit

    @Composable
    fun rememberRemoteArtworkUrl(song: Song?): String?

    val volume: SystemVolume

    @Composable
    fun rememberAudioOutputs(): List<AudioOutputDevice>

    @Composable
    fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit

    fun selectAudioOutput(id: Int?)

    val outputFormat: StateFlow<OutputFormatUi>

    val party: StateFlow<PartyUi>

    suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult

    suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult

    fun showMessage(message: String)

    @Composable
    fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit)

    val castState: StateFlow<CastUi> get() = NoCast

    val watchOutput: StateFlow<WatchOutputUi> get() = NoWatch

    fun refreshWatchOutput() = Unit

    fun selectWatchOutput() = Unit

    @Composable
    fun CastDialog(hazeState: HazeState, onDismiss: () -> Unit) = Unit

    @Composable
    fun rememberPlayerFrost(): PlayerFrost? = null

    val lyricsShareAvailable: Boolean get() = false

    @Composable
    fun LyricsShareSheet(hazeState: HazeState, request: LyricsShareRequest, onDismiss: () -> Unit) = Unit
}

private val NoCast = MutableStateFlow(CastUi())

private val DefaultVideoAspect = MutableStateFlow(16f / 9f)

private val NoVideoQuality = MutableStateFlow(MusicVideoQualityUi())

data class MusicVideoQualityUi(
    val heights: List<Int> = emptyList(),
    val held: Int? = null,
    val activeHeight: Int? = null,
)

internal fun videoQualityLabel(height: Int): String = when {
    height >= 4320 -> "8K"
    height >= 2160 -> "4K"
    height >= 1440 -> "2K"
    height >= 1080 -> "FullHD"
    height >= 720 -> "HD"
    else -> "${height}p"
}

internal fun showsMusicVideo(song: Song): Boolean =
    song.isVideo && PlayerPlatform.host.musicVideoSupported &&
        PlayerPlatform.host.showsMusicVideoPicture(song.videoId)

@Composable
internal fun MusicVideoSurface(
    videoId: String,
    modifier: Modifier = Modifier,
    fit: Boolean = true,
    onRenderedChanged: (Boolean) -> Unit = {},
) = PlayerPlatform.host.MusicVideo(videoId, fit, onRenderedChanged, modifier)

private val NoWatch = MutableStateFlow(WatchOutputUi())

data class WatchOutputUi(
    val available: Boolean = false,
    val name: String? = null,
    val active: Boolean = false,
)

data class CastUi(
    val supported: Boolean = false,
    val connectedName: String? = null,
    val connecting: Boolean = false,
)

object PlayerPlatform {
    @Volatile
    private var installed: PlayerHost? = null

    val host: PlayerHost
        get() = installed ?: error("PlayerPlatform.install() was never called")

    fun install(host: PlayerHost) {
        installed = host
    }
}

interface PlayerSettingsSource {
    val animatedCanvas: StateFlow<Boolean>
    val audioQualityCellular: StateFlow<AudioQuality>
    val audioQualityWifi: StateFlow<AudioQuality>
    val canvasOverCellular: StateFlow<Boolean>
    val fullBleedArtwork: StateFlow<Boolean>
    val hideSongStatus: StateFlow<Boolean>
    val showQueueHistory: StateFlow<Boolean>
    val hideVolumeBar: StateFlow<Boolean>
    val hideStatusBar: StateFlow<Boolean>
    val lastPlayerScreen: StateFlow<LastPlayerScreen>
    val playerTheme: StateFlow<PlayerTheme>
    val lyricsBlur: StateFlow<Boolean>
    val lyricsOffsetMs: StateFlow<Int>
    val lyricsSourceOrder: StateFlow<List<LyricsSource>>
    val meteredConnection: StateFlow<Boolean?>
    val streamCeilingOverride: StateFlow<AudioQuality?>
    val preferUsbDac: StateFlow<Boolean>
    val prioritizeSpotifyCanvas: StateFlow<Boolean>
    val reduceAnimation: StateFlow<Boolean>
    val reduceDynamicBlur: StateFlow<Boolean>
    val disableBlendEffects: StateFlow<Boolean>
    val showNerdStats: StateFlow<Boolean>
    val showListenTogetherButton: StateFlow<Boolean>
    val smartAnalysis: StateFlow<SmartAnalysis>
    val automixDetail: StateFlow<String?>
    val smartFadeEnabled: StateFlow<Boolean>
    val smartMixInProgress: StateFlow<Boolean>
    val smartMixBlend: StateFlow<MixBlend?>
    val smartMixView: StateFlow<MixTransitionView?>
    val smartMixProgress: StateFlow<Float>
    val smartMixPreview: StateFlow<MixTransitionView?>
    val smartTransitionWindow: StateFlow<TransitionWindow?>
    val spotifyCanvasAutoHide: StateFlow<Boolean>
    val syncedLyrics: StateFlow<Boolean>
    val translationLanguage: StateFlow<String>
    val versionAlignmentInProgress: StateFlow<Boolean>

    fun setLastPlayerScreen(value: LastPlayerScreen)
    fun setLyricsOffsetMs(value: Int)
    fun setMixViewOpen(open: Boolean)
}

internal val PlayerSettings: PlayerSettingsSource
    get() = PlayerPlatform.host.settings

const val MIN_LYRICS_OFFSET_MS = -5_000
const val MAX_LYRICS_OFFSET_MS = 5_000

interface SystemVolume {
    val level: StateFlow<Float>
    fun set(level: Float)

    fun refresh()
}

enum class AudioOutputKind { PHONE, WIRED, USB, BLUETOOTH, HDMI, OTHER }

data class AudioOutputDevice(
    val id: Int,
    val name: String,
    val kind: AudioOutputKind,
    val isActive: Boolean = false,
    val form: BluetoothForm = BluetoothForm.UNKNOWN,
)

enum class BluetoothForm { UNKNOWN, BUDS, HEADPHONES, SPEAKER, CAR }

data class OutputFormatUi(
    val summary: String = "",
    val bitPerfect: Boolean = false,
    val dacLimited: Boolean = false,
)

data class PartyUi(
    val inParty: Boolean = false,
    val controlsLocked: Boolean = false,
    val members: List<PartyMember> = emptyList(),
    val you: PartyMember? = null,
    val code: String? = null,
)

sealed interface LyricsTranslationResult {
    data class Translated(val lines: List<LyricLine>) : LyricsTranslationResult
    data class SameLanguage(val language: String) : LyricsTranslationResult
    data object Unavailable : LyricsTranslationResult
}

sealed interface LyricsRomanizationResult {
    data class Romanized(val lines: List<LyricLine>) : LyricsRomanizationResult
    data object AlreadyRomanized : LyricsRomanizationResult
    data object Unavailable : LyricsRomanizationResult
}

enum class CanvasContentMode {
    CROP,
    FIT_PORTRAIT,
}

class CanvasVideoSpec(
    val canvas: CanvasArtwork,
    val isPlaying: Boolean,
    val contentMode: CanvasContentMode,
    val alignPortraitTop: Boolean,
    val onAspectRatioChanged: (Float) -> Unit,
    val portraitRevealBounds: IntSize,
    val presentationAlpha: () -> Float,
    val onRenderedChanged: (Boolean) -> Unit,
    val onFrameCaptured: (ImageBitmap) -> Unit,
    val refreshFrameEveryMs: Long?,
    val frameCapturePx: Int,
    val onCoverChanged: (Float) -> Unit,
    val bottomFade: Float,
    val bottomFadeEndPx: Float?,
    val pausedForTransition: Boolean,
    val resumeDelayMs: Long,
    val startPositionMs: Long,
    val revealInstantly: Boolean,
    val captureStill: Boolean,
    val onStillCaptured: (ImageBitmap?, Long) -> Unit,
    val onResumed: () -> Unit,
)

@Composable
fun CanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentMode: CanvasContentMode = CanvasContentMode.CROP,
    alignPortraitTop: Boolean = false,
    onAspectRatioChanged: (Float) -> Unit = {},
    portraitRevealBounds: IntSize = IntSize.Zero,
    presentationAlpha: () -> Float = { 1f },
    onRenderedChanged: (Boolean) -> Unit = {},
    onFrameCaptured: (ImageBitmap) -> Unit = {},
    refreshFrameEveryMs: Long? = null,
    frameCapturePx: Int = 0,
    onCoverChanged: (Float) -> Unit = {},
    bottomFade: Float = 0f,
    bottomFadeEndPx: Float? = null,
    pausedForTransition: Boolean = false,
    resumeDelayMs: Long = 0L,
    startPositionMs: Long = 0L,
    revealInstantly: Boolean = false,
    captureStill: Boolean = false,
    onStillCaptured: (ImageBitmap?, Long) -> Unit = { _, _ -> },
    onResumed: () -> Unit = {},
) {
    PlayerPlatform.host.CanvasVideo(
        CanvasVideoSpec(
            canvas = canvas,
            isPlaying = isPlaying,
            contentMode = contentMode,
            alignPortraitTop = alignPortraitTop,
            onAspectRatioChanged = onAspectRatioChanged,
            portraitRevealBounds = portraitRevealBounds,
            presentationAlpha = presentationAlpha,
            onRenderedChanged = onRenderedChanged,
            onFrameCaptured = onFrameCaptured,
            refreshFrameEveryMs = refreshFrameEveryMs,
            frameCapturePx = frameCapturePx,
            onCoverChanged = onCoverChanged,
            bottomFade = bottomFade,
            bottomFadeEndPx = bottomFadeEndPx,
            pausedForTransition = pausedForTransition,
            resumeDelayMs = resumeDelayMs,
            startPositionMs = startPositionMs,
            revealInstantly = revealInstantly,
            captureStill = captureStill,
            onStillCaptured = onStillCaptured,
            onResumed = onResumed,
        ),
        modifier,
    )
}

const val CANVAS_FRAME_CAPTURE_PX = 128

object RepeatModes {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

@Composable
internal fun rememberAudioOutputs(): List<AudioOutputDevice> = PlayerPlatform.host.rememberAudioOutputs()

@Composable
internal fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit =
    PlayerPlatform.host.rememberOutputPicker(onOpen)

@Composable
internal fun rememberRemoteArtworkUrl(song: Song?): String? =
    PlayerPlatform.host.rememberRemoteArtworkUrl(song)

@Composable
internal fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) =
    PlayerPlatform.host.AudioPipelineDialog(hazeState, isPlaying, onDismiss)
