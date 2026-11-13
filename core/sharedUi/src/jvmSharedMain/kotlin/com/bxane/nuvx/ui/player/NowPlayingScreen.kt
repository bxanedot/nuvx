package com.bxane.nuvx.ui.player

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.Constraints
import com.bxane.nuvx.ui.graphics.FADE_CURVE_STOPS
import com.bxane.nuvx.ui.graphics.smootherStep
import com.bxane.nuvx.playback.PlaybackPosition
import com.bxane.nuvx.sharedui.resources.*

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.stringArrayResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bxane.nuvx.ui.components.ExplicitBadge
import com.bxane.nuvx.ui.components.VideoThumbnails
import com.bxane.nuvx.ui.components.menuAnchor
import com.bxane.nuvx.ui.theme.HideStatusBar
import com.bxane.nuvx.ui.theme.rememberScreenOrientation
import com.bxane.nuvx.ui.theme.StatusBarIcons
import com.bxane.nuvx.ui.theme.stableStatusBarInsets
import com.bxane.nuvx.ui.theme.rememberArtworkTopBandLuminance
import com.bxane.nuvx.ui.theme.topBandScrimAlpha
import com.bxane.nuvx.ui.LyricsProviderState
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.canvas.CanvasSource
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.settings.LastPlayerScreen
import com.bxane.nuvx.data.settings.PlayerTheme
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.PlaybackSourceType
import com.bxane.nuvx.data.model.PLAYER_ART_PX
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.playback.BACK_RESTARTS_AFTER_MS
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import com.bxane.nuvx.ui.components.optimizedHazeEffect
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt


internal const val ART_PX = PLAYER_ART_PX

internal const val ART_RETRIES = 3

internal const val ART_RETRY_DELAY_MS = 1_500L

internal const val ALBUM_SETTLE_MS = 700L

private const val REVERT_CUE_MS = 2_600L

internal const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

internal const val SEEK_SETTLE_TIMEOUT_MS = 4_000L

private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
private val ART_TITLE_GAP = 20.dp
private const val QUEUE_TRAVEL_MS = 420
private val DISMISS_STRIP_HEIGHT = 32.dp
private val ART_BOX_TOP_PAD = 8.dp
private const val HERO_FADE_FRACTION = 0.42f
private const val SUBVIEW_STATUS_SCRIM_MIN_ALPHA = 0.40f

private const val MESH_REFRESH_MS = 3_000L


private const val PORTRAIT_SCRIM_MAX_ASPECT = 0.7f

private val PortraitClipScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    0.40f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.70f),
)

internal val PLAYER_GUTTER = 30.dp
internal val PLAYER_MAX_WIDTH = 560.dp
internal val TABLET_PLAYER_MIN_WIDTH = 700.dp

internal val LANDSCAPE_PLAYER_MIN_WIDTH = 560.dp

private const val ARTWORK_EXPANDED_SCALE = 1f
private const val ARTWORK_PAUSE_SHRINK_SCALE = 0.88f
private const val ARTWORK_DRAG_SHRINK_SCALE = 0.94f

private val ArtworkScaleEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private const val ARTWORK_SCALE_DURATION_MS = 500

private val CONTROL_GAP_SPREAD_MAX = 24.dp
private var lastControlSpread: Dp = 0.dp

private var lastHeroSettled = false

private const val DOCK_FADE_LEAD = 0.15f

private const val DOCK_FADE_FLOOR = 1f / 255f

private val TileShape = RoundedCornerShape(8.dp)

private const val LYRICS_CONTROLS_IDLE_MS = 5_000L
private const val SPOTIFY_CANVAS_CONTROLS_IDLE_MS = 5_000L
internal const val SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS = 420
internal const val PLAYER_DECK_FADE_IN_MS = 700
private const val SPOTIFY_DECK_TOP_FADE_FRACTION = 0.28f

@Composable
@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)
fun NowPlayingScreen(
    song: Song,
    playedBy: String? = null,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    audioVersionSwitching: Boolean,
    qualityUpgraded: Boolean,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    signedIn: Boolean,
    accountName: String?,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onBlockedControl: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onQueueDragActiveChange: (Boolean) -> Unit = {},
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    onOpenPlaybackSource: () -> Unit,
    onListenTogether: () -> Unit,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    lyricsUnavailable: Boolean,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    windowWidth: Dp,
    windowHeight: Dp,
    menuOpen: () -> Boolean = { false },
    onLiveCanvasChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()

    val remoteArt = rememberRemoteArtworkUrl(song)
    val artTopLuminance = rememberArtworkTopBandLuminance(remoteArt, ART_PX)
    val artworkStatusScrimAlpha = topBandScrimAlpha(artTopLuminance)

    StatusBarIcons(dark = false)
    val hideStatusBar by PlayerSettings.hideStatusBar.collectAsStateWithLifecycle()
    HideStatusBar(hideStatusBar)

    val playerHaze = remember { HazeState() }
    var showAudioPipeline by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var showAudioOutput by remember { mutableStateOf(false) }
    var showVideoQuality by remember { mutableStateOf(false) }
    var showLyricsProviders by remember { mutableStateOf(false) }
    val openAudioOutput = rememberOutputPicker { showAudioOutput = true }
    val controlsLocked = rememberControlsLocked()
    var showListenTogetherMembers by remember { mutableStateOf(false) }
    val openListenTogetherMembers: () -> Unit = { showListenTogetherMembers = true }

    val syncedLyricsEnabled by PlayerSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsOffsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val lyricsPlayhead = rememberLyricPlayhead(position)
    val seekToLyric: (Long) -> Unit = { lineTimeMs ->
        onSeek(adjustedLyricsSeekTarget(lineTimeMs, lyricsOffsetMs))
    }
    val hideVolumeBar by PlayerSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val disableBlendEffects by PlayerSettings.disableBlendEffects.collectAsStateWithLifecycle()
    val lyricSlotHeight = with(density) {
        MaterialTheme.typography.titleMedium.lineHeight.takeIf { it.isSpecified }?.toDp() ?: 24.dp
    } + 8.dp
    val hideSongStatus by PlayerSettings.hideSongStatus.collectAsStateWithLifecycle()

    val spotifyCanvasAutoHide by PlayerSettings.spotifyCanvasAutoHide.collectAsStateWithLifecycle()
    val mixing by PlayerSettings.smartMixInProgress.collectAsStateWithLifecycle()
    val videoMode = showsMusicVideo(song)
    val squareVideo = videoMode && VideoThumbnails.square
    val canvas = rememberCanvasArtwork(song)?.takeUnless { videoMode }?.let {
        val tall = it.portraitUrl
        if (tall != null && playerFillsWindow(windowWidth)) {
            it.copy(url = tall, fallbackUrl = it.url, portraitUrl = null)
        } else it
    }
    var videoRendered by remember(song.videoId) { mutableStateOf(false) }
    val videoAspectState = PlayerPlatform.host.musicVideoAspect.collectAsStateWithLifecycle()
    val screenOrientation = rememberScreenOrientation()
    var fullscreenRequested by remember { mutableStateOf(false) }
    val sideways = landscapePlayerAvailable(windowWidth, windowHeight)
    LaunchedEffect(sideways) { if (!sideways) fullscreenRequested = false }
    val exitFullscreen = {
        fullscreenRequested = false
        screenOrientation.portrait()
    }
    var canvasAspect by remember(canvas) { mutableFloatStateOf(0f) }
    val canvasRenderedState = remember(canvas?.url) { mutableStateOf(false) }
    var canvasRendered by canvasRenderedState
    var canvasFrame by remember(canvas?.url) { mutableStateOf<ImageBitmap?>(null) }
    var spotifyCanvasControlsOpen by remember(canvas?.url) { mutableStateOf(true) }
    var spotifyCanvasDeckHeight by remember(canvas?.url) { mutableStateOf(0.dp) }
    var spotifyCanvasExpandedTopHeight by remember(canvas?.url) { mutableStateOf(0.dp) }
    val canvasCover = remember(canvas?.url) { mutableFloatStateOf(0f) }
    val playerTheme by PlayerSettings.playerTheme.collectAsStateWithLifecycle()
    val tintsOffClip = playerTheme != PlayerTheme.SIMPLE
    val meshRefreshMs = MESH_REFRESH_MS.takeIf { tintsOffClip }

    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    val livePosition = rememberLivePlaybackPosition(position, isPlaying, durationMs)
    val shown: () -> Float = { scrub.shown(livePosition.value, durationMs) }
    val pastRestartPoint by remember(position) {
        derivedStateOf { position.positionMs > BACK_RESTARTS_AFTER_MS }
    }
    val restoredPlayerScreen = remember { PlayerSettings.lastPlayerScreen.value }
    var queueOpen by remember {
        mutableStateOf(restoredPlayerScreen == LastPlayerScreen.QUEUE)
    }
    var lyricsOpen by remember {
        mutableStateOf(restoredPlayerScreen == LastPlayerScreen.LYRICS)
    }
    val queueSlide = remember { mutableFloatStateOf(0f) }
    var playerPrewarmStage by remember(song.videoId) { mutableIntStateOf(0) }
    LaunchedEffect(song.videoId) {
        delay(600)
        playerPrewarmStage = 1
        delay(650)
        playerPrewarmStage = 2
        delay(650)
        playerPrewarmStage = 3
    }
    var lyricsScrolling by remember { mutableStateOf(false) }
    var queueScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(lyricsOpen) { if (!lyricsOpen) lyricsScrolling = false }
    LaunchedEffect(queueOpen) { if (!queueOpen) queueScrolling = false }
    val panelScrolling = lyricsScrolling || queueScrolling
    var lyricsControlsOpen by remember {
        mutableStateOf(restoredPlayerScreen == LastPlayerScreen.LYRICS)
    }
    var queueControlsOpen by remember { mutableStateOf(true) }
    val playerDeckReveal = remember { Animatable(1f) }
    val playerDeckSettledOpen by remember(playerDeckReveal) {
        derivedStateOf { playerDeckReveal.value >= 0.999f }
    }
    LaunchedEffect(lyricsOpen, queueOpen) {
        PlayerSettings.setLastPlayerScreen(
            when {
                lyricsOpen -> LastPlayerScreen.LYRICS
                queueOpen -> LastPlayerScreen.QUEUE
                else -> LastPlayerScreen.MAIN
            },
        )
        if (queueOpen) queueControlsOpen = true
    }
    val openLyrics: () -> Unit = {
        lyricsControlsOpen = true
        lyricsOpen = true
        queueOpen = false
    }
    val closeLyrics: () -> Unit = {
        lyricsControlsOpen = false
        lyricsOpen = false
    }
    val toggleLyrics: () -> Unit = {
        if (lyricsOpen) closeLyrics() else openLyrics()
    }
    val toggleQueue: () -> Unit = {
        val opening = !queueOpen
        if (opening && lyricsOpen) queueSlide.floatValue = 1f
        if (opening) queueControlsOpen = true
        queueOpen = opening
        if (opening) closeLyrics()
    }
    val lyricsLoadingLines = stringArrayResource(Res.array.lyrics_loading_lines)
    val lyricsLoadingText = remember(song.videoId) { lyricsLoadingLines.random() }
    val lyricsTranslation = rememberLyricsTranslation(
        trackId = song.videoId,
        lyrics = lyrics,
        lyricsSource = lyricsSource,
        lyricsUnavailable = lyricsUnavailable,
        loadingText = lyricsLoadingText,
        haptics = haptics,
    )
    var lyricsShare by remember { mutableStateOf<LyricsShareRequest?>(null) }
    val currentMenuOpen by rememberUpdatedState(menuOpen)
    val currentLyricsOffsetOpen by rememberUpdatedState(lyricsOffsetOpen)
    val overlayOpen = remember {
        derivedStateOf {
            currentMenuOpen() || showAudioPipeline || showCast || showAudioOutput || showVideoQuality ||
                showLyricsProviders || showListenTogetherMembers || lyricsShare != null || currentLyricsOffsetOpen
        }
    }
    val lyricsShareEnabled = lyricsShareAvailable
    val lyricPicker = rememberLyricsPicker(
        song = song,
        lines = lyrics,
        subLines = lyricsTranslation.subLines,
        artworkUrl = remoteArt,
        haptics = haptics,
        onCard = { lyricsShare = it },
    )
    var showRevertCue by remember(song.videoId) { mutableStateOf(false) }
    LaunchedEffect(song.videoId, qualityUpgraded) {
        if (!qualityUpgraded) {
            showRevertCue = false
            return@LaunchedEffect
        }
        showRevertCue = true
        delay(REVERT_CUE_MS)
        showRevertCue = false
    }

    KeepScreenOn(enabled = lyricsOpen)

    PlayerBackHandler(enabled = sideways && (videoMode || fullscreenRequested), onBack = exitFullscreen)

    PlayerBackHandler(enabled = lyricsOpen, onBack = closeLyrics)

    PlayerBackHandler(enabled = queueOpen) { queueOpen = false }

    PlayerBackHandler(enabled = showAudioOutput) { showAudioOutput = false }

    PlayerBackHandler(enabled = showVideoQuality) { showVideoQuality = false }

    PlayerBackHandler(enabled = showLyricsProviders) { showLyricsProviders = false }

    PlayerBackHandler(enabled = showListenTogetherMembers) { showListenTogetherMembers = false }

    PlayerBackHandler(enabled = showAudioPipeline) { showAudioPipeline = false }

    PlayerBackHandler(enabled = showCast) { showCast = false }

    PlayerBackHandler(enabled = lyricsOffsetOpen, onBack = onDismissLyricsOffset)

    PlayerBackHandler(enabled = lyricsShare != null) { lyricsShare = null }

    PlayerBackHandler(enabled = lyricPicker.picking) { lyricPicker.cancel() }

    var queueDragging by remember { mutableStateOf(false) }
    var queueReleased by remember { mutableIntStateOf(0) }
    LaunchedEffect(queueOpen, queueDragging, queueReleased) {
        if (queueDragging) return@LaunchedEffect
        val target = if (queueOpen) 1f else 0f
        val from = queueSlide.floatValue
        if (from == target) return@LaunchedEffect
        animate(
            initialValue = from,
            targetValue = target,
            animationSpec = tween(
                durationMillis = (QUEUE_TRAVEL_MS * abs(target - from)).roundToInt(),
                easing = FastOutSlowInEasing,
            ),
        ) { value, _ -> queueSlide.floatValue = value }
    }

    val swipeThreshold = with(density) { 72.dp.toPx() }
    val swipeSettle = remember { Animatable(0f) }
    val swipeTargets = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(swipeTargets) {
        for (target in swipeTargets) {
            val newest = swipeTargets.tryReceive().getOrNull() ?: target
            launch {
                if (newest != swipeSettle.targetValue) {
                    swipeSettle.animateTo(newest, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
        }
    }
    val setSwipeOffset: (Float) -> Unit = { swipeTargets.trySend(it) }
    val swipeHintShown by remember(swipeThreshold) {
        derivedStateOf { abs(swipeSettle.value) / swipeThreshold > 0.01f }
    }
    val swipeHintNext by remember { derivedStateOf { swipeSettle.value < 0f } }

    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.86f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "artScale",
    )

    val volume = rememberPlayerVolume()
    LaunchedEffect(lyricsOpen, lyricsControlsOpen, scrub.scrubbing, volume.dragging) {
        if (lyricsOpen && lyricsControlsOpen && !scrub.scrubbing && !volume.dragging) {
            delay(LYRICS_CONTROLS_IDLE_MS)
            lyricsControlsOpen = false
        }
    }
    val animatedCollapse = animateFloatAsState(
        targetValue = if (lyricsOpen || queueOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "sleeveCollapse",
    )
    val p: () -> Float = {
        val queueOwnsCollapse = !lyricsOpen &&
            (queueOpen || queueDragging || queueSlide.floatValue > 0.001f)
        if (queueOwnsCollapse) queueSlide.floatValue else animatedCollapse.value
    }
    val collapseStarted by remember { derivedStateOf { p() > 0f } }
    val collapseAtRest by remember { derivedStateOf { p() == 0f } }
    val collapsePastSettling by remember { derivedStateOf { p() >= 0.01f } }
    val collapsePastHalf by remember { derivedStateOf { p() >= 0.5f } }
    val collapseAlmostDone by remember { derivedStateOf { p() >= 0.999f } }
    val collapseDone by remember { derivedStateOf { p() >= 1f } }
    val queueShowing by remember { derivedStateOf { queueSlide.floatValue > 0.01f } }

    val panelFade = remember {
        Animatable(if (Snapshot.withoutReadObservation { collapseDone }) 1f else 0f)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { collapseDone }.collectLatest { settled ->
            panelFade.animateTo(
                if (settled) 1f else 0f,
                tween(durationMillis = 200, easing = FastOutSlowInEasing),
            )
        }
    }
    val fullBleedArt by PlayerSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val spotifyCanvasOnPhone = canvas?.source == CanvasSource.SPOTIFY &&
        playerFillsWindow(windowWidth)
    val heroMode = !videoMode && (
        spotifyCanvasOnPhone ||
            (fullBleedArt && playerFillsWindow(windowWidth))
        )

    val art = rememberPlayerArtwork(remoteArt)
    var heroSettled by remember { mutableStateOf(lastHeroSettled) }
    LaunchedEffect(art, canvasRenderedState) {
        if (heroSettled) return@LaunchedEffect
        snapshotFlow { art.loaded || canvasRenderedState.value }.first { it }
        heroSettled = true
        lastHeroSettled = true
    }
    val heroClip = canvas?.takeIf { heroMode && !collapsePastHalf }
    val spotifyCanvasFullscreen = spotifyCanvasOnPhone &&
        heroClip?.source == CanvasSource.SPOTIFY
    val spotifyCanvasPresentation = spotifyCanvasFullscreen && canvasRendered
    val heroClipMounted = canvas?.takeIf {
        heroMode && (!(lyricsOpen || queueOpen) || !collapseDone)
    }
    val clipFullscreen = spotifyCanvasOnPhone &&
        heroClipMounted?.source == CanvasSource.SPOTIFY
    val clipPortrait = !clipFullscreen &&
        heroClipMounted != null && canvasAspect > 0f && canvasAspect < 1f

    LaunchedEffect(
        spotifyCanvasPresentation,
        spotifyCanvasControlsOpen,
        spotifyCanvasAutoHide,
        isPlaying,
        scrub.scrubbing,
        volume.dragging,
        lyricsOpen,
        queueOpen,
        mixing,
    ) {
        if (!spotifyCanvasPresentation || !spotifyCanvasControlsOpen ||
            !spotifyCanvasAutoHide || !isPlaying || scrub.scrubbing || volume.dragging ||
            lyricsOpen || queueOpen || mixing
        ) return@LaunchedEffect
        delay(SPOTIFY_CANVAS_CONTROLS_IDLE_MS)
        spotifyCanvasControlsOpen = false
    }

    LaunchedEffect(spotifyCanvasPresentation, lyricsOpen, queueOpen) {
        if (spotifyCanvasPresentation && !lyricsOpen && !queueOpen) {
            spotifyCanvasControlsOpen = true
        }
    }
    LaunchedEffect(spotifyCanvasPresentation, mixing) {
        if (spotifyCanvasPresentation && mixing) spotifyCanvasControlsOpen = true
    }
    val heroT = animateFloatAsState(
        targetValue = if (
            heroMode && (heroSettled || art.loaded || canvasRendered)
        ) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "heroCanvas",
    )
    val spotifyChromeAlpha by animateFloatAsState(
        targetValue = if (!spotifyCanvasPresentation || spotifyCanvasControlsOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "spotifyCanvasChrome",
    )
    var heroHeight by remember { mutableStateOf(0.dp) }
    var playerBounds by remember { mutableStateOf(IntSize.Zero) }
    fun canvasBottom(portrait: Boolean): Dp =
        if (portrait && playerBounds.width > 0 && playerBounds.height > 0) {
            val viewAspect = playerBounds.width.toFloat() / playerBounds.height
            val videoHeight = if (canvasAspect >= viewAspect) playerBounds.width / canvasAspect
                else playerBounds.height.toFloat()
            with(density) { videoHeight.toDp() }
        } else 0.dp
    val clipBottom = canvasBottom(clipPortrait)
    val clipFadeFraction = if (clipBottom > 0.dp) {
        (heroHeight.value * HERO_FADE_FRACTION / clipBottom.value).coerceIn(0f, 1f)
    } else 0f
    val statusBarTop = stableStatusBarInsets().asPaddingValues().calculateTopPadding()
    val topStrip = DISMISS_STRIP_HEIGHT

    var dismissBandTop by remember { mutableFloatStateOf(0f) }
    var dismissBandBottom by remember { mutableFloatStateOf(0f) }
    var dismissBandSpace by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val openPlaybackOrigin: () -> Unit = {
        if (playedBy != null) {
            onListenTogether()
        } else if (song.playbackSourceType == PlaybackSourceType.QUEUE) {
            queueOpen = true
            closeLyrics()
        } else {
            onOpenPlaybackSource()
        }
    }

    val skipSwipeGesture = Modifier.pointerInput(showAudioPipeline, showCast, panelScrolling, controlsLocked) {
        if (showAudioPipeline || showCast || panelScrolling) return@pointerInput
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { setSwipeOffset(0f) },
            onDragEnd = {
                val crossed = total <= -swipeThreshold || total >= swipeThreshold
                when {
                    controlsLocked -> if (crossed) onBlockedControl()
                    total <= -swipeThreshold -> {
                        haptics.play(Haptic.SkipNext)
                        onNext()
                    }
                    total >= swipeThreshold -> {
                        haptics.play(Haptic.SkipPrevious)
                        onPrevious()
                    }
                }
                setSwipeOffset(0f)
            },
            onHorizontalDrag = { _, delta ->
                total += delta
                setSwipeOffset(total * 0.35f)
            },
        )
    }

    val onScrub: (Float) -> Unit = scrub::drag
    val onScrubFinished: () -> Unit = {
        haptics.play(Haptic.Select)
        scrub.release(onSeekFraction)
    }
    val onVolumeChange: (Float) -> Unit = volume::drag
    val onVolumeChangeFinished: () -> Unit = volume::release

    val playerActions: @Composable () -> Unit = {
        PlayerActionRow(
            lyricsOpen = lyricsOpen,
            queueOpen = queueOpen,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            autoplayEnabled = autoplayEnabled,
            onToggleLyrics = toggleLyrics,
            onToggleQueue = toggleQueue,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            onToggleAutoplay = onToggleAutoplay,
            onOpenOutput = openAudioOutput,
            onListenTogether = onListenTogether,
            onOpenListenTogetherMembers = openListenTogetherMembers,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            OutputCaption(
                accountName = accountName,
                onOpenOutput = openAudioOutput,
                onOpenMembers = openListenTogetherMembers,
            )
        }
    }

    val playerOverlays: @Composable () -> Unit = {
        if (showVideoQuality) {
            VideoQualitySheet(
                hazeState = playerHaze,
                onDismiss = { showVideoQuality = false },
            )
        }
        if (showAudioOutput) {
            AudioOutputSheet(
                hazeState = playerHaze,
                accountName = accountName,
                onDismiss = { showAudioOutput = false },
                onOpenPipeline = { showAudioPipeline = true },
                onOpenCast = { showCast = true },
            )
        }
        if (showLyricsProviders) {
            LyricsProviderSheet(
                hazeState = playerHaze,
                currentSource = lyricsSource,
                states = lyricsProviderStates,
                onSelect = onSelectLyricsProvider,
                onDismiss = { showLyricsProviders = false },
            )
        }
        if (showAudioPipeline) {
            AudioPipelineDialog(
                hazeState = playerHaze,
                isPlaying = isPlaying,
                onDismiss = { showAudioPipeline = false },
            )
        }
        if (showCast) {
            PlayerPlatform.host.CastDialog(
                hazeState = playerHaze,
                onDismiss = { showCast = false },
            )
        }
        if (showListenTogetherMembers) {
            ListenTogetherMembersSheet(
                hazeState = playerHaze,
                onDismiss = { showListenTogetherMembers = false },
                onManage = {
                    showListenTogetherMembers = false
                    onListenTogether()
                },
            )
        }
        if (lyricsOffsetOpen) {
            LyricsOffsetSheet(
                hazeState = playerHaze,
                onDismiss = onDismissLyricsOffset,
            )
        }
        lyricsShare?.let { request ->
            PlayerPlatform.host.LyricsShareSheet(
                hazeState = playerHaze,
                request = request,
                onDismiss = { lyricsShare = null },
            )
        }
        MixViewOverlay(hazeState = playerHaze)
    }

    val landscape = sideways
    val tabletArtworkBackdrop = tabletSizedPlayer(windowWidth)
    val fullArtworkBlurImage = rememberFullArtworkBlurImage(
        imageUrl = remoteArt,
        artPx = ART_PX,
        prepare = playerTheme == PlayerTheme.SIMPLE || landscape || tabletArtworkBackdrop ||
            lyricsOpen || queueOpen || playerPrewarmStage >= 1,
    )
    val currentFullArtworkBlurImage = rememberUpdatedState(fullArtworkBlurImage)
    val fullArtworkBlurContent = remember {
        movableContentOf<Modifier> { backdropModifier ->
            FullArtworkBlurBackdrop(
                image = currentFullArtworkBlurImage.value,
                modifier = backdropModifier,
            )
        }
    }

    val dock = LocalPlayerDock.current
    if (dock != null) {
        DisposableEffect(dock) {
            onDispose { dock.attached = false }
        }
    }
    val dockFrame = remember { DockFrame() }
    val dockT: () -> Float = {
        if (dock == null) {
            1f
        } else {
            val open = dock.openFraction()
            if (dockFrame.anchor != null && dock.hasMiniArt()) open else 1f
        }
    }
    val docking: () -> Boolean = { dockT() < 1f }
    val dockMoving by remember { derivedStateOf { docking() } }
    val dockFade: () -> Float = { ((dockT() - DOCK_FADE_LEAD) / (1f - DOCK_FADE_LEAD)).coerceIn(0f, 1f) }

    val stillClip = canvas?.takeIf { !clipFullscreen }
    val stillUrl = stillClip?.url
    fun still(): CanvasStill? = CanvasStills.current?.takeIf { it.url == stillUrl }
    val stillMoving = collapseStarted || dockMoving || lyricsOpen || queueOpen || queueDragging
    fun stillTakesOver(): Boolean = still() != null && CanvasStills.fresh &&
        (collapseStarted || dockMoving || lyricsOpen || queueOpen || queueDragging || !canvasRendered)
    fun stillBottomPx(): Float {
        val image = still()?.image ?: return 0f
        if (!heroMode || image.width >= image.height || playerBounds.width <= 0) return 0f
        return minOf(playerBounds.width * image.height / image.width.toFloat(), playerBounds.height.toFloat())
    }
    fun stillFadeFraction(): Float {
        val bottom = stillBottomPx()
        if (bottom <= 0f) return HERO_FADE_FRACTION
        return (with(density) { heroHeight.toPx() } * HERO_FADE_FRACTION / bottom).coerceIn(0f, 1f)
    }
    fun stillAtMount(): CanvasStill? = Snapshot.withoutReadObservation { still() }
    val onStillCaptured: (ImageBitmap?, Long) -> Unit = { image, position ->
        val url = stillClip?.url
        if (image != null && url != null) {
            CanvasStills.current = CanvasStill(url, image, position)
            CanvasStills.fresh = true
        } else {
            CanvasStills.current = null
        }
    }
    val onClipResumed: () -> Unit = { CanvasStills.fresh = false }
    val sleeveLayer = rememberGraphicsLayer()

    val dockHost: Modifier = Modifier
        .then(
            if (dock != null) {
                Modifier.onGloballyPositioned { coordinates ->
                    dockFrame.host = coordinates
                    if (dockFrame.anchor != null && dockFrame.anchorFor == coordinates.size) {
                        return@onGloballyPositioned
                    }
                    val offset = dock.offset() ?: return@onGloballyPositioned
                    dockFrame.anchorFor = coordinates.size
                    dockFrame.anchor = coordinates.positionOnScreen() - Offset(0f, offset.toInt().toFloat())
                    if (!dock.attached) dock.attached = true
                }
            } else {
                Modifier
            },
        )
        .drawWithContent {
            drawContent()
            if (docking()) {
                translate(dockFrame.portalLeft, dockFrame.portalTop) {
                    val portalScale = dockFrame.portalScale
                    scale(portalScale, portalScale, pivot = Offset.Zero) { drawLayer(sleeveLayer) }
                }
            }
        }
        .graphicsLayer { alpha = if (docking()) dockFade().coerceAtLeast(DOCK_FADE_FLOOR) else 1f }

    val reportLiveCanvas by rememberUpdatedState(onLiveCanvasChanged)
    LaunchedEffect(spotifyCanvasPresentation) { reportLiveCanvas(spotifyCanvasPresentation) }
    DisposableEffect(Unit) { onDispose { reportLiveCanvas(false) } }
    val frostOff by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val frost = if (frostOff) null else PlayerPlatform.host.rememberPlayerFrost()
    FrostedPlayerFrame(
        modifier = modifier,
        chrome = !disableBlendEffects && !(landscape && videoMode),
        frost = frost,
        live = spotifyCanvasPresentation,
        overlayOpen = { overlayOpen.value && !currentMenuOpen() },
        overlays = playerOverlays,
    ) {
        if (landscape && videoMode) {
            val versionAligning by PlayerSettings.versionAlignmentInProgress.collectAsStateWithLifecycle()
            val transitionWindow by PlayerSettings.smartTransitionWindow.collectAsStateWithLifecycle()
            Box(modifier = Modifier.fillMaxSize().then(dockHost)) {
                FullscreenVideoPlayer(
                    song = song,
                    isPlaying = isPlaying,
                    artRequest = art.request,
                    rendered = videoRendered,
                    onRenderedChanged = { videoRendered = it },
                    onExitFullscreen = exitFullscreen,
                    onOpenQuality = { showVideoQuality = true },
                    scrubber = {
                        PlayerScrubber(
                            shown = shown,
                            durationMs = durationMs,
                            loading = versionAligning || audioVersionSwitching,
                            mixing = mixing && !scrub.scrubbing,
                            transitionWindow = transitionWindow
                                ?.takeIf { !scrub.scrubbing && it.end > it.start }
                                ?.let { it.start..it.end },
                            onScrub = onScrub,
                            onScrubFinished = onScrubFinished,
                        ) {
                            PlaybackQualityLabel(
                                song = song,
                                isLoading = isLoading,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 8.dp),
                            )
                        }
                    },
                    transport = {
                        TransportRow(
                            isPlaying = isPlaying,
                            isLoading = isLoading || audioVersionSwitching,
                            previousEnabled = !controlsLocked && (hasPrevious || pastRestartPoint),
                            nextEnabled = !controlsLocked && hasNext,
                            onPrevious = onPrevious,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            compact = true,
                        )
                    },
                )
            }
        } else if (landscape) {
            val landscapeArtScale by animateFloatAsState(
                targetValue = when {
                    !isPlaying -> ARTWORK_PAUSE_SHRINK_SCALE
                    scrub.scrubbing -> ARTWORK_DRAG_SHRINK_SCALE
                    else -> ARTWORK_EXPANDED_SCALE
                },
                animationSpec = tween(durationMillis = ARTWORK_SCALE_DURATION_MS, easing = ArtworkScaleEasing),
                label = "landscapeArtworkScale",
            )
            val versionAligning by PlayerSettings.versionAlignmentInProgress.collectAsStateWithLifecycle()
            val transitionWindow by PlayerSettings.smartTransitionWindow.collectAsStateWithLifecycle()
            val panelOpen = lyricsOpen || queueOpen

            fun landscapeDockPose(): LandscapeDockPose? {
                if (dock == null) return null
                val t = dockT()
                val mini = dock.miniArtOnScreen() ?: return null
                val anchor = dockFrame.anchor ?: return null
                val host = dockFrame.host?.takeIf { it.isAttached } ?: return null
                val sleeve = dockFrame.landscapeArt?.takeIf { it.isAttached } ?: return null
                val offset = (dock.offset() ?: return null).toInt().toFloat()
                val travel = dock.sheetTravel.takeIf { it > 0f } ?: return null
                if (sleeve.size.width <= 0) return null
                val open = Rect(
                    offset = anchor + host.localPositionOf(sleeve, Offset.Zero),
                    size = Size(sleeve.size.width.toFloat(), sleeve.size.height.toFloat()),
                )
                val ride = ((mini.top - open.top) / travel).coerceIn(0f, 1f)
                val rect = lerp(mini, open.translate(0f, offset * ride), t)
                return LandscapeDockPose(
                    left = rect.left - anchor.x,
                    top = rect.top - anchor.y - offset,
                    scale = rect.width / open.width,
                )
            }
            val landscapeArtShape: () -> Shape = {
                val t = dockT()
                if (dock == null || t >= 1f) {
                    dockFrame.cornerShape(LANDSCAPE_ART_CORNER)
                } else {
                    val scale = landscapeDockPose()?.scale?.takeIf { it > 0f } ?: 1f
                    dockFrame.cornerShape(lerp(dock.miniArtCorner(), LANDSCAPE_ART_CORNER, t) / scale)
                }
            }

            Box(modifier = Modifier.fillMaxSize().then(dockHost)) {
                LandscapePlayerLayout(
                    pane = when {
                        lyricsOpen -> PlayerPane.Lyrics
                        queueOpen -> PlayerPane.Queue
                        else -> PlayerPane.Main
                    },
                    background = { backgroundModifier ->
                        fullArtworkBlurContent(backgroundModifier.hazeSource(playerHaze))
                    },
                    artwork = { artworkModifier ->
                        LandscapeArtwork(
                            artRequest = art.request,
                            artLoaded = art.loaded,
                            onArtState = art::onState,
                            canvas = canvas,
                            canvasRendered = canvasRendered,
                            isPlaying = isPlaying,
                            onCanvasRenderedChange = { canvasRendered = it },
                            pausedForTransition = dockMoving || overlayOpen.value,
                            sleeveShape = landscapeArtShape,
                            shadowFraction = dockT,
                            modifier = artworkModifier
                                .then(
                                    if (dock != null) {
                                        Modifier.onGloballyPositioned { dockFrame.landscapeArt = it }
                                    } else {
                                        Modifier
                                    },
                                )
                                .drawWithContent {
                                    val pose = if (docking()) landscapeDockPose() else null
                                    if (pose != null) {
                                        dockFrame.portalLeft = pose.left
                                        dockFrame.portalTop = pose.top
                                        dockFrame.portalScale = pose.scale
                                        sleeveLayer.record { this@drawWithContent.drawContent() }
                                    } else {
                                        if (docking()) {
                                            dockFrame.portalScale = 0f
                                        }
                                        drawContent()
                                    }
                                }
                                .then(skipSwipeGesture)
                                .graphicsLayer {
                                    val t = dockT()
                                    val scale = if (t >= 1f) landscapeArtScale else 1f + (landscapeArtScale - 1f) * t
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = if (t >= 1f) swipeSettle.value else swipeSettle.value * t
                                }
                                .clickable(
                                    enabled = panelOpen,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    queueOpen = false
                                    closeLyrics()
                                },
                        ) {
                            SleeveNerdStats(
                                song = song,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .graphicsLayer { alpha = if (panelOpen) 0f else dockFade() },
                            )
                        }
                    },
                    actions = playerActions,
                    mainPane = { compact ->
                        LandscapeMainPane(
                            compact = compact,
                            caption = if (hideSongStatus) null else playbackOriginText(song, playedBy),
                            onOpenCaption = openPlaybackOrigin,
                            credits = {
                                LandscapeCredits(
                                    song = song,
                                    signedIn = signedIn,
                                    likeStatus = likeStatus,
                                    showRevertCue = showRevertCue,
                                    onToggleLike = onToggleLike,
                                    onOpenMenu = onOpenMenu,
                                    onOpenAlbum = onOpenAlbum,
                                    onOpenArtist = onOpenArtist,
                                )
                            },
                            lyricStrip = if (syncedLyricsEnabled) {
                                {
                                    CurrentLyricStrip(
                                        lines = lyricsTranslation.displayedLyrics,
                                        trackKey = song.videoId,
                                        playhead = lyricsPlayhead,
                                        isPlaying = isPlaying && position.advancing,
                                        durationMs = durationMs,
                                        lyricsUnavailable = lyricsUnavailable,
                                        loadingText = lyricsLoadingText,
                                        onClick = openLyrics,
                                    )
                                }
                            } else {
                                null
                            },
                            scrubber = {
                                PlayerScrubber(
                                    shown = shown,
                                    durationMs = durationMs,
                                    loading = versionAligning || audioVersionSwitching,
                                    mixing = mixing && !scrub.scrubbing,
                                    transitionWindow = transitionWindow
                                        ?.takeIf { !scrub.scrubbing && it.end > it.start }
                                        ?.let { it.start..it.end },
                                    onScrub = onScrub,
                                    onScrubFinished = onScrubFinished,
                                ) {
                                    PlaybackQualityLabel(
                                        song = song,
                                        isLoading = isLoading,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .padding(horizontal = 8.dp),
                                    )
                                }
                            },
                            transport = {
                                TransportRow(
                                    isPlaying = isPlaying,
                                    isLoading = isLoading || audioVersionSwitching,
                                    previousEnabled = !controlsLocked &&
                                        (hasPrevious || pastRestartPoint),
                                    nextEnabled = !controlsLocked && hasNext,
                                    onPrevious = onPrevious,
                                    onPlayPause = onPlayPause,
                                    onNext = onNext,
                                    compact = compact,
                                )
                            },
                            volume = if (hideVolumeBar) {
                                null
                            } else {
                                {
                                    VolumeRow(
                                        value = { volume.level.value },
                                        onValueChange = onVolumeChange,
                                        onValueChangeFinished = onVolumeChangeFinished,
                                    )
                                }
                            },
                        )
                    },
                    lyricsPane = {
                        LandscapeLyricsPane(
                            hasLyrics = lyricsTranslation.displayedLyrics.isNotEmpty(),
                            placeholder = if (lyricsUnavailable) {
                                stringResource(Res.string.lyrics_not_available)
                            } else {
                                lyricsLoadingText
                            },
                            status = lyricsTranslation.status,
                            onStatusClick = { showLyricsProviders = true },
                            picking = lyricPicker.picking,
                            pickBar = {
                                LyricsPickBar(
                                    shareEnabled = lyricPicker.picks.isNotEmpty() &&
                                        !lyricPicker.overBudget,
                                    onCancel = lyricPicker.cancel,
                                    onShare = lyricPicker.share,
                                )
                            },
                            romanizationToggle = {
                                RomanizationToggleButton(
                                    state = lyricsTranslation.romanizationState,
                                    showingRomanization = lyricsTranslation.showingRomanization,
                                    enabled = !lyrics.isNullOrEmpty(),
                                    onClick = lyricsTranslation.toggleRomanization,
                                )
                            },
                            translationToggle = {
                                TranslationToggleButton(
                                    state = lyricsTranslation.translationState,
                                    showingTranslation = lyricsTranslation.showingTranslation,
                                    enabled = !lyrics.isNullOrEmpty(),
                                    onClick = lyricsTranslation.toggleTranslation,
                                )
                            },
                        ) { panelModifier ->
                            LyricsTranslationMotion(
                                trigger = lyricsTranslation.transition,
                                reduceMotion = lyricsTranslation.reduceMotion,
                                modifier = panelModifier,
                            ) { particleProgress ->
                                LyricsPanel(
                                    lines = lyrics.orEmpty(),
                                    subLines = lyricsTranslation.subLines,
                                    trackKey = song.videoId,
                                    playhead = lyricsPlayhead,
                                    looking = !lyricsUnavailable,
                                    isPlaying = isPlaying && position.advancing,
                                    onSeekToLine = seekToLyric,
                                    controlsOpen = true,
                                    onRevealControls = {},
                                    onHideControls = {},
                                    translationProgress = particleProgress,
                                    canPick = lyricsShareEnabled,
                                    picking = lyricPicker.picking,
                                    picked = lyricPicker.picks,
                                    onPickLine = lyricPicker.pick,
                                    onTogglePick = lyricPicker.toggle,
                                    centerFocus = true,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    },
                    queuePane = {
                        InlineQueue(
                            queue = queue,
                            currentIndex = queueIndex,
                            autoplayEnabled = autoplayEnabled,
                            controlsLocked = controlsLocked,
                            onJumpTo = onJumpTo,
                            onRemove = onRemoveFromQueue,
                            onMove = onMoveInQueue,
                            onClear = onClearQueue,
                            onDragActiveChange = onQueueDragActiveChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { playerBounds = it }
                    .then(dockHost)
                    .background(Color.Black),
            ) {
                val legacyMeshBackdrop = !tabletArtworkBackdrop && !spotifyCanvasPresentation &&
                    playerTheme == PlayerTheme.LEGACY
                val artworkMeshBackdrop = !tabletArtworkBackdrop && playerTheme == PlayerTheme.DEFAULT
                val fullArtworkBackdropAlpha by animateFloatAsState(
                    targetValue = if (
                        !legacyMeshBackdrop &&
                        (tabletArtworkBackdrop || fullArtworkBlurImage != null) &&
                        (!spotifyCanvasPresentation || lyricsOpen || queueOpen) &&
                        (!artworkMeshBackdrop || lyricsOpen || queueOpen)
                    ) 1f else 0f,
                    animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                    label = "fullArtworkBackdropCrossfade",
                )
                if (legacyMeshBackdrop) {
                    LegacyMeshBackdrop(
                        imageUrl = remoteArt,
                        canvasFrame = { canvasFrame },
                        trackKey = song.videoId,
                        modifier = Modifier.graphicsLayer { alpha = 1f - fullArtworkBackdropAlpha },
                    )
                }
                if (artworkMeshBackdrop) {
                    PlayerMeshBackdrop(
                        trackKey = song.videoId,
                        imageUrl = remoteArt,
                        canvasFrame = { canvasFrame },
                        seamTarget = {
                            val stillBottom = stillBottomPx()
                            when {
                                clipPortrait -> clipBottom
                                stillBottom > 0f -> with(density) { stillBottom.toDp() }
                                heroMode -> heroHeight
                                else -> 0.dp
                            }
                        },
                        alpha = {
                            if (spotifyCanvasPresentation || fullArtworkBackdropAlpha >= 1f) 0f else 1f
                        },
                    )
                }
                fullArtworkBlurContent(
                    Modifier.graphicsLayer { alpha = fullArtworkBackdropAlpha },
                )

                if (heroMode && heroHeight > 0.dp) {
                    heroClipMounted?.let { clip ->
                        val stillStart = remember(clip.url) { stillAtMount() }
                        CanvasArtworkPlayer(
                            canvas = clip,
                            isPlaying = isPlaying,
                            pausedForTransition = lyricsOpen || queueOpen || queueDragging ||
                                collapseStarted || dockMoving || (overlayOpen.value && !clipFullscreen),
                            contentMode = if (clipFullscreen) {
                                CanvasContentMode.CROP
                            } else {
                                CanvasContentMode.FIT_PORTRAIT
                            },
                            alignPortraitTop = clipPortrait,
                            onAspectRatioChanged = { canvasAspect = it },
                            portraitRevealBounds = playerBounds,
                            presentationAlpha = {
                                if (stillTakesOver()) 0f
                                else (1f - 2f * p()).coerceIn(0f, 1f) * dockFade()
                            },
                            resumeDelayMs = if (clipFullscreen) SPOTIFY_CANVAS_RESUME_DELAY_MS else 0L,
                            startPositionMs = stillStart?.positionMs ?: 0L,
                            revealInstantly = stillStart != null,
                            captureStill = stillMoving && !clipFullscreen,
                            onStillCaptured = onStillCaptured,
                            onResumed = onClipResumed,
                            onRenderedChanged = { canvasRendered = it },
                            frameCapturePx = if (tintsOffClip && !clipFullscreen && !tabletArtworkBackdrop) {
                                CANVAS_FRAME_CAPTURE_PX
                            } else {
                                0
                            },
                            onFrameCaptured = {
                                if (!clipFullscreen && !tabletArtworkBackdrop &&
                                    !lyricsOpen && !queueOpen && !queueDragging
                                ) canvasFrame = it
                            },
                            refreshFrameEveryMs = if (
                                clipFullscreen || tabletArtworkBackdrop || lyricsOpen || queueOpen ||
                                queueDragging || dockMoving
                            ) {
                                null
                            } else {
                                meshRefreshMs
                            },
                            onCoverChanged = { canvasCover.floatValue = it },
                            bottomFade = when {
                                clipFullscreen -> 0f
                                clipPortrait -> clipFadeFraction
                                else -> HERO_FADE_FRACTION
                            },
                            bottomFadeEndPx = if (clipPortrait) with(density) { clipBottom.toPx() }
                                else null,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .then(
                                    if (clipFullscreen || clipPortrait) Modifier.fillMaxSize()
                                    else Modifier.fillMaxWidth().height(heroHeight),
                                )
                                .hazeSource(playerHaze),
                        )
                    }
                }

                val playerSubviewOpen = lyricsOpen || queueOpen || lyricsOffsetOpen ||
                    showAudioPipeline || showCast || showAudioOutput || showVideoQuality || showLyricsProviders ||
                    lyricsShare != null
                val topGradientAlpha = if (playerSubviewOpen) {
                    maxOf(artworkStatusScrimAlpha, SUBVIEW_STATUS_SCRIM_MIN_ALPHA)
                } else {
                    artworkStatusScrimAlpha
                }

                val steps = 8
                val gradientColors = remember(topGradientAlpha) {
                    List(steps) { index ->
                        val progress = index / (steps - 1).toFloat()
                        val factor = (1f - progress).toDouble().pow(1.5).toFloat()
                        Color.Black.copy(alpha = topGradientAlpha * factor)
                    }
                }
                val topScrimBrush = remember(gradientColors) {
                    Brush.verticalGradient(gradientColors)
                }

                if (artworkMeshBackdrop && clipPortrait && canvasAspect < PORTRAIT_SCRIM_MAX_ASPECT) {
                    Box(
                        Modifier.matchParentSize()
                            .graphicsLayer {
                                alpha = canvasCover.floatValue * (1f - 2f * p()).coerceIn(0f, 1f) * dockFade()
                            }
                            .background(PortraitClipScrim),
                    )
                }

                AnimatedVisibility(
                    visible = spotifyCanvasPresentation && spotifyCanvasControlsOpen,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.56f),
                    enter = fadeIn(tween(SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS)) + playerDeckSlideIn(),
                    exit = fadeOut(tween(SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS)) + playerDeckSlideOut(),
                ) {
                    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (reduceDynamicBlur || !renderEffectBlurSupported || overlayOpen.value) {
                                    Modifier.background(
                                        Brush.verticalGradient(
                                            0.00f to Color.Transparent,
                                            SPOTIFY_DECK_TOP_FADE_FRACTION to Color.Black.copy(alpha = 0.40f),
                                            1.00f to Color.Black.copy(alpha = 0.40f),
                                        ),
                                    )
                                } else {
                                    Modifier.optimizedHazeEffect(
                                        state = playerHaze,
                                        style = HazeStyle(
                                            backgroundColor = Color.Transparent,
                                            tints = emptyList(),
                                            blurRadius = 48.dp,
                                            noiseFactor = 0f,
                                            fallbackTint = HazeTint(Color.Transparent),
                                        ),
                                    ) {
                                        inputScale = HazeInputScale.Fixed(0.18f)
                                        canDrawArea = { true }
                                        mask = Brush.verticalGradient(
                                            0.00f to Color.Transparent,
                                            SPOTIFY_DECK_TOP_FADE_FRACTION to Color.Black,
                                            1.00f to Color.Black,
                                        )
                                    }
                                },
                            ),
                    )
                }

                val spotifyCanvasDim = animateFloatAsState(
                    targetValue = if (spotifyCanvasPresentation && spotifyCanvasControlsOpen) 0.5f else 0f,
                    animationSpec = tween(SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS),
                    label = "spotifyCanvasDim",
                )
                val spotifyCanvasDimShowing by remember { derivedStateOf { spotifyCanvasDim.value > 0f } }
                if (spotifyCanvasPresentation || spotifyCanvasDimShowing) {
                    Box(
                        Modifier.matchParentSize()
                            .graphicsLayer { alpha = spotifyCanvasDim.value }
                            .background(Color.Black),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(stableStatusBarInsets())
                        .navigationBarsPadding()
                        .toggleSpotifyCanvasControlsOnTap(
                            enabled = spotifyCanvasPresentation && !mixing,
                            onToggle = { spotifyCanvasControlsOpen = !spotifyCanvasControlsOpen },
                        )
                        .then(skipSwipeGesture),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .zIndex(1f)
                            .fillMaxWidth()
                            .height(topStrip)
                            .drawBehind {
                                val above = statusBarTop.toPx()
                                translate(top = -above) {
                                    drawRect(
                                        brush = topScrimBrush,
                                        size = Size(size.width, size.height + above),
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset {
                                    IntOffset(
                                        x = 0,
                                        y = lerp(
                                            6.dp,
                                            (topStrip - 5.dp).coerceAtLeast(0.dp) / 2,
                                            p(),
                                        ).roundToPx(),
                                    )
                                }
                                .width(38.dp)
                                .height(5.dp)
                                .graphicsLayer { alpha = spotifyChromeAlpha }
                                .shadow(2.dp, RoundedCornerShape(3.dp), clip = false)
                                .clip(RoundedCornerShape(3.dp))
                                .additiveChrome(LocalPlayerChrome.current)
                                .background(chromeWhite(0.70f)),
                        )
                        if (!hideSongStatus) {
                            WhileShown({ !collapseAlmostDone }) {
                                PlaybackOriginCaption(
                                    text = playbackOriginText(song, playedBy),
                                    onClick = openPlaybackOrigin,
                                    textAlign = TextAlign.Center,
                                    contentPadding = PaddingValues(start = PLAYER_GUTTER, end = PLAYER_GUTTER, bottom = 1.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .graphicsLayer { alpha = (1f - p()) * spotifyChromeAlpha },
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onGloballyPositioned { dismissBandSpace = it }
                            .pointerInput(showAudioPipeline, showCast, panelScrolling) {
                                if (showAudioPipeline || showCast || panelScrolling) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val space = dismissBandSpace
                                    val y = space?.localToRoot(down.position)?.y
                                        ?: down.position.y
                                    val panelUp = queueOpen || lyricsOpen ||
                                        queueSlide.floatValue > 0.01f
                                    val bandTop: Float
                                    val bandBottom: Float
                                    if (panelUp) {
                                        bandTop = space?.positionInRoot()?.y ?: 0f
                                        bandBottom = bandTop +
                                            (ART_BOX_TOP_PAD + HEADER_HEIGHT).toPx()
                                    } else {
                                        bandTop = dismissBandTop
                                        bandBottom = dismissBandBottom
                                    }
                                    if (y >= bandTop && y <= bandBottom) {
                                        if (!panelUp) {
                                            dragQueueIn(
                                                down = down,
                                                travel = bandBottom - bandTop -
                                                    HEADER_HEIGHT.toPx(),
                                                slide = queueSlide,
                                                onHold = { queueDragging = it },
                                                onSettle = { open ->
                                                    if (open != queueOpen) {
                                                        haptics.play(
                                                            if (open) Haptic.Expand else Haptic.Tap,
                                                        )
                                                        if (open) queueControlsOpen = true
                                                        queueOpen = open
                                                    }
                                                    queueReleased++
                                                },
                                            )
                                        }
                                        return@awaitEachGesture
                                    }
                                    if (down.isConsumed) return@awaitEachGesture
                                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                                        change.consume()
                                    }
                                    if (drag != null) verticalDrag(drag.id) { it.consume() }
                                }
                            }
                            .padding(horizontal = PLAYER_GUTTER),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                    val versionAligning by PlayerSettings.versionAlignmentInProgress.collectAsStateWithLifecycle()
                    val versionSwitching = versionAligning || audioVersionSwitching
                    var controlSpread by remember { mutableStateOf(lastControlSpread) }
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = PLAYER_MAX_WIDTH)
                            .fillMaxWidth()
                            .padding(top = ART_BOX_TOP_PAD, bottom = 18.dp),
                    ) {
                        if (spotifyCanvasPresentation && playerDeckSettledOpen &&
                            maxHeight != spotifyCanvasExpandedTopHeight
                        ) {
                            SideEffect { spotifyCanvasExpandedTopHeight = maxHeight }
                        }
                        val roomy = maxHeight + controlSpread
                        val wantArt = minOf(maxWidth, roomy - ART_TITLE_GAP - HEADER_HEIGHT)
                        val fullArt = minOf(wantArt, maxHeight - ART_TITLE_GAP - HEADER_HEIGHT)
                            .coerceAtLeast(THUMB_SIZE)
                        val slack = (roomy - wantArt - ART_TITLE_GAP - HEADER_HEIGHT)
                            .coerceAtLeast(0.dp)
                        val controlDeckIsMeasured = !spotifyCanvasPresentation || playerDeckSettledOpen
                        if (!lyricsOpen && !queueOpen && !queueDragging && collapseAtRest && controlDeckIsMeasured) {
                            val target = with(density) {
                                val half = slack
                                    .coerceAtMost(CONTROL_GAP_SPREAD_MAX * 2)
                                    .toPx()
                                    .div(2f)
                                    .roundToInt()
                                (half * 2).toDp()
                            }
                            val granted = with(density) {
                                val steppedPx = (controlSpread.toPx() +
                                    (target.toPx() - controlSpread.toPx()) * 0.4f)
                                    .roundToInt()
                                steppedPx.toDp()
                            }
                            if (granted != controlSpread) {
                                SideEffect {
                                    controlSpread = granted
                                    lastControlSpread = granted
                                }
                            }
                        }
                        val groupTop = (maxHeight - fullArt - ART_TITLE_GAP - HEADER_HEIGHT)
                            .coerceAtLeast(0.dp) / 2
                        fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p())
                        fun artTop(): Dp = lerp(groupTop, 0.dp, p())
                        fun artStart(): Dp = lerp((maxWidth - fullArt) / 2, 0.dp, p())
                        fun regularTitleTop(): Dp = lerp(groupTop + fullArt + ART_TITLE_GAP, 0.dp, p())
                        val spotifyTitleRange = if (spotifyCanvasPresentation) {
                            val expandedTopHeight = spotifyCanvasExpandedTopHeight
                                .takeIf { it > 0.dp }
                                ?: maxHeight
                            val collapsedTitleTop = (
                                expandedTopHeight + spotifyCanvasDeckHeight - HEADER_HEIGHT
                            ).coerceAtLeast(0.dp)
                            collapsedTitleTop
                        } else {
                            null
                        }
                        fun currentTitleTop(): Dp = spotifyTitleRange?.let { collapsedTitleTop ->
                            lerp(collapsedTitleTop, regularTitleTop(), playerDeckReveal.value)
                        } ?: regularTitleTop()
                        val thumbWidth = if (videoMode && !squareVideo) THUMB_SIZE * (16f / 9f) else THUMB_SIZE
                        fun titleStart(): Dp = lerp(0.dp, thumbWidth + 12.dp, p())

                        val bannerBottom = statusBarTop + topStrip + ART_BOX_TOP_PAD +
                            groupTop + fullArt + ART_TITLE_GAP / 2
                        val bannerSettled = !lyricsOpen || heroHeight == 0.dp
                        if (bannerSettled && bannerBottom != heroHeight) {
                            SideEffect { heroHeight = bannerBottom }
                        }

                        val bannerTopPx = with(density) {
                            -(statusBarTop.roundToPx() + topStrip.roundToPx() + ART_BOX_TOP_PAD.roundToPx())
                        }
                        val boxWidthPx = constraints.maxWidth
                        val videoBleedPx = if (videoMode && !squareVideo) with(density) { PLAYER_GUTTER.roundToPx() } else 0
                        val videoWidthPx = boxWidthPx + videoBleedPx * 2
                        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                        fun fromStart(start: Float, width: Float): Float =
                            if (rtl) boxWidthPx - start - width else start
                        fun bannerShare(): Float =
                            if (heroHeight > 0.dp && playerBounds.width > 0) heroT.value else 0f
                        fun bannerShown(): Float = bannerShare() * (1f - p())
                        fun cardScale(): Float = if (videoMode) 1f else artScale + (1f - artScale) * p()
                        fun cardRect(): Rect = with(density) {
                            val side = artSize().roundToPx().toFloat()
                            val square = Rect(
                                offset = Offset(
                                    fromStart(artStart().roundToPx().toFloat(), side),
                                    artTop().roundToPx().toFloat(),
                                ),
                                size = Size(side, side),
                            )
                            if (!videoMode || squareVideo) return@with square
                            val height = videoWidthPx / videoAspectState.value.coerceIn(1f, 2.4f)
                            val video = Rect(
                                offset = Offset(-videoBleedPx.toFloat(), square.center.y - height / 2f),
                                size = Size(videoWidthPx.toFloat(), height),
                            )
                            val thumbW = thumbWidth.roundToPx().toFloat()
                            val thumbH = THUMB_SIZE.roundToPx().toFloat()
                            val thumb = Rect(Offset(fromStart(0f, thumbW), 0f), Size(thumbW, thumbH))
                            lerp(video, thumb, p())
                        }
                        fun sleeveRect(): Rect {
                            val card = cardRect()
                            val share = bannerShare()
                            if (share == 0f) return card
                            val banner = with(density) {
                                Rect(
                                    offset = Offset(-(playerBounds.width - boxWidthPx) / 2f, bannerTopPx.toFloat()),
                                    size = Size(
                                        playerBounds.width.toFloat(),
                                        stillBottomPx().takeIf { it > 0f } ?: heroHeight.roundToPx().toFloat(),
                                    ),
                                )
                            }
                            val thumbSide = with(density) { THUMB_SIZE.roundToPx().toFloat() }
                            val thumb = Rect(Offset(fromStart(0f, thumbSide), 0f), Size(thumbSide, thumbSide))
                            return lerp(card, lerp(banner, thumb, p()), share)
                        }
                        val boxLeftPx = ((playerBounds.width - boxWidthPx) / 2f).roundToInt()
                        fun miniInBox(): Rect? {
                            if (dock == null || !docking()) return null
                            val mini = dock.miniArtOnScreen() ?: return null
                            val anchor = dockFrame.anchor ?: return null
                            val offset = dock.offset() ?: return null
                            dockFrame.offset = offset.toInt().toFloat()
                            return mini.translate(
                                -(anchor.x + boxLeftPx),
                                -(anchor.y + dockFrame.offset - bannerTopPx),
                            )
                        }
                        fun dockRide(sleeve: Rect, mini: Rect): Float {
                            val travel = dock?.sheetTravel ?: return 0f
                            if (travel <= 0f) return 0f
                            return ((mini.top + dockFrame.offset - sleeve.top) / travel).coerceIn(0f, 1f)
                        }
                        fun towardsDock(rect: Rect, mini: Rect?): Rect {
                            if (mini == null) return rect
                            val unridden = dockFrame.offset * (1f - dockFrame.ride)
                            return lerp(mini, rect.translate(0f, -unridden), dockT())
                        }
                        fun sleeveShown(): Float = bannerShown() * dockT()
                        fun sleeveCorner(): Dp {
                            val corner = 8.dp * (1f - sleeveShown())
                            val t = dockT()
                            return if (dock == null || t >= 1f) corner else lerp(dock.miniArtCorner(), corner, t)
                        }

                        Box(
                            modifier = Modifier
                                .layout { measurable, constraints ->
                                    val mini = miniInBox()
                                    val open = sleeveRect()
                                    if (mini != null) dockFrame.ride = dockRide(open, mini)
                                    val rect = towardsDock(open, mini)
                                    dockFrame.miniInBox = mini
                                    dockFrame.sleeve = rect
                                    val placeable = measurable.measure(
                                        Constraints.fixed(rect.width.roundToInt(), rect.height.roundToInt()),
                                    )
                                    layout(constraints.maxWidth, 0) {
                                        val x = rect.left.roundToInt()
                                        val y = rect.top.roundToInt()
                                        dockFrame.portalLeft = (boxLeftPx + x).toFloat()
                                        dockFrame.portalScale = 1f
                                        dockFrame.portalTop = (y - bannerTopPx).toFloat()
                                        placeable.place(x, y)
                                    }
                                }
                                .drawWithContent {
                                    if (docking()) {
                                        sleeveLayer.record { this@drawWithContent.drawContent() }
                                    } else {
                                        drawContent()
                                    }
                                }
                                .graphicsLayer {
                                    val share = bannerShare()
                                    val idle = cardScale()
                                    val t = dockT()
                                    val open = idle + (1f - idle) * share
                                    val scale = if (t >= 1f) open else 1f + (open - 1f) * t
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = swipeSettle.value * (1f - p()) * (1f - share) * t
                                }
                                .hazeSource(playerHaze)
                                .graphicsLayer {
                                    alpha = (
                                        if (stillTakesOver()) 1f
                                        else if (heroClip != null) 1f - canvasCover.floatValue
                                        else 1f
                                        ) *
                                        (if (!videoMode || docking()) 1f else (p() * 2f - 1f).coerceIn(0f, 1f))
                                }
                                .graphicsLayer {
                                    val shown = sleeveShown()
                                    shadowElevation = if (art.loaded) {
                                        10.dp.toPx() * (1f - 2f * shown).coerceAtLeast(0f) * dockT()
                                    } else {
                                        0f
                                    }
                                    shape = dockFrame.cornerShape(sleeveCorner())
                                    clip = art.loaded
                                }
                                .graphicsLayer {
                                    shape = dockFrame.cornerShape(sleeveCorner())
                                    clip = true
                                    compositingStrategy = if (sleeveShown() > 0f) {
                                        CompositingStrategy.Offscreen
                                    } else {
                                        CompositingStrategy.Auto
                                    }
                                }
                                .drawWithContent {
                                    drawContent()
                                    val shown = sleeveShown()
                                    if (shown > 0f) {
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colorStops = Array(FADE_CURVE_STOPS + 1) { i ->
                                                    val t = i / FADE_CURVE_STOPS.toFloat()
                                                    t to Color.Black.copy(alpha = 1f - shown * smootherStep(t))
                                                },
                                                startY = size.height * (1f - stillFadeFraction()),
                                                endY = size.height,
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                },
                        ) {
                            Box(
                                modifier = Modifier
                                    .layout { measurable, constraints ->
                                        val card = towardsDock(cardRect(), dockFrame.miniInBox)
                                        val sleeve = dockFrame.sleeve
                                        val placeable = measurable.measure(
                                            Constraints.fixed(card.width.roundToInt(), card.height.roundToInt()),
                                        )
                                        layout(constraints.maxWidth, 0) {
                                            placeable.place(
                                                (card.left - sleeve.left).roundToInt(),
                                                (card.top - sleeve.top).roundToInt(),
                                            )
                                        }
                                    }
                                    .graphicsLayer {
                                        val share = bannerShare()
                                        val idle = cardScale()
                                        val t = dockT()
                                        val scale = if (t >= 1f) {
                                            idle / (idle + (1f - idle) * share)
                                        } else {
                                            (1f + (idle - 1f) * t) / (1f + (idle + (1f - idle) * share - 1f) * t)
                                        }
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = swipeSettle.value * (1f - p()) * share * t
                                        shape = TileShape
                                        clip = share > 0f
                                    }
                                    .background(Color.Black.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!art.loaded && !canvasRendered) {
                                    Icon(
                                        imageVector = SfIcons.MusicNote,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier
                                            .size(40.dp)
                                            .graphicsLayer {
                                                val scale = 1f - 0.5f * p()
                                                scaleX = scale
                                                scaleY = scale
                                            },
                                    )
                                }
                            }
                            AsyncImage(
                                model = art.request,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onState = art::onState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .drawWithContent {
                                        val frame = still()
                                        if (frame == null) {
                                            if (art.loaded || !canvasRendered) drawContent()
                                        } else if (
                                            stillTakesOver() || !canvasRendered ||
                                            (heroMode && canvasCover.floatValue < 1f)
                                        ) {
                                            drawCropped(frame.image)
                                        }
                                    },
                            )

                            if (!heroMode && canvas != null) {
                                WhileShown({ !collapsePastHalf }) {
                                    val stillStart = remember(canvas.url) { stillAtMount() }
                                    CanvasArtworkPlayer(
                                        canvas = canvas,
                                        isPlaying = isPlaying,
                                        pausedForTransition = lyricsOpen || queueOpen || queueDragging ||
                                            collapseStarted || dockMoving || overlayOpen.value,
                                        presentationAlpha = { if (stillTakesOver()) 0f else 1f },
                                        startPositionMs = stillStart?.positionMs ?: 0L,
                                        revealInstantly = stillStart != null,
                                        captureStill = stillMoving,
                                        onStillCaptured = onStillCaptured,
                                        onResumed = onClipResumed,
                                        onRenderedChanged = { canvasRendered = it },
                                        frameCapturePx = if (tintsOffClip && !tabletArtworkBackdrop) {
                                            CANVAS_FRAME_CAPTURE_PX
                                        } else {
                                            0
                                        },
                                        onFrameCaptured = {
                                            if (!tabletArtworkBackdrop && !lyricsOpen && !queueOpen) {
                                                canvasFrame = it
                                            }
                                        },
                                        refreshFrameEveryMs = if (
                                            tabletArtworkBackdrop || lyricsOpen || queueOpen || dockMoving
                                        ) null else meshRefreshMs,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(artStart().roundToPx(), artTop().roundToPx()) }
                                .layout { measurable, constraints ->
                                    val side = artSize().roundToPx()
                                    val placeable = measurable.measure(
                                        constraints.constrain(Constraints.fixed(side, side)),
                                    )
                                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                                }
                                .onGloballyPositioned { dismissBandTop = it.boundsInRoot().top }
                                .graphicsLayer {
                                    val idle = cardScale()
                                    scaleX = idle
                                    scaleY = idle
                                    translationX = swipeSettle.value * (1f - p())
                                }
                                .then(
                                    if (queueOpen || lyricsOpen) {
                                        Modifier.clickable {
                                            queueOpen = false
                                            closeLyrics()
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            WhileShown({ !collapsePastHalf }) {
                                SleeveNerdStats(
                                    song = song,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                        .graphicsLayer {
                                            alpha = (1f - p() * 2f) * spotifyChromeAlpha
                                        },
                                )
                            }
                        }

                        if (videoMode) {
                            WhileShown({ !collapsePastHalf && !docking() }) {
                                val videoAspect by PlayerPlatform.host.musicVideoAspect
                                    .collectAsStateWithLifecycle()
                                val aspect = videoAspect.coerceIn(1f, 2.4f)
                                Box(
                                    modifier = Modifier
                                        .offset {
                                            if (squareVideo) {
                                                val side = artSize().roundToPx()
                                                return@offset IntOffset(
                                                    fromStart(artStart().roundToPx().toFloat(), side.toFloat()).roundToInt(),
                                                    artTop().roundToPx(),
                                                )
                                            }
                                            val height = (videoWidthPx / aspect).roundToInt()
                                            val centre = (artTop() + artSize() / 2).roundToPx()
                                            IntOffset(-videoBleedPx, centre - height / 2)
                                        }
                                        .layout { measurable, _ ->
                                            val width = if (squareVideo) artSize().roundToPx() else videoWidthPx
                                            val height = if (squareVideo) width else (videoWidthPx / aspect).roundToInt()
                                            val placeable = measurable.measure(Constraints.fixed(width, height))
                                            layout(if (squareVideo) width else boxWidthPx, height) { placeable.placeRelative(0, 0) }
                                        }
                                        .graphicsLayer {
                                            alpha = (1f - p() * 2f).coerceIn(0f, 1f)
                                            translationX = swipeSettle.value * (1f - p())
                                        },
                                ) {
                                    if (!videoRendered) {
                                        VideoLoadingBackdrop(
                                            Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(10.dp)),
                                        )
                                    }
                                    MusicVideoSurface(
                                        videoId = song.videoId,
                                        fit = !squareVideo,
                                        onRenderedChanged = { videoRendered = it },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp)),
                                    )
                                    VideoCornerControls(
                                        fullscreen = false,
                                        onToggleFullscreen = {
                                            fullscreenRequested = true
                                            screenOrientation.landscape()
                                        },
                                        onOpenQuality = { showVideoQuality = true },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                    )
                                }
                            }
                        }

                        if (swipeHintShown) {
                            val showNext = swipeHintNext
                            val enabled = if (showNext) hasNext else hasPrevious
                            val hintAlpha = if (enabled) 0.85f else 0.3f
                            Icon(
                                imageVector = if (showNext) SfIcons.FastForward else SfIcons.FastRewind,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset {
                                        IntOffset(0, (artTop() + artSize() + (ART_TITLE_GAP - 16.dp) / 2).roundToPx())
                                    }
                                    .size(16.dp)
                                    .graphicsLayer {
                                        alpha = (abs(swipeSettle.value) / swipeThreshold)
                                            .coerceIn(0f, 1f) * (1f - p()) * hintAlpha
                                    },
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset {
                                    IntOffset(
                                        x = 0,
                                        y = (
                                            currentTitleTop() -
                                                lerp(0.dp, (HEADER_HEIGHT - THUMB_SIZE) / 2, p())
                                        ).roundToPx(),
                                    )
                                }
                                .layout { measurable, constraints ->
                                    val start = titleStart().roundToPx()
                                    val placeable = measurable.measure(constraints.offset(horizontal = -start))
                                    layout(
                                        constraints.constrainWidth(placeable.width + start),
                                        constraints.constrainHeight(placeable.height),
                                    ) { placeable.placeRelative(start, 0) }
                                }
                                .height(HEADER_HEIGHT)
                                .onGloballyPositioned { dismissBandBottom = it.boundsInRoot().bottom },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        val scale = 1f - 0.2f * p()
                                        scaleX = scale
                                        scaleY = scale
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    },
                            ) {
                                var titleOverflowing by remember { mutableStateOf(false) }
                                val scrolls = {
                                    !(lyricsOpen || queueOpen || queueDragging) && !collapsePastSettling
                                }
                                Crossfade(
                                    targetState = song.title to song.artist,
                                    modifier = Modifier.additiveChrome(LocalPlayerChrome.current),
                                    animationSpec = tween(durationMillis = 300),
                                    label = "playerCredits",
                                ) {
                                    val scrolling = scrolls()
                                    val artistColor = chromeWhite(0.55f)
                                    Column {
                                        MarqueeText(
                                            text = song.title,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontSize = 20.sp,
                                            ),
                                            color = Color.White,
                                            enabled = scrolling,
                                            leading = if (song.isExplicit == true) {
                                                { ExplicitBadge(color = artistColor) }
                                            } else {
                                                null
                                            },
                                            onOverflowChange = { titleOverflowing = it },
                                            modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
                                        )
                                        ArtistCreditsMarquee(
                                            text = song.artist,
                                            credits = remember(song.artist, song.artists, song.artistId) {
                                                artistCredits(song.artist, song.artists, song.artistId)
                                            },
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.W500,
                                                fontSize = 20.sp,
                                            ),
                                            color = artistColor,
                                            enabled = scrolling,
                                            startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
                                            onOpenArtist = onOpenArtist,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            if (signedIn || song.localUri != null) {
                                val liked = likeStatus == LikeStatus.LIKE
                                CircleGlyph(
                                    icon = if (liked) SfIcons.StarFill else SfIcons.Star,
                                    contentDescription = stringResource(
                                        if (liked) Res.string.remove_from_liked else Res.string.like,
                                    ),
                                    onClick = onToggleLike,
                                    active = liked,
                                    haptic = if (liked) Haptic.ToggleOff else Haptic.ToggleOn,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            val lyricsShareGlyphVisible = lyricsOpen && collapseDone && lyricsShareEnabled &&
                                !lyrics.isNullOrEmpty() && (!lyricsControlsOpen || lyricPicker.picking)
                            CircleGlyph(
                                icon = when {
                                    showRevertCue -> SfIcons.Undo
                                    lyricsShareGlyphVisible && lyricPicker.picking -> SfIcons.Close
                                    lyricsShareGlyphVisible -> SfIcons.IosShare
                                    else -> SfIcons.MoreHoriz
                                },
                                contentDescription = stringResource(
                                    when {
                                        showRevertCue -> Res.string.more
                                        lyricsShareGlyphVisible && lyricPicker.picking -> Res.string.cancel
                                        lyricsShareGlyphVisible -> Res.string.share
                                        else -> Res.string.more
                                    },
                                ),
                                onClick = when {
                                    showRevertCue -> onOpenMenu
                                    lyricsShareGlyphVisible && lyricPicker.picking -> lyricPicker.cancel
                                    lyricsShareGlyphVisible -> lyricPicker.beginPicking
                                    else -> onOpenMenu
                                },
                                haptic = if (lyricsShareGlyphVisible && !showRevertCue) {
                                    Haptic.Select
                                } else {
                                    Haptic.Tap
                                },
                                modifier = Modifier.menuAnchor(),
                            )
                        }

                        val lyricsPanelVisible = lyricsOpen && collapseDone
                        if (lyricsPanelVisible || playerPrewarmStage >= 2) {
                                LyricsTranslationMotion(
                                    trigger = lyricsTranslation.transition,
                                    reduceMotion = lyricsTranslation.reduceMotion,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = HEADER_HEIGHT)
                                        .offset {
                                            if (lyricsPanelVisible) IntOffset.Zero else IntOffset(100_000, 0)
                                        }
                                        .graphicsLayer { alpha = if (lyricsPanelVisible) panelFade.value else 0f },
                                ) { particleProgress ->
                                    LyricsPanel(
                                        lines = lyrics.orEmpty(),
                                        subLines = lyricsTranslation.subLines,
                                        trackKey = song.videoId,
                                        playhead = lyricsPlayhead,
                                        looking = !lyricsUnavailable,
                                        isPlaying = isPlaying && position.advancing,
                                        active = lyricsPanelVisible,
                                        onSeekToLine = seekToLyric,
                                        controlsOpen = lyricsControlsOpen,
                                        onRevealControls = { lyricsControlsOpen = true },
                                        onHideControls = { lyricsControlsOpen = false },
                                        translationProgress = particleProgress,
                                        onScrollingChange = { lyricsScrolling = it },
                                        canPick = lyricsShareEnabled,
                                        picking = lyricPicker.picking,
                                        picked = lyricPicker.picks,
                                        onPickLine = lyricPicker.pick,
                                        onTogglePick = lyricPicker.toggle,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

                            val translateShown = lyricsPanelVisible && lyricsControlsOpen
                            val translateFade by animateFloatAsState(
                                targetValue = if (translateShown) 1f else 0f,
                                animationSpec = tween(if (translateShown) 220 else 160),
                                label = "translateFade",
                            )
                            if (translateFade > 0.01f && !lyricPicker.picking) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .graphicsLayer { alpha = translateFade },
                                ) {
                                    RomanizationToggleButton(
                                        state = lyricsTranslation.romanizationState,
                                        showingRomanization = lyricsTranslation.showingRomanization,
                                        enabled = translateShown && !lyrics.isNullOrEmpty(),
                                        onClick = lyricsTranslation.toggleRomanization,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .graphicsLayer { alpha = translateFade },
                                ) {
                                    TranslationToggleButton(
                                        state = lyricsTranslation.translationState,
                                        showingTranslation = lyricsTranslation.showingTranslation,
                                        enabled = translateShown && !lyrics.isNullOrEmpty(),
                                        onClick = lyricsTranslation.toggleTranslation,
                                    )
                                }
                            }
                            if (lyricPicker.picking) {
                                LyricsPickBar(
                                    shareEnabled = lyricPicker.picks.isNotEmpty() &&
                                        !lyricPicker.overBudget,
                                    onCancel = lyricPicker.cancel,
                                    onShare = lyricPicker.share,
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }
                        }

                        val queuePanelVisible = !lyricsOpen &&
                            (queueDragging || (queueOpen && queueShowing && collapseDone))
                        if (queuePanelVisible || playerPrewarmStage >= 3) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = HEADER_HEIGHT)
                                    .offset {
                                        if (queuePanelVisible) IntOffset.Zero else IntOffset(100_000, 0)
                                    }
                                    .graphicsLayer {
                                        alpha = if (!queuePanelVisible) {
                                            0f
                                        } else if (queueDragging) {
                                            ((queueSlide.floatValue - 0.45f) / 0.55f).coerceIn(0f, 1f)
                                        } else {
                                            panelFade.value
                                        }
                                        translationY = if (queuePanelVisible) {
                                            (1f - queueSlide.floatValue) * 26.dp.toPx()
                                        } else {
                                            0f
                                        }
                                    },
                            ) {
                                InlineQueue(
                                    queue = queue,
                                    currentIndex = queueIndex,
                                    autoplayEnabled = autoplayEnabled,
                                    controlsLocked = controlsLocked,
                                    onJumpTo = onJumpTo,
                                    onRemove = onRemoveFromQueue,
                                    onMove = onMoveInQueue,
                                    onClear = onClearQueue,
                                    onScrollingChange = { queueScrolling = it },
                                    onDragActiveChange = onQueueDragActiveChange,
                                    collapsePlayerOnScroll = true,
                                    onRevealPlayer = { queueControlsOpen = true },
                                    onHidePlayer = { queueControlsOpen = false },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    SlidingPlayerDeck(
                        visible = !lyricPicker.picking &&
                            (!lyricsOpen || lyricsControlsOpen) &&
                            (!queueOpen || queueControlsOpen) &&
                            (!spotifyCanvasPresentation || spotifyCanvasControlsOpen || mixing),
                        reveal = playerDeckReveal,
                        fadeIn = { !lyricsOpen && !queueOpen && p() > 0f },
                    ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.onSizeChanged { size ->
                            if (spotifyCanvasPresentation) {
                                val measuredHeight = with(density) { size.height.toDp() }
                                if (measuredHeight != spotifyCanvasDeckHeight) {
                                    spotifyCanvasDeckHeight = measuredHeight
                                }
                            }
                        },
                    ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = PLAYER_MAX_WIDTH)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(lyricSlotHeight),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                    if (!lyricsOpen && syncedLyricsEnabled) {
                        CurrentLyricStrip(
                            lines = lyricsTranslation.displayedLyrics,
                            trackKey = song.videoId,
                            playhead = lyricsPlayhead,
                            isPlaying = isPlaying && position.advancing,
                            durationMs = durationMs,
                            lyricsUnavailable = lyricsUnavailable,
                            loadingText = lyricsLoadingText,
                            onClick = openLyrics,
                        )
                    }
                    if (!lyricsOpen && !syncedLyricsEnabled) {
                        Text(
                            text = "\u00A0",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = 6.dp)
                                .padding(vertical = 4.dp),
                        )
                    }
                    if (lyricsOpen) {
                        LyricsStatusWithChange(
                            status = lyricsTranslation.status,
                            onStatusClick = { showLyricsProviders = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = 6.dp)
                                .padding(vertical = 4.dp),
                        )
                    }
                    }
                    val transitionWindow by PlayerSettings.smartTransitionWindow.collectAsStateWithLifecycle()
                    PlayerScrubber(
                        shown = shown,
                        durationMs = durationMs,
                        loading = versionSwitching,
                        mixing = mixing && !scrub.scrubbing,
                        transitionWindow = transitionWindow
                            ?.takeIf { !scrub.scrubbing && it.end > it.start }
                            ?.let { it.start..it.end },
                        onScrub = onScrub,
                        onScrubFinished = onScrubFinished,
                    ) {
                        PlaybackQualityLabel(
                            song = song,
                            isLoading = isLoading,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 8.dp),
                        )
                    }

                    val transport: @Composable () -> Unit = {
                        TransportRow(
                            isPlaying = isPlaying,
                            isLoading = isLoading || audioVersionSwitching,
                            previousEnabled = !controlsLocked &&
                                (hasPrevious || pastRestartPoint),
                            nextEnabled = !controlsLocked && hasNext,
                            onPrevious = onPrevious,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                        )
                    }
                    if (!hideVolumeBar) {
                        Spacer(Modifier.height(8.dp + controlSpread / 2))
                        transport()
                        Spacer(Modifier.height(12.dp + controlSpread / 2))
                        VolumeRow(
                            value = { volume.level.value },
                            onValueChange = onVolumeChange,
                            onValueChangeFinished = onVolumeChangeFinished,
                        )
                        Spacer(Modifier.height(6.dp))
                    } else {
                        val run = 8.dp + 12.dp + 6.dp + controlSpread + VOLUME_ROW_HEIGHT
                        val lift = SCRUBBER_LABEL_LIFT / 2
                        Spacer(Modifier.height(run / 2 - lift))
                        transport()
                        Spacer(Modifier.height(run / 2 + lift))
                    }

                    playerActions()
                    Spacer(Modifier.height(18.dp))
                    }
                    }
                    }
                    }
                }
            }
        }
    }
}
