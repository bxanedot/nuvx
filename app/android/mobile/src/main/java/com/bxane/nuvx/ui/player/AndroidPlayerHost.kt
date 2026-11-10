package com.bxane.nuvx.ui.player

import com.bxane.nuvx.wear.WearBridge
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.data.canvas.CanvasRepository
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsTranslation
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.LastPlayerScreen
import com.bxane.nuvx.playback.MusicOnlyPlayback
import com.bxane.nuvx.playback.AudioOutputStatus
import com.bxane.nuvx.playback.AudioRouting
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectOutput
import com.bxane.nuvx.playback.audio.bitperfect.DacVolume
import com.bxane.nuvx.playback.cast.CastController
import com.bxane.nuvx.ui.components.backdrop.backdrops.SnapshotBackdrop
import com.bxane.nuvx.ui.components.backdrop.backdrops.rememberSnapshotBackdrop
import com.bxane.nuvx.ui.components.backdrop.backdrops.snapshotBackdropSource
import com.bxane.nuvx.ui.components.isGlassSupported
import com.bxane.nuvx.ui.components.menuBackdropFrost
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.roundToInt

class AndroidPlayerHost(context: Context) : PlayerHost {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    override val settings: PlayerSettingsSource = object : PlayerSettingsSource {
        override val animatedCanvas get() = AppSettings.animatedCanvas
        override val audioQualityCellular get() = AppSettings.audioQualityCellular
        override val audioQualityWifi get() = AppSettings.audioQualityWifi
        override val canvasOverCellular get() = AppSettings.canvasOverCellular
        override val fullBleedArtwork get() = AppSettings.fullBleedArtwork
        override val hideSongStatus get() = AppSettings.hideSongStatus
        override val showQueueHistory get() = AppSettings.showQueueHistory
        override val hideVolumeBar get() = AppSettings.hideVolumeBar
        override val hideStatusBar get() = AppSettings.hideStatusBarInPlayer
        override val lastPlayerScreen get() = AppSettings.lastPlayerScreen
        override val playerTheme get() = AppSettings.playerTheme
        override val lyricsBlur get() = AppSettings.lyricsBlur
        override val lyricsOffsetMs get() = AppSettings.lyricsOffsetMs
        override val lyricsSourceOrder get() = AppSettings.lyricsSourceOrder
        override val meteredConnection get() = AppSettings.meteredConnection
        override val streamCeilingOverride get() = AppSettings.streamCeilingOverride
        override val preferUsbDac get() = AppSettings.preferUsbDac
        override val prioritizeSpotifyCanvas get() = AppSettings.prioritizeSpotifyCanvas
        override val reduceAnimation get() = AppSettings.reduceAnimation
        override val reduceDynamicBlur get() = AppSettings.reduceDynamicBlur
        override val disableBlendEffects get() = AppSettings.disableBlendEffects
        override val showNerdStats get() = AppSettings.showNerdStats
        override val showListenTogetherButton get() = AppSettings.showListenTogetherButton
        override val smartAnalysis get() = AppSettings.smartAnalysis
        override val automixDetail get() = AppSettings.automixDetail
        override val smartFadeEnabled get() = AppSettings.smartFadeEnabled
        override val smartMixInProgress get() = AppSettings.smartMixInProgress
        override val smartMixBlend get() = AppSettings.smartMixBlend
        override val smartMixView get() = AppSettings.smartMixView
        override val smartMixProgress get() = AppSettings.smartMixProgress
        override val smartMixPreview get() = AppSettings.smartMixPreview
        override val smartTransitionWindow get() = AppSettings.smartTransitionWindow
        override val spotifyCanvasAutoHide get() = AppSettings.spotifyCanvasAutoHide
        override val syncedLyrics get() = AppSettings.syncedLyrics
        override val translationLanguage get() = AppSettings.translationLanguage
        override val versionAlignmentInProgress get() = AppSettings.versionAlignmentInProgress

        override fun setLastPlayerScreen(value: LastPlayerScreen) = AppSettings.setLastPlayerScreen(value)
        override fun setLyricsOffsetMs(value: Int) = AppSettings.setLyricsOffsetMs(value)
        override fun setMixViewOpen(open: Boolean) {
            AppSettings.mixViewOpen.value = open
        }
    }

    override fun cachedCanvas(song: Song): CanvasArtwork? = CanvasRepository.cached(song)

    override suspend fun canvasFor(song: Song): CanvasArtwork? = CanvasRepository.canvasFor(song)

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = AndroidCanvasVideo(spec, modifier)

    override val musicVideoSupported: Boolean get() = true

    @Composable
    override fun MusicVideo(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier) =
        AndroidMusicVideo(videoId, fit, onRenderedChanged, modifier)

    override fun showsMusicVideoPicture(videoId: String): Boolean = MusicOnlyPlayback.showsPicture(videoId)

    override val musicVideoAspect: StateFlow<Float> get() = MusicVideoEngine.aspect

    override val musicVideoQuality: StateFlow<MusicVideoQualityUi> get() = MusicVideoEngine.quality

    override fun setMusicVideoQuality(height: Int?) = MusicVideoEngine.setQuality(height)

    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? =
        com.bxane.nuvx.ui.components.rememberRemoteArtworkUrl(song)

    override val volume: SystemVolume by lazy { CastAwareVolume(MusicStreamVolume(app), scope) }

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> {
        val outputs = rememberAndroidAudioOutputs()
        val cast by CastController.state.collectAsStateWithLifecycle()
        val watch by WearBridge.watchOutput.collectAsStateWithLifecycle()
        return if (cast.casting || watch.active) outputs.map { it.copy(isActive = false) } else outputs
    }

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = rememberAndroidOutputPicker(onOpen)

    override fun selectAudioOutput(id: Int?) {
        if (CastController.state.value.casting) CastController.disconnect(resumeHere = true)
        AudioRouting.select(id)
        WearBridge.leaveWatchOutput()
    }

    override val watchOutput: StateFlow<WatchOutputUi> by lazy {
        WearBridge.watchOutput
            .map { WatchOutputUi(available = it.available, name = it.name, active = it.active) }
            .stateIn(scope, SharingStarted.Eagerly, WatchOutputUi())
    }

    override fun refreshWatchOutput() = WearBridge.refreshWatchOutput(app)

    override fun selectWatchOutput() {
        if (CastController.state.value.casting) CastController.disconnect(resumeHere = false)
        WearBridge.selectWatchOutput()
    }

    override val outputFormat: StateFlow<OutputFormatUi> by lazy {
        combine(AudioOutputStatus.current, BitPerfectOutput.status) { status, bitPerfect ->
            status.toOutputFormat(bitPerfect)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            AudioOutputStatus.current.value.toOutputFormat(BitPerfectOutput.status.value),
        )
    }

    override val party: StateFlow<PartyUi> by lazy {
        ListenTogether.state
            .map { it.toPartyUi() }
            .stateIn(scope, SharingStarted.Eagerly, ListenTogether.state.value.toPartyUi())
    }

    override suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult =
        when (val result = LyricsTranslation.translate(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.Result.Translated -> LyricsTranslationResult.Translated(result.lines)
            is LyricsTranslation.Result.SameLanguage -> LyricsTranslationResult.SameLanguage(result.language)
            LyricsTranslation.Result.Unavailable -> LyricsTranslationResult.Unavailable
        }

    override suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult =
        when (val result = LyricsTranslation.romanize(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.RomanizationResult.Romanized -> LyricsRomanizationResult.Romanized(result.lines)
            LyricsTranslation.RomanizationResult.AlreadyRomanized -> LyricsRomanizationResult.AlreadyRomanized
            LyricsTranslation.RomanizationResult.Unavailable -> LyricsRomanizationResult.Unavailable
        }

    override fun showMessage(message: String) {
        main.post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
    }

    override val castState: StateFlow<CastUi> by lazy {
        CastController.ensureStarted(app)
        CastController.state
            .map { CastUi(supported = it.supported, connectedName = it.connectedName, connecting = it.connecting) }
            .stateIn(scope, SharingStarted.Eagerly, CastUi())
    }

    @Composable
    override fun CastDialog(hazeState: HazeState, onDismiss: () -> Unit) =
        com.bxane.nuvx.ui.components.CastDialog(hazeState = hazeState, onDismiss = onDismiss)

    @Composable
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) =
        com.bxane.nuvx.ui.components.AudioPipelineDialog(
            hazeState = hazeState,
            onDismiss = onDismiss,
            isPlaying = isPlaying,
        )

    @Composable
    override fun rememberPlayerFrost(): PlayerFrost? {
        if (!isGlassSupported()) return null
        val backdrop = rememberSnapshotBackdrop()
        return remember(backdrop) { BackdropPlayerFrost(backdrop) }
    }

    override val lyricsShareAvailable: Boolean get() = true

    @Composable
    override fun LyricsShareSheet(
        hazeState: HazeState,
        request: LyricsShareRequest,
        onDismiss: () -> Unit,
    ) = AndroidLyricsShareSheet(hazeState = hazeState, request = request, onDismiss = onDismiss)
}

private fun AudioOutputStatus.Snapshot.toOutputFormat(bitPerfect: BitPerfectOutput.Status): OutputFormatUi {
    val encoding = if (bitPerfect.active) "${bitPerfect.outputBits}-bit PCM" else AudioOutputStatus.encodingLabel(this)
    val rate = (if (bitPerfect.active) bitPerfect.sampleRateHz else actualSampleRateHz)?.takeIf { it > 0 }
    val format = if (rate == null) {
        encoding
    } else {
        "$encoding · ${"%.1f".format(Locale.ROOT, rate / 1000f).removeSuffix(".0")} kHz"
    }
    val dacLimited = bitPerfect.active && bitPerfect.dacLimited
    return OutputFormatUi(
        summary = if (dacLimited) "$format · DAC max" else format,
        bitPerfect = bitPerfect.active && bitPerfect.exact,
        dacLimited = dacLimited,
    )
}

private fun ListenTogether.State.toPartyUi() = PartyUi(
    inParty = inParty,
    controlsLocked = controlsLocked,
    members = members,
    you = you,
    code = code,
)

private class CastAwareVolume(
    private val system: SystemVolume,
    scope: CoroutineScope,
) : SystemVolume {
    override val level: StateFlow<Float> = combine(
        system.level,
        CastController.volume,
        CastController.state.map { it.casting },
        WearBridge.watchOutput,
        DacVolume.level,
    ) { phone, receiver, casting, watch, dac ->
        when {
            casting -> receiver
            watch.active -> watch.volume
            dac != null -> dac
            else -> phone
        }
    }
        .stateIn(scope, SharingStarted.Eagerly, system.level.value)

    override fun set(level: Float) {
        when {
            CastController.state.value.casting -> CastController.setVolume(level)
            WearBridge.watchOutput.value.active -> WearBridge.setWatchVolume(level)
            DacVolume.level.value != null -> DacVolume.set(level)
            else -> system.set(level)
        }
    }

    override fun refresh() = system.refresh()
}

private class MusicStreamVolume(private val context: Context) : SystemVolume {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val _level = MutableStateFlow(read())
    override val level: StateFlow<Float> = _level.asStateFlow()

    init {
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
            },
        )
    }

    private fun max(): Int = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

    private fun read(): Float =
        (manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max()

    override fun set(level: Float) {
        manager?.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max()).roundToInt(), 0)
    }

    override fun refresh() {
        _level.value = read()
    }
}

private class BackdropPlayerFrost(private val backdrop: SnapshotBackdrop) : PlayerFrost {
    @Composable
    override fun Modifier.frostSource(): Modifier = snapshotBackdropSource(backdrop)

    override fun capture() = backdrop.capture()

    override fun release() = backdrop.release()

    override fun Modifier.frostPanel(shape: Shape, fill: Color): Modifier = menuBackdropFrost(backdrop, shape, fill)
}
