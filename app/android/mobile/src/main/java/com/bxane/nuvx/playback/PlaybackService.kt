package com.bxane.nuvx.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.future
import com.bxane.nuvx.playback.audio.DspChain
import com.bxane.nuvx.playback.cast.CastController
import com.bxane.nuvx.playback.cast.CastPlayback
import com.bxane.nuvx.playback.cast.RemoteSpeaker
import com.bxane.nuvx.playback.cast.Speakers
import com.bxane.nuvx.playback.cast.CastStream
import com.bxane.nuvx.playback.audio.PrecisionAudioSink
import com.bxane.nuvx.playback.dsd.DsdExtractorsFactory
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectAudioSink
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectOutput
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectRoutingSink
import com.bxane.nuvx.playback.audio.bitperfect.DacVolume
import com.bxane.nuvx.playback.audio.bitperfect.usb.UsbDirectDevices
import com.bxane.nuvx.MainActivity
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.model.QueueTier
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.download.Downloads
import java.util.concurrent.ConcurrentHashMap
import com.bxane.nuvx.data.Http
import com.bxane.nuvx.auth.SignedIn
import com.bxane.nuvx.data.LikeState
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.innertube.PlaybackTracker
import com.bxane.nuvx.data.stats.ListeningRecorder
import com.bxane.nuvx.data.innertube.PlayerClient
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.isUnresolvedSpotify
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.settings.BitPerfectMode
import com.bxane.nuvx.data.settings.permits
import com.bxane.nuvx.data.settings.EqualizerMode
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.TrackMatcher
import com.bxane.nuvx.playback.smart.AutomixAnalysisSource
import com.bxane.nuvx.playback.smart.VersionAudioAligner
import com.bxane.nuvx.wear.WearBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlin.random.Random
import java.util.UUID
import com.bxane.nuvx.data.model.isLiveRadio


const val ACTION_TOGGLE_AUTOPLAY = "com.bxane.nuvx.action.TOGGLE_AUTOPLAY"

const val ACTION_START_BOUNDED_CONTEXT = "com.bxane.nuvx.action.START_BOUNDED_CONTEXT"
const val ACTION_CLEAR_BOUNDED_CONTEXT = "com.bxane.nuvx.action.CLEAR_BOUNDED_CONTEXT"
const val EXTRA_BOUNDED_CONTEXT_SONGS = "nuvx.boundedContext.songs"

const val ACTION_SWAP_VERSION = "com.bxane.nuvx.action.SWAP_VERSION"
const val EXTRA_SWAP_MEDIA_ITEM = "nuvx.swap.media_item"

private const val WATCH_SESSION_FOREGROUND_MS = 6 * 60 * 60 * 1000L

const val ACTION_TOGGLE_FAVORITE = "com.bxane.nuvx.action.TOGGLE_FAVORITE"

const val ACTION_TOGGLE_SHUFFLE = "com.bxane.nuvx.action.TOGGLE_SHUFFLE"

const val ACTION_START_STATION = "com.bxane.nuvx.action.START_STATION"
const val ACTION_REVERT_TO_ORIGINAL = "com.bxane.nuvx.action.REVERT_TO_ORIGINAL"
const val ACTION_SWAP_TO_VERSION = "com.bxane.nuvx.action.SWAP_TO_VERSION"

const val ACTION_BEGIN_RADIO_QUEUE = "com.bxane.nuvx.action.BEGIN_RADIO_QUEUE"
const val ACTION_COMMIT_RADIO_QUEUE = "com.bxane.nuvx.action.COMMIT_RADIO_QUEUE"

const val ACTION_UPGRADE_QUALITY = "com.bxane.nuvx.action.UPGRADE_QUALITY"

const val ACTION_QUEUE_DRAG = "com.bxane.nuvx.action.QUEUE_DRAG"
const val EXTRA_QUEUE_DRAG_ACTIVE = "nuvx.queueDrag.active"

private const val INITIAL_STATION_TRACKS = 24

private fun Song.canStartStation(): Boolean =
    videoId.isNotBlank() &&
        !videoId.startsWith("content://") &&
        !videoId.startsWith("file://")

internal const val RECOVERY_DELAY_MS = 350L

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val songCache = ConcurrentHashMap<String, Song>()

    private var player: ExoPlayer? = null

    private var spare: ExoPlayer? = null

    private var crossfade: CrossfadeController? = null

    private val spatialAudioProcessorA = SpatialAudioProcessor()
    private val spatialAudioProcessorB = SpatialAudioProcessor()
    private val equalizerProcessorA = EqualizerProcessor()
    private val equalizerProcessorB = EqualizerProcessor()
    private val transitionFilterA = TransitionFilterProcessor()
    private val transitionFilterB = TransitionFilterProcessor()

    private val loudnessA = LoudnessProcessor().apply { gainFor = ::loudnessGainFor }
    private val loudnessB = LoudnessProcessor().apply { gainFor = ::loudnessGainFor }
    private var loudnessRetryJob: Job? = null

    private val audioEffectSession = PlaybackAudioEffectSessionController(this)

    private var activeTrackIsDolbyAtmos = false

    private var activeFilter: TransitionFilterProcessor = transitionFilterA
    private var spareFilter: TransitionFilterProcessor = transitionFilterB

    private fun activeLoudness(): LoudnessProcessor =
        if (activeFilter === transitionFilterA) loudnessA else loudnessB

    private fun spareLoudness(): LoudnessProcessor =
        if (activeFilter === transitionFilterA) loudnessB else loudnessA

    private val trackAnalyzer = com.bxane.nuvx.playback.smart.TrackAnalyzer(this, AudioCache)

    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    private var lastPositionSeconds = 0L

    private var trackSelectedAt: Long? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val versionAlignment = PlaybackVersionAlignmentController(this, scope)

    private val versionSwapController = PlaybackVersionSwapController(
        scope = scope,
        player = { player },
        spare = { spare },
        crossfade = { crossfade },
        versionAlignment = versionAlignment,
        loudnessA = loudnessA,
        loudnessB = loudnessB,
        spareLoudness = ::spareLoudness,
        nextMediaIdOf = ::nextMediaIdOf,
        onSwapping = { swappingMediaId = it },
        adoptPlayerForVersionSwap = ::adoptPlayerForVersionSwap,
        onTrackBecameCurrent = ::onTrackBecameCurrent,
        refreshCustomLayouts = ::refreshCustomLayouts,
    )

    private val listenBrainz = PlaybackListenBrainzController(scope) { player }
    private val scrobbling = PlaybackScrobblingController(scope) { player }

    private val discordPresence = PlaybackDiscordPresenceController(
        context = this,
        scope = scope,
        player = { player },
        teardownTimeoutMs = DISCORD_TEARDOWN_TIMEOUT_MS,
    )

    private val autoBrowse = AutoBrowseCatalog(
        scope = scope,
        cacheSong = { songCache[it.videoId] = it },
    )

    private val autoMediaItemResolver = AutoMediaItemResolver(
        context = this,
        autoBrowse = autoBrowse,
        songCache = songCache,
    )

    private val autoBrowseMediaLibraryCallbacks = AutoBrowseMediaLibraryCallbacks(
        context = this,
        scope = scope,
        autoBrowse = autoBrowse,
        autoMediaItemResolver = autoMediaItemResolver,
        player = { player },
        songCache = songCache,
    )

    private val boundedQueue: BoundedQueueController = BoundedQueueController(
        scope = scope,
        player = { player },
        isTransitioning = { crossfade?.isTransitioning() == true },
        isCasting = { CastController.state.value.casting || castPlayback.active },
        refreshLayouts = { refreshCustomLayouts() },
        onContextStarted = {
            autoplay.clearRepeatAllStash()
        },
        onRestoring = { persistedQueueStart = 0 },
    )

    private val autoplay: PlaybackAutoplayController = PlaybackAutoplayController(
        scope = scope,
        player = { player },
        boundedQueueActive = { boundedQueue.active },
        autoplayEligible = { exoPlayer -> boundedQueue.autoplayEligible(exoPlayer) },
    )

    private val streamResolution: StreamResolutionController = StreamResolutionController(
        context = this,
        scope = scope,
        player = { player },
        crossfade = { crossfade },
        audioFormatFor = { formatListener.audioFormatFor },
        buildAuditionPlayer = ::buildAuditionPlayer,
        onSwapping = { swappingMediaId = it },
        onSwapCut = { formatListener.markSwapCut() },
    )

    private val musicOnlyController = PlaybackMusicOnlyController(
        scope = scope,
        player = { player },
        isTransitioning = { crossfade?.isTransitioning() == true },
        isVersionSwapActive = versionSwapController::isActive,
        prefetchAround = ::prefetchAround,
        smoothSwapCurrentTrackVersion = { versionSwapController.smoothSwapCurrentTrackVersion(it) },
    )

    private val telemetry = PlaybackTelemetryController(
        context = this,
        scope = scope,
        player = { player },
        resolveLocalUri = streamResolution::resolveLocalUri,
    )

    private val errorRecovery = PlaybackErrorRecovery(
        scope = scope,
        player = { player },
        onSwapping = { swappingMediaId = it },
    )

    private val directSourceWindow = PlaybackDirectSourceWindowController(
        scope = scope,
        player = { player },
        spare = { spare },
        crossfade = { crossfade },
        streamResolution = streamResolution,
        errorRecovery = errorRecovery,
    )

    private val outputRouter: PlaybackOutputRouter = PlaybackOutputRouter(
        context = this,
        scope = scope,
        player = { player },
        spare = { spare },
        isTransitioning = { crossfade?.isTransitioning() == true },
        detachPlayer = { oldActive ->
            crossfade?.release()
            oldActive.removeListener(playbackListener)
            oldActive.removeAnalyticsListener(formatListener)
        },
        buildAndSetPlayers = ::buildAndSetOutputPlayers,
        applySettings = ::applySettings,
        applyProcessors = {
            applyEqualizer()
            applySpatialAudioEnabled()
        },
        setupLoudness = ::setupLoudness,
        attachPlayer = { newActive ->
            newActive.addListener(playbackListener)
            newActive.addAnalyticsListener(formatListener)

            val newCrossfade = createCrossfadeController()
            crossfade = newCrossfade
            newCrossfade.start()
            mediaSession?.player = newSessionPlayer(newActive, newCrossfade)
        },
    )

    private var streamResolver: ResolvingDataSource.Resolver? = null

    private val castPlayback = CastPlayback(
        scope = scope,
        localPlayer = { player },
        resolve = ::resolveForCast,
        say = { message -> Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() },
    )

    private val watchSpeaker by lazy {
        WearBridge.createSpeaker(this, scope, localPlayer = { player }, onChanged = { publishWidgetState() })
    }

    private val speakers by lazy { Speakers(listOf(castPlayback, watchSpeaker)) }

    private var partySync: PartySync? = null

    private val boundedContextCommand = SessionCommand(ACTION_START_BOUNDED_CONTEXT, Bundle.EMPTY)
    private val clearBoundedContextCommand = SessionCommand(ACTION_CLEAR_BOUNDED_CONTEXT, Bundle.EMPTY)
    private val favoriteCommand = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    private val autoplayCommand = SessionCommand(ACTION_TOGGLE_AUTOPLAY, Bundle.EMPTY)
    private val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private val startStationCommand = SessionCommand(ACTION_START_STATION, Bundle.EMPTY)
    private val revertToOriginalCommand = SessionCommand(ACTION_REVERT_TO_ORIGINAL, Bundle.EMPTY)
    private val swapToVersionCommand = SessionCommand(ACTION_SWAP_TO_VERSION, Bundle.EMPTY)
    private val beginRadioQueueCommand = SessionCommand(ACTION_BEGIN_RADIO_QUEUE, Bundle.EMPTY)
    private val commitRadioQueueCommand = SessionCommand(ACTION_COMMIT_RADIO_QUEUE, Bundle.EMPTY)
    private val upgradeQualityCommand = SessionCommand(ACTION_UPGRADE_QUALITY, Bundle.EMPTY)
    private val swapVersionCommand = SessionCommand(ACTION_SWAP_VERSION, Bundle.EMPTY)
    private val queueDragCommand = SessionCommand(ACTION_QUEUE_DRAG, Bundle.EMPTY)

    private val customLayouts = PlaybackCustomLayoutController(
        context = this,
        player = { player },
        mediaSession = { mediaSession },
        isLiked = ::isLiked,
        canLike = ::canLike,
        canStartStation = { it.canStartStation() },
        favoriteCommand = favoriteCommand,
        shuffleCommand = shuffleCommand,
        startStationCommand = startStationCommand,
        revertToOriginalCommand = revertToOriginalCommand,
    )

    private var favoriteActionJob: Job? = null
    private var stationActionJob: Job? = null
    private var preferredPrefetchRequest: List<String>? = null

    private var persistedQueueStart = 0

    private val queuePersistence: PlaybackQueuePersistenceController = PlaybackQueuePersistenceController(
        boundedQueue = boundedQueue,
        queueStart = { persistedQueueStart },
        setQueueStart = { persistedQueueStart = it },
    )

    private val widgetPublisher = PlaybackWidgetPublisher(
        context = this,
        player = { player },
        reportedPlayer = { mediaSession?.player },
        canLike = ::canLike,
    )

    private val lyricsController = PlaybackLyricsController(
        context = { this@PlaybackService },
        scope = scope,
        player = { player },
        crossfade = { crossfade },
        loopMixLyricsTimeoutMs = LOOP_MIX_LYRICS_TIMEOUT_MS,
    )
    private val playbackListener = PlaybackPlayerListener(
        context = this,
        player = { player },
        audioEffectSession = audioEffectSession,
        trackSelectedAt = { trackSelectedAt },
        clearTrackSelectedAt = { trackSelectedAt = null },
        lastPositionSeconds = { lastPositionSeconds },
        clearLastPositionSeconds = { lastPositionSeconds = 0 },
        errorRecovery = errorRecovery,
        registerCurrentPlay = ::registerCurrentPlay,
        prefetchAround = ::prefetchAround,
        cancelPrefetch = ::cancelPrefetch,
        lookForBetterCopy = streamResolution::lookForBetterCopy,
        savePlaybackState = ::savePlaybackState,
        publishWidgetState = ::publishWidgetState,
        scrobbling = scrobbling,
        listenBrainz = listenBrainz,
        discordPresence = discordPresence,
        partySync = { partySync },
        speakers = { speakers },
        directSourceWindow = directSourceWindow,
        swappingMediaId = { swappingMediaId },
        clearSwappingMediaId = { swappingMediaId = null },
        onTrackBecameCurrent = ::onTrackBecameCurrent,
        autoplay = autoplay,
        loadLyricsForCurrentTrack = ::loadLyricsForCurrentTrack,
        startLyricsTicker = ::startLyricsTicker,
        stopLyricsTicker = ::stopLyricsTicker,
        updateLyricSubtitle = ::updateLyricSubtitle,
        refreshCustomLayouts = ::refreshCustomLayouts,
        boundedQueue = boundedQueue,
        activeLoudness = ::activeLoudness,
        nextMediaIdOf = ::nextMediaIdOf,
        musicOnlyController = musicOnlyController,
        saveQueueSnapshot = ::saveQueueSnapshot,
    )

    private val formatListener = PlaybackAudioAnalyticsListener(
        onDolbyAtmosChanged = { activeTrackIsDolbyAtmos = it },
        applySpatialAudioEnabled = ::applySpatialAudioEnabled,
        publishNerdStats = telemetry::publish,
        outputRouter = outputRouter,
    )

    override fun onCreate() {
        super.onCreate()

        if (com.bxane.nuvx.data.innertube.Innertube.cookie == null) {
            com.bxane.nuvx.data.innertube.Innertube.cookie = com.bxane.nuvx.auth.AuthStore(this).cookie
        }

        telemetry.forgetLastSession()
        QualityUpgrade.forgetLastSession()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_logo) },
        )

        scope.launch {
            QueueShuffle.enabled
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }
        scope.launch {
            WearBridge.watchSession.collect { onWatch ->
                setForegroundServiceTimeoutMs(
                    if (onWatch) WATCH_SESSION_FOREGROUND_MS else DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS,
                )
            }
        }
        scope.launch {
            SignedIn.state(this@PlaybackService)
                .drop(1)
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }
        scope.launch {
            LikeState.overrides
                .map { it[player?.currentMediaItem?.mediaId] }
                .distinctUntilChanged()
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }
        scope.launch {
            OfflinePlaylists.liked
                .map { liked -> player?.currentMediaItem?.toSong()?.localUri?.let { it in liked } }
                .distinctUntilChanged()
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }

        outputRouter.startBluetoothTracking()
        scope.launch {
            var previous = AppSettings.effectiveAudioQuality
            combine(
                AppSettings.audioQualityWifi,
                AppSettings.audioQualityCellular,
                AppSettings.meteredConnection,
                AppSettings.streamCeilingOverride,
            ) { _, _, _, _ -> AppSettings.effectiveAudioQuality }
                .distinctUntilChanged()
                .collect { ceiling ->
                    val lost = SourceKind.entries.filter { previous.permits(it) && !ceiling.permits(it) }
                    previous = ceiling
                    if (lost.isNotEmpty()) onCeilingLowered(ceiling, lost)
                }
        }

        val streamResolver = streamResolution.createResolver()
        this.streamResolver = streamResolver

        val resolvingFactory = ResolvingDataSource.Factory(
            ChunkedDataSource.Factory(OkHttpDataSource.Factory(Http.client), STREAM_CHUNK_BYTES),
        ) { dataSpec ->
            streamResolver.resolveDataSpec(dataSpec).also { resolved ->
                mediaIdIn(dataSpec.uri)?.let { StreamContainer.served(it, resolved.uri.toString()) }
                AudioCache.recordServed(dataSpec, resolved.uri.toString())
            }
        }
        val defaultDataSourceFactory = SmbDataSource.RoutingFactory(
            DefaultDataSource.Factory(this, resolvingFactory),
        )
        AudioCache.setUpstream(defaultDataSourceFactory)
        mediaSourceFactory = DefaultMediaSourceFactory(
            MusicOnlyPlayback.holdingFactory(AudioCache.playbackFactory(defaultDataSourceFactory)),
            DsdExtractorsFactory(),
        )
            .setLoadErrorHandlingPolicy(PermanentAwareLoadErrorPolicy())
        WearBridge.attachStreams(AudioCache.playbackFactory(defaultDataSourceFactory))

        outputRouter.configureInitialOutput()
        val exoPlayer = buildPlayer(
            spatialAudioProcessorA,
            equalizerProcessorA,
            transitionFilterA,
            loudnessA,
            ownsSession = true,
        )
        val sparePlayer = buildPlayer(
            spatialAudioProcessorB,
            equalizerProcessorB,
            transitionFilterB,
            loudnessB,
            ownsSession = false,
        )
        player = exoPlayer
        spare = sparePlayer
        sparePlayer.audioSessionId = exoPlayer.audioSessionId
        outputRouter.registerDeviceCallback()
        outputRouter.applyOutputRoute()

        AppSettings.audioSessionId.value = exoPlayer.audioSessionId
        applySettings(exoPlayer)
        applySettings(sparePlayer)
        observeSettings()
        scrobbling.observe()
        observeDiscord()
        watchSleepTimer()
        val restored = if (PartyPersonalQueueStash.hasStash()) {
            val stashed = PartyPersonalQueueStash.load()
            PartyPersonalQueueStash.clear()
            if (stashed != null && stashed.songs.isNotEmpty()) {
                persistedQueueStart = 0
                exoPlayer.setMediaItems(stashed.songs.map { it.toMediaItem() }, stashed.index, stashed.positionMs)
                true
            } else {
                restoreLastQueue(exoPlayer)
            }
        } else {
            restoreBoundedQueueOrLast(exoPlayer)
        }
        if (restored) {
            publishWidgetState()
        } else {
            widgetPublisher.clear()
        }
        playbackListener.setLastRepeatMode(exoPlayer.repeatMode)
        exoPlayer.addListener(playbackListener)
        partySync = PartySync(scope) { player }.also { it.start() }
        scope.launch {
            ListenTogether.state
                .map { state -> Triple(state.code, state.playback.autoplayEnabled, autoplaySupplierId(state)) }
                .distinctUntilChanged()
                .collectLatest { (code, enabled, supplierId) ->
                    if (code != null && enabled && supplierId == ListenTogether.state.value.you?.memberId) {
                        autoplay.resetAndLoadForCurrentTrack()
                    }
                }
        }
        scope.launch {
            var wasInParty = ListenTogether.state.value.inParty
            ListenTogether.state
                .map { it.inParty }
                .distinctUntilChanged()
                .collect { inParty ->
                    if (wasInParty && !inParty) boundedQueue.rebuildAfterParty()
                    wasInParty = inParty
                }
        }
        autoplay.loadForCurrentTrack()

        exoPlayer.addAnalyticsListener(formatListener)

        reportProgress()
        directSourceWindow.watch()

        val controller = createCrossfadeController()
        crossfade = controller
        QueueShuffle.isCrossfadeTransitioning = { crossfade?.isTransitioning() == true }
        controller.start()

        mediaSession = MediaLibrarySession.Builder(
            this,
            newSessionPlayer(exoPlayer, controller),
            MediaLibraryCallback(),
        )
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            .build()
        refreshCustomLayouts()

        CastController.ensureStarted(this)
        CastController.attach(castPlayback)
    }

    private fun newSessionPlayer(player: Player, crossfade: CrossfadeController) = SessionPlayer(
        player,
        crossfade,
        onUserIntent = { partySync?.onLocalIntent() },
        deferPlayToParty = { partySync?.shouldDeferPlay() == true },
        lockedTransport = { playing -> partySync?.onLockedTransport(playing) == true },
        cast = speakers,
    ) { lyricsController.subtitle }

    private suspend fun resolveForCast(item: MediaItem): CastStream? = withContext(Dispatchers.IO) {
        val uri = item.localConfiguration?.uri ?: return@withContext null
        val resolver = streamResolver ?: return@withContext null
        val resolved = DirectSourceGate.ungated { resolver.resolveDataSpec(DataSpec(uri)) }.uri
        if (resolved.scheme == "http" || resolved.scheme == "https") {
            CastStream(resolved.toString(), CastPlayback.mimeTypeOf(resolved))
        } else {
            val fallback = if (uri.scheme == "content" || uri.scheme == "file") {
                item.mediaId.takeIf { it.isNotBlank() }?.let { vid ->
                    if (Downloads.saved.value.containsKey(vid)) "nuvx://watch?v=$vid" else null
                }
            } else null
            if (fallback != null) {
                runCatching {
                    DirectSourceGate.ungated { resolver.resolveDataSpec(DataSpec(fallback.toUri())) }.uri
                        .takeIf { it.scheme == "http" || it.scheme == "https" }
                }.getOrNull()?.let { url ->
                    CastStream(url.toString(), CastPlayback.mimeTypeOf(url))
                }
            } else null
        }
    }

    private fun createCrossfadeController() = CrossfadeController(
            scope,
            active = { requireNotNull(player) },
            standby = { requireNotNull(spare) },
            onHandoff = ::adoptPlayer,
            analysisFor = { item -> trackAnalyzer.analysisFor(item.mediaId) },
            requestAnalysis = { item, durationMs ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.request(item.mediaId, uri, durationMs / 1000.0)
                }
            },
            filters = object : TransitionFilters {
                override fun incoming(lowPassHz: Float, highPassHz: Float) =
                    activeFilter.setCutoffs(lowPassHz, highPassHz)

                override fun outgoing(lowPassHz: Float, highPassHz: Float) =
                    spareFilter.setCutoffs(lowPassHz, highPassHz)

                override fun incomingLeadMs(): Long = activeFilter.leadUs / 1000

                override fun outgoingLeadMs(): Long = spareFilter.leadUs / 1000

                override fun incomingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    activeFilter.setEcho(delaySeconds, send, dry)

                override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    spareFilter.setEcho(delaySeconds, send, dry)

                override fun parkEchoes() {
                    transitionFilterA.parkEcho()
                    transitionFilterB.parkEcho()
                }
            },
            analysisRunningFor = { item -> trackAnalyzer.isAnalysing(item.mediaId) },
            onArmIncoming = { item, nextId -> spareLoudness().track(item.mediaId, nextId) },
            onBlendHeadroom = { trim ->
                loudnessA.setBlendTrim(trim)
                loudnessB.setBlendTrim(trim)
            },
            onSettled = {
                if (boundedQueue.hasPendingEdits) {
                    scope.launch {
                        boundedQueue.applyDeferredEdit()
                    }
                }
            },
            versionSwapActive = versionSwapController::isActive,
            nextStreamReady = { item ->
                !MusicOnlyPlayback.wants(item) &&
                    (!DirectSourceGate.isActive() || DirectSourceGate.isStreamReady(item))
            },
            mixWaveform = { item, durationMs, from, to, bins ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.mixWaveform(item.mediaId, uri, durationMs / 1000.0, from, to, bins)
                }
            },
            loopPortOf = { target ->
                when {
                    target === player -> activeFilter.loop
                    target === spare -> spareFilter.loop
                    else -> null
                }
            },
            loopRegion = { item, durationMs, from, to, rate ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.loopRegion(item.mediaId, uri, durationMs / 1000.0, from, to, rate)
                }
            },
            syncedLines = ::loopMixLyrics,
            loudnessGain = ::loudnessGainFor,
            onMixPositionJump = { wrapped -> (mediaSession?.player as? SessionPlayer)?.announceMixPosition(wrapped) },
        )

    private suspend fun loopMixLyrics(item: MediaItem, durationMs: Long): List<LyricLine>? =
        lyricsController.loopMixLyrics(item, durationMs)

    private fun canLike(item: MediaItem?): Boolean =
        item != null && (item.toSong().localUri != null || SignedIn.state(this).value)

    private fun isLiked(item: MediaItem?): Boolean {
        val song = item?.toSong() ?: return false
        return if (song.localUri != null) {
            OfflinePlaylists.isLiked(song)
        } else {
            LikeState.overrides.value[song.videoId] == LikeStatus.LIKE
        }
    }

    private fun refreshCustomLayouts() = customLayouts.refreshCustomLayouts()

    private fun startStationFromSession() {
        val originalPlayer = player ?: return
        val currentItem = originalPlayer.currentMediaItem ?: return
        val seed = currentItem.toSong().takeIf { it.canStartStation() } ?: return
        val originalManualQueue = (0 until originalPlayer.mediaItemCount)
            .map { originalPlayer.getMediaItemAt(it) }
            .filterNot { it.fromAutoplay }
            .map { it.mediaId }

        stationActionJob?.cancel()
        stationActionJob = scope.launch {
            val radioSeed = seed.copy(radioName = seed.title)
            val related = loadAutoplayTracks(
                existing = listOf(radioSeed),
                seedSong = radioSeed,
                limit = INITIAL_STATION_TRACKS,
            ).getOrElse {
                TrackLog.w(
                    "nuvx",
                    "Android Auto station failed: ${it.message}",
                    about = seed.videoId,
                )
                return@launch
            }
            if (related.isEmpty()) return@launch

            val activePlayer = player ?: return@launch
            val activeManualQueue = (0 until activePlayer.mediaItemCount)
                .map { activePlayer.getMediaItemAt(it) }
                .filterNot { it.fromAutoplay }
                .map { it.mediaId }
            if (activePlayer.currentMediaItem?.mediaId != currentItem.mediaId ||
                activeManualQueue != originalManualQueue
            ) {
                return@launch
            }

            beginRadioQueue()
            val currentIndex = activePlayer.currentMediaItemIndex
            activePlayer.addMediaItems(currentIndex + 1, related.map { it.toMediaItem() })
            val oldTail = currentIndex + 1 + related.size
            if (oldTail < activePlayer.mediaItemCount) {
                activePlayer.removeMediaItems(oldTail, activePlayer.mediaItemCount)
            }
            if (currentIndex > 0) activePlayer.removeMediaItems(0, currentIndex)
            saveQueueSnapshotImmediately(activePlayer)
        }
    }

    private fun smoothSwapCurrentTrackVersion(
        targetMediaItem: MediaItem,
        onSwapCommitted: (() -> Unit)? = null,
    ) {
        versionSwapController.smoothSwapCurrentTrackVersion(targetMediaItem, onSwapCommitted)
    }
    private fun adoptFormatOf(incoming: ExoPlayer) {
        if (incoming.audioFormat == null) return
        formatListener.audioFormatFor = incoming.currentMediaItem?.mediaId
    }

    private fun adoptPlayerForVersionSwap(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)

        outgoing.removeListener(playbackListener)
        outgoing.removeAnalyticsListener(formatListener)

        player = incoming
        spare = outgoing

        val heldFilter = activeFilter
        activeFilter = spareFilter
        spareFilter = heldFilter

        incoming.addListener(playbackListener)
        incoming.addAnalyticsListener(formatListener)
        adoptFormatOf(incoming)

        mediaSession?.player = newSessionPlayer(incoming, requireNotNull(crossfade))

        incoming.volume = 1f
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f

        swappingMediaId = null
        refreshCustomLayouts()

        onTrackBecameCurrent(
            incoming.currentMediaItem,
            previousEnded = false,
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
            alreadyAudible = true,
        )
    }

    private fun revertCurrentToOriginal() = versionSwapController.revertCurrentToOriginal()

    private fun swapCurrentToVersion(args: Bundle) = versionSwapController.swapCurrentToVersion(args)
    private fun toggleShuffleFromSession() {
        if (boundedQueue.requestShuffleToggle()) return
        if (ListenTogether.state.value.inParty) QueueShuffle.clearCanonicalContext()
        player?.let(QueueShuffle::toggle)
        refreshCustomLayouts()
    }

    private fun toggleAutoplayFromNotification() {
        val party = ListenTogether.state.value
        val enabled = if (party.inParty) !party.playback.autoplayEnabled else !AppSettings.autoplay.value
        AppSettings.setAutoplay(enabled)
        if (party.inParty) {
            ListenTogether.setAutoplay(enabled)
            refreshCustomLayouts()
            return
        }
        if (enabled) {
            autoplay.resetAndLoadForCurrentTrack()
        } else {
            autoplay.disableAutoplay()
        }
        refreshCustomLayouts()
    }

    private fun toggleFavoriteFromNotification(videoId: String) {
        favoriteActionJob?.cancel()
        val previous = LikeState.overrides.value[videoId] ?: LikeStatus.INDIFFERENT
        val target = if (previous == LikeStatus.LIKE) {
            LikeStatus.INDIFFERENT
        } else {
            LikeStatus.LIKE
        }

        LikeState.set(videoId, target)
        refreshCustomLayouts()
        favoriteActionJob = scope.launch {
            YtMusicRepository.rate(videoId, target)
                .onFailure {
                    LikeState.set(videoId, previous)
                    refreshCustomLayouts()
                    TrackLog.w("nuvx", "notification favorite failed: ${it.message}", about = videoId)
                }
        }
    }

    private fun buildPlayer(
        spatial: SpatialAudioProcessor,
        equalizer: EqualizerProcessor,
        filter: TransitionFilterProcessor,
        loudness: LoudnessProcessor,
        ownsSession: Boolean,
    ): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(silenceSkippingRenderers(spatial, equalizer, filter, loudness))
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setLoadControl(farBufferingLoadControl())
        .setAudioAttributes(AUDIO_ATTRIBUTES, ownsSession)
        .setHandleAudioBecomingNoisy(ownsSession)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setMaxSeekToPreviousPositionMs(BACK_RESTARTS_AFTER_MS)
        .build()

    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)

        outgoing.removeListener(playbackListener)
        outgoing.removeAnalyticsListener(formatListener)
        player = incoming
        spare = outgoing
        val heldFilter = activeFilter
        activeFilter = spareFilter
        spareFilter = heldFilter
        incoming.addListener(playbackListener)
        incoming.addAnalyticsListener(formatListener)
        adoptFormatOf(incoming)

        mediaSession?.player = newSessionPlayer(incoming, requireNotNull(crossfade))

        onTrackBecameCurrent(
            incoming.currentMediaItem,
            previousEnded = true,
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            alreadyAudible = true,
        )

        autoplay.resetAndLoadForCurrentTrack()
    }

    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun registerCurrentPlay() {
        player?.currentMediaItem?.mediaId?.let(PlaybackTracker::onPlaying)
    }

    private fun onTrackBecameCurrent(
        mediaItem: MediaItem?,
        previousEnded: Boolean,
        reason: Int,
        alreadyAudible: Boolean = false,
    ) {
        val exoPlayer = player ?: return
        boundedQueue.syncCursor(mediaItem)
        if (boundedQueue.active && !ListenTogether.state.value.inParty) {
            boundedQueue.saveSnapshot(exoPlayer)
        }
        outputRouter.currentAudioInputFormat = null
        NerdStats.onTrackTransition(mediaItem?.mediaId)
        telemetry.onTrackTransition()
        AudioOutputStatus.onTrackTransition()
        mediaItem?.let { AudioCache.notePlayed(it.toSong(), it.localConfiguration?.uri) }
        scheduleForeignCachePurge()
        mediaItem?.mediaId?.takeUnless(::isLiveRadio)?.let { id ->
            telemetry.resolveLocalBitrate(id, mediaItem)
        }

        activeTrackIsDolbyAtmos = NerdStats.declaredFormat(mediaItem?.mediaId)?.isDolbyAtmos == true
        applySpatialAudioEnabled()

        setupLoudness(mediaItem?.mediaId)
        scheduleLoudnessRetry(mediaItem?.mediaId)

        val expiredHistory = queueHistoryTrimCount(exoPlayer.currentMediaItemIndex)
        if (expiredHistory > 0) {
            if (boundedQueue.active) {
                exoPlayer.removeMediaItems(0, expiredHistory)
            } else if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
                exoPlayer.rotateExpiredHistory(expiredHistory)
            } else {
                exoPlayer.removeMediaItems(0, expiredHistory)
            }
        }
        boundedQueue.scheduleTopUp()
        errorRecovery.onTrackChanged(mediaItem?.mediaId)

        trackSelectedAt = if (alreadyAudible) null else SystemClock.elapsedRealtime()
        if (alreadyAudible) {
            TrackLog.d(
                "nuvx",
                "TIMING first audio: 0ms, the crossfade covered it",
                about = mediaItem?.mediaId,
            )
            errorRecovery.onAudible(mediaItem?.mediaId)
        }
        mediaItem?.mediaId?.let(TrackLog::onTrackStarted)
        mediaItem?.mediaId?.let {
            QualityUpgrade.onPlaybackStarted(
                it,
                mediaItem.localConfiguration?.uri?.let(QualityUpgrade::cacheTag),
            )
        }
        TrackLog.d(
            "nuvx",
            "TIMING track selected: ${mediaItem?.mediaId} (reason=$reason)",
            about = mediaItem?.mediaId,
        )

        PlaybackTracker.onTrackChanged(lastPositionSeconds)
        lastPositionSeconds = 0

        scrobbling.onSongStop()
        ListeningRecorder.onStopped()
        val newSong = mediaItem?.toSong()
        val durationMs = exoPlayer.duration.takeIf { it > 0 }
        if (exoPlayer.isPlaying) {
            scrobbling.onSongStart(newSong, durationMs)
        }

        val ended = previousEnded
        listenBrainz.onTrackChanged(newSong, exoPlayer.isPlaying, durationMs, ended)

        NerdStats.current.value = null

        if (exoPlayer.isPlaying) pushDiscordPresence(exoPlayer)

        if (ended && SleepTimer.afterTrack.value) {
            exoPlayer.pause()
            SleepTimer.cancel()
        }
        if (exoPlayer.isPlaying) registerCurrentPlay()
        savePlaybackState(exoPlayer)
        musicOnlyController.convertVideosAround(exoPlayer)
        prefetchAround(exoPlayer)
        streamResolution.cancelStaleUpgradeJob()
        streamResolution.lookForBetterCopy(exoPlayer)
        publishWidgetState()
        loadLyricsForCurrentTrack()
        if (exoPlayer.isPlaying) startLyricsTicker()
    }

    private var swappingMediaId: String? = null

    private fun buildAuditionPlayer(meter: BandwidthMeter): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(FfmpegFallbackRenderersFactory(this))
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setBandwidthMeter(meter)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    AUDITION_BUFFER_MS,
                    AUDITION_BUFFER_MS,
                    START_PLAYBACK_MS,
                    START_PLAYBACK_MS,
                )
                .setTargetBufferBytes(AUDITION_BUFFER_BYTES)
                .build(),
        )
        .build()
        .apply {
            playWhenReady = false
            volume = 0f
        }

    private fun publishNerdStats() = telemetry.publish()

    private fun saveQueueSnapshot(player: ExoPlayer) {
        queuePersistence.saveQueueSnapshot(player)
    }

    private fun saveQueueSnapshotImmediately(player: ExoPlayer) {
        queuePersistence.saveQueueSnapshotImmediately(player)
    }

    private fun beginRadioQueue() {
        crossfade?.onSkipRequested()
        boundedQueue.clear()
        autoplay.clearRadioQueueSession()
        persistedQueueStart = 0
        LastPlayed.clearImmediately()
    }

    private fun savePlaybackState(player: ExoPlayer) {
        queuePersistence.savePlaybackState(player)
    }

    private fun restoreLastQueue(player: ExoPlayer): Boolean {
        return queuePersistence.restoreLastQueue(player)
    }

    private fun restoreBoundedQueueOrLast(player: ExoPlayer): Boolean {
        return queuePersistence.restoreBoundedQueueOrLast(player)
    }

    private fun publishWidgetState(playing: Boolean? = null) {
        widgetPublisher.publish(playing)
    }

    private var foreignCachePurgeJob: Job? = null

    private fun scheduleForeignCachePurge() {
        foreignCachePurgeJob?.cancel()
        foreignCachePurgeJob = scope.launch {
            delay(FOREIGN_CACHE_PURGE_DELAY_MS)
            val exoPlayer = player ?: return@launch
            val keep = buildSet {
                exoPlayer.currentMediaItem?.mediaId?.let(::add)
                val current = exoPlayer.currentMediaItemIndex
                if (current != C.INDEX_UNSET) {
                    val window = (current - 1)..(current + FOREIGN_CACHE_KEEP_AHEAD)
                    for (index in window) {
                        if (index in 0 until exoPlayer.mediaItemCount) add(exoPlayer.getMediaItemAt(index).mediaId)
                    }
                }
                spare?.currentMediaItem?.mediaId?.let(::add)
            }
            AudioCache.dropForeignEntries(keep)
        }
    }

    private fun onCeilingLowered(ceiling: AudioQuality, lost: List<SourceKind>) {
        val exoPlayer = player ?: return
        val keep = buildSet {
            exoPlayer.currentMediaItem?.mediaId?.let(::add)
            spare?.currentMediaItem?.mediaId?.let(::add)
            val duration = exoPlayer.duration
            val next = exoPlayer.nextMediaItemIndex
            if (duration != C.TIME_UNSET && next != C.INDEX_UNSET &&
                exoPlayer.totalBufferedDuration >= duration - exoPlayer.currentPosition
            ) {
                add(exoPlayer.getMediaItemAt(next).mediaId)
            }
        }
        TrackLog.d(
            "nuvx",
            "quality ceiling now ${ceiling.name}; letting go of ${lost.joinToString { it.label }} " +
                "except for ${keep.joinToString()}",
        )
        val permitted = { stream: SourceStream -> SourceRegistry.permitsForPlayback(stream.sourceConfigId) }
        streamResolution.cancelStaleUpgradeJob()
        cancelPrefetch()
        StreamChoice.forgetWhere(keep) { !permitted(it) }
        QualityUpgrade.onCeilingLowered(keep, permitted)
        AudioCache.dropForeignEntries(keep, keepAnalysis = true)
        prefetchAround(exoPlayer)
        streamResolution.lookForBetterCopy(exoPlayer)
    }

    private fun prefetchAround(player: ExoPlayer) {
        if (DirectSourceGate.isActive()) {
            directSourceWindow.drive()
            if (preferredPrefetchRequest != null) cancelPrefetch()
            return
        }
        val nextIndex = player.nextMediaItemIndex
        val upcomingSongs = if (nextIndex != C.INDEX_UNSET) {
            val end = (nextIndex + AudioCache.QUEUE_DEPTH - 1).coerceAtMost(player.mediaItemCount - 1)
            (nextIndex..end).map(player::getMediaItemAt)
                .filterNot(MusicOnlyPlayback::wants)
                .map { it.toSong() }
        } else {
            emptyList()
        }
        AudioCache.noteSongs(upcomingSongs)
        val request = upcomingSongs.map { it.videoId }
        if (request == preferredPrefetchRequest) return
        preferredPrefetchRequest = request

        fun warm(songs: List<Song>) {
            AudioCache.prefetchQueue(
                songs.map { song ->
                    AudioCache.Upcoming(
                        mediaId = song.videoId,
                        target = TrackMatcher.targetOf(song),
                    )
                },
            )
        }

        warm(upcomingSongs)
    }

    private fun cancelPrefetch() {
        preferredPrefetchRequest = null
        AudioCache.cancel()
    }

    private fun reportProgress() {
        scope.launch {
            while (isActive) {
                val player = this@PlaybackService.player
                if (player != null && player.isPlaying) {
                    lastPositionSeconds = player.currentPosition / 1000
                    player.currentMediaItem?.mediaId?.let {
                        PlaybackTracker.onProgress(it, lastPositionSeconds)
                    }
                    player.currentMediaItem?.toSong()?.let {
                        ListeningRecorder.onSample(it, player.duration)
                    }
                    savePlaybackState(player)
                    publishNerdStats()
                    streamResolution.lookForBetterCopy(player)
                }
                delay(PROGRESS_SAMPLE_MS)
            }
        }
    }

    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                player?.pause()
                SleepTimer.cancel()
            }
        }
    }

    private fun farBufferingLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            FAR_BUFFER_MS,
            START_PLAYBACK_MS,
            RESUME_PLAYBACK_MS,
        )
        .setTargetBufferBytes(FAR_BUFFER_BYTES)
        .setBackBuffer(BACK_BUFFER_MS, true)
        .build()

    private fun silenceSkippingRenderers(
        spatial: SpatialAudioProcessor,
        equalizer: EqualizerProcessor,
        transition: TransitionFilterProcessor,
        loudness: LoudnessProcessor,
    ) = object : FfmpegFallbackRenderersFactory(this) {
        init {
            setEnableAudioFloatOutput(outputRouter.configuredFloatOutput)
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val selector = if (mimeType == MimeTypes.AUDIO_FLAC) {
                    MediaCodecSelector.PREFER_SOFTWARE
                } else {
                    MediaCodecSelector.DEFAULT
                }
                val candidates = selector.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                if (mimeType == MimeTypes.AUDIO_FLAC) {
                    candidates.filterNot { AudioOutputPolicy.isUnsafeFloatFlacDecoder(it.name) }
                } else {
                    candidates
                }
            }
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val defaultSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(
                    enableAudioTrackPlaybackParams || enableFloatOutput,
                )
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray<AudioProcessor>(),
                        SilenceSkippingAudioProcessor(
                            MIN_SILENCE_US,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                        ),
                        SonicAudioProcessor(),
                    ),
                )
                .build()

            val dspChain = DspChain(spatial, equalizer, transition, loudness)
            val precision = PrecisionAudioSink(
                delegate = defaultSink,
                dspChain = dspChain,
                enableFloatOutput = enableFloatOutput,
                preferredOutputEncodingProvider = { format -> outputRouter.resolvePreferredOutputEncoding(format) },
                isAudible = { transition === activeFilter },
            )
            return BitPerfectRoutingSink(precision, BitPerfectAudioSink(BitPerfectOutput.forContext(context)))
        }
    }

    private fun applySettings(player: ExoPlayer) {
        player.skipSilenceEnabled = AppSettings.skipSilence.value
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        QueueShuffle.setEnabled(AppSettings.shuffleEnabled.value)
        player.repeatMode = AppSettings.repeatMode.value
    }

    private fun buildAndSetOutputPlayers(): Pair<ExoPlayer, ExoPlayer> {
        activeFilter = transitionFilterA
        spareFilter = transitionFilterB
        val newActive = buildPlayer(
            spatialAudioProcessorA,
            equalizerProcessorA,
            transitionFilterA,
            loudnessA,
            ownsSession = true,
        )
        val newSpare = buildPlayer(
            spatialAudioProcessorB,
            equalizerProcessorB,
            transitionFilterB,
            loudnessB,
            ownsSession = false,
        )
        player = newActive
        spare = newSpare
        return newActive to newSpare
    }

    private inline fun eachPlayer(body: (ExoPlayer) -> Unit) {
        player?.let(body)
        spare?.let(body)
    }

    private fun observeSettings() {
        scope.launch {
            AppSettings.skipSilence.collect { on -> eachPlayer { it.skipSilenceEnabled = on } }
        }
        scope.launch {
            AppSettings.preferMusicOnly.drop(1).collect {
                MusicOnlyPlayback.reset()
                cancelPrefetch()
                player?.let { live ->
                    musicOnlyController.convertVideosAround(live)
                    if (live.isPlaying) prefetchAround(live)
                }
            }
        }
        scope.launch {
            AppSettings.preferUsbDac.drop(1).collect { outputRouter.requestOutputReconfiguration() }
        }
        scope.launch {
            AudioRouting.selectedId.drop(1).collect { outputRouter.requestOutputReconfiguration() }
        }
        scope.launch {
            AppSettings.outputPcmMode.drop(1).collect { outputRouter.requestOutputReconfiguration() }
        }
        UsbDirectDevices.init(this)
        BitPerfectOutput.watch(this)
        scope.launch {
            var available = false
            DacVolume.level.collect { level ->
                val now = level != null
                (mediaSession?.player as? SessionPlayer)?.onDacVolumeChanged(routeChanged = now != available)
                available = now
            }
        }
        UsbDirectDevices.onChanged = {
            BitPerfectOutput.refreshReady(this)
            scope.launch {
                if (AppSettings.bitPerfectMode.value == BitPerfectMode.USB_DIRECT) {
                    UsbDirectDevices.requestPermission(this@PlaybackService)
                    outputRouter.requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            AppSettings.bitPerfectMode.collect { mode ->
                BitPerfectOutput.refreshReady(this@PlaybackService)
                if (mode == BitPerfectMode.USB_DIRECT) UsbDirectDevices.requestPermission(this@PlaybackService)
            }
        }
        scope.launch {
            AppSettings.bitPerfectMode.drop(1).collect { outputRouter.requestOutputReconfiguration(rebuild = true) }
        }
        scope.launch {
            AppSettings.usbAllowFixedVolume.drop(1).collect {
                if (AppSettings.bitPerfectMode.value == BitPerfectMode.USB_DIRECT) {
                    outputRouter.requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            AppSettings.convertForLimitedDac.drop(1).collect {
                if (AppSettings.bitPerfectMode.value != BitPerfectMode.OFF) {
                    outputRouter.requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            AppSettings.playbackSpeed.collect { speed ->
                if (crossfade?.isTransitioning() == true) return@collect
                crossfade?.applyPlaybackSpeed(speed) ?: eachPlayer { it.setPlaybackSpeed(speed) }
            }
        }
        scope.launch {
            combine(
                AppSettings.syncedLyrics,
                AppSettings.lyricsSources,
                AppSettings.lyricsSourceOrder,
                AppSettings.prioritizeSyllableSync,
            ) { synced, sources, order, prio ->
                synced to sources
            }.distinctUntilChanged().collect {
                loadLyricsForCurrentTrack()
            }
        }
        scope.launch {
            AppSettings.spatialAudio.collect { applySpatialAudioEnabled() }
        }
        scope.launch {
            AppSettings.loudnessNormalization.collect {
                setupLoudness(player?.currentMediaItem?.mediaId)
            }
        }
        scope.launch {
            combine(
                AppSettings.loudnessOffOnSpeaker,
                AudioOutputStatus.current.map { it.routeKind == AudioRouting.Kind.PHONE }.distinctUntilChanged(),
            ) { off, onSpeaker -> off && onSpeaker }
                .distinctUntilChanged()
                .drop(1)
                .collect { setupLoudness(player?.currentMediaItem?.mediaId) }
        }
        scope.launch {
            combine<Any, Unit>(
                AppSettings.equalizerEnabled,
                AppSettings.equalizerMode,
                AppSettings.equalizerToneX,
                AppSettings.equalizerToneY,
                AppSettings.equalizerFocused,
                AppSettings.equalizerBalance,
                AppSettings.equalizerBands,
            ) { }.collect { applyEqualizer() }
        }
    }

    private fun applyEqualizer() {
        val enabled = AppSettings.equalizerEnabled.value
        val curve = when (AppSettings.equalizerMode.value) {
            EqualizerMode.DYNAMIC -> toneCurve(
                x = AppSettings.equalizerToneX.value,
                y = AppSettings.equalizerToneY.value,
                focused = AppSettings.equalizerFocused.value,
            )
            EqualizerMode.MANUAL -> manualCurve(AppSettings.equalizerBands.value)
        }
        val balance = AppSettings.equalizerBalance.value
        equalizerProcessorA.setTuning(enabled, curve, balance)
        equalizerProcessorB.setTuning(enabled, curve, balance)
    }

    private fun applySpatialAudioEnabled() {
        val enabled = AppSettings.spatialAudio.value && !activeTrackIsDolbyAtmos
        spatialAudioProcessorA.enabled = enabled
        spatialAudioProcessorB.enabled = enabled
    }

    private fun loudnessGainFor(mediaId: String): Float = loudnessGainMb(mediaId)
        ?.let { 10.0.pow(it / 2000.0).toFloat() }
        ?: 1f

    private fun loudnessGainMb(mediaId: String?): Int? {
        if (!AppSettings.loudnessNormalization.value) return null
        if (AppSettings.loudnessOffOnSpeaker.value &&
            AudioOutputStatus.current.value.routeKind == AudioRouting.Kind.PHONE
        ) return null
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return null
        val loudnessDb = StreamResolver.loudnessDbFor(id) ?: return null
        return (-loudnessDb * 100.0).roundToInt().coerceIn(MIN_LOUDNESS_GAIN_MB, MAX_LOUDNESS_GAIN_MB)
    }

    private fun setupLoudness(mediaId: String?) {
        val exoPlayer = player ?: return
        activeLoudness().track(mediaId, nextMediaIdOf(exoPlayer))
        publishLoudnessFor(mediaId)
    }

    private fun nextMediaIdOf(exoPlayer: ExoPlayer): String? {
        val next = exoPlayer.nextMediaItemIndex
        if (next == C.INDEX_UNSET || next !in 0 until exoPlayer.mediaItemCount) return null
        return exoPlayer.getMediaItemAt(next).mediaId
    }

    private fun publishLoudnessFor(mediaId: String?) {
        val gainMb = loudnessGainMb(mediaId)
        val lufs = mediaId?.takeIf { it.isNotBlank() }?.let(StreamResolver::loudnessDbFor)
        if (gainMb == null || lufs == null) {
            AudioOutputStatus.publishLoudness(gainDb = null, lufs = null)
        } else {
            AudioOutputStatus.publishLoudness(gainDb = gainMb / 100f, lufs = lufs.toFloat())
        }
    }

    private fun scheduleLoudnessRetry(mediaId: String?) {
        loudnessRetryJob?.cancel()
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return
        if (StreamResolver.loudnessDbFor(id) != null) return
        loudnessRetryJob = scope.launch {
            delay(LOUDNESS_RETRY_MS)
            if (player?.currentMediaItem?.mediaId == id) publishLoudnessFor(id)
        }
    }

    private fun observeDiscord() = discordPresence.observe()

    private fun pushDiscordPresence(exoPlayer: ExoPlayer) = discordPresence.push(exoPlayer)

    private fun clearDiscordPresence() = discordPresence.clear()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (AppSettings.stopOnTaskRemoved.value) {
            if (castPlayback.active) CastController.disconnect(resumeHere = false)
            eachPlayer { it.stop() }
            stopSelf()
        }
    }


    override fun onDestroy() {
        WearBridge.attachStreams(null)
        WearBridge.releaseSpeaker()
        CastController.detach(castPlayback)
        castPlayback.release()
        audioEffectSession.close()
        outputRouter.stop()
        partySync?.stop()
        partySync = null
        player?.let {
            saveQueueSnapshot(it)
            savePlaybackState(it)
        }
        publishWidgetState(playing = false)
        lyricsController.release()
        cancelPrefetch()
        trackAnalyzer.release()
        loudnessRetryJob?.cancel()
        PlaybackTracker.onPlaybackFinished(
            player?.currentPosition?.div(1000) ?: lastPositionSeconds,
        )
        listenBrainz.release()
        scrobbling.release()
        ListeningRecorder.onStopped()
        discordPresence.release()
        scope.cancel()
        QueueShuffle.isCrossfadeTransitioning = null
        crossfade?.release()
        crossfade = null
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playbackListener)
        player?.removeAnalyticsListener(formatListener)
        player?.release()
        player = null
        spare?.release()
        spare = null
        AudioOutputStatus.reset()
        NerdStats.forgetLastSession()
        telemetry.clear()
        super.onDestroy()
    }

    private fun loadLyricsForCurrentTrack() = lyricsController.loadLyricsForCurrentTrack()

    private fun startLyricsTicker() = lyricsController.startLyricsTicker()

    private fun stopLyricsTicker() = lyricsController.stopLyricsTicker()

    private fun updateLyricSubtitle() = lyricsController.updateLyricSubtitle()
    private inner class MediaLibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(favoriteCommand)
                .add(boundedContextCommand)
                .add(clearBoundedContextCommand)
                .add(autoplayCommand)
                .add(shuffleCommand)
                .add(startStationCommand)
                .add(revertToOriginalCommand)
                .add(swapToVersionCommand)
                .add(beginRadioQueueCommand)
                .add(commitRadioQueueCommand)
                .add(upgradeQualityCommand)
                .add(swapVersionCommand)
                .add(queueDragCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)) {
                customLayouts.addCarController(controller)
                refreshCustomLayouts()
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (customLayouts.removeCarController(controller)) refreshCustomLayouts()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_START_BOUNDED_CONTEXT -> boundedQueue.start(args)
                ACTION_CLEAR_BOUNDED_CONTEXT -> boundedQueue.clear()
                ACTION_TOGGLE_AUTOPLAY -> toggleAutoplayFromNotification()
                ACTION_TOGGLE_SHUFFLE -> toggleShuffleFromSession()
                ACTION_START_STATION -> startStationFromSession()
                ACTION_REVERT_TO_ORIGINAL -> revertCurrentToOriginal()
                ACTION_SWAP_TO_VERSION -> swapCurrentToVersion(args)
                ACTION_BEGIN_RADIO_QUEUE -> beginRadioQueue()
                ACTION_COMMIT_RADIO_QUEUE -> player?.let(::saveQueueSnapshotImmediately)
                ACTION_UPGRADE_QUALITY -> streamResolution.upgradeQualityNow()
                ACTION_SWAP_VERSION -> {
                    val bundle = args.getBundle(EXTRA_SWAP_MEDIA_ITEM)
                    if (bundle != null) {
                        val targetSong = songFromBundle(bundle)
                        if (targetSong.isVideo) {
                            MusicOnlyPlayback.keep(targetSong.videoId)
                        } else {
                            session.player.currentMediaItem?.mediaId?.let(MusicOnlyPlayback::letGo)
                        }
                        smoothSwapCurrentTrackVersion(targetSong.toMediaItem())
                    }
                }
                ACTION_QUEUE_DRAG -> {
                    if (args.getBoolean(EXTRA_QUEUE_DRAG_ACTIVE, false)) {
                        partySync?.beginQueueDrag()
                    } else {
                        partySync?.endQueueDrag()
                    }
                }
                ACTION_TOGGLE_FAVORITE -> session.player.currentMediaItem
                    ?.takeIf(::canLike)
                    ?.let { item ->
                        val song = item.toSong()
                        if (song.localUri != null) {
                            OfflinePlaylists.toggleLike(song)
                        } else {
                            toggleFavoriteFromNotification(item.mediaId)
                        }
                    }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            autoBrowseMediaLibraryCallbacks.onGetLibraryRoot(session, browser, params)

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            autoBrowseMediaLibraryCallbacks.onGetChildren(session, browser, parentId, page, pageSize, params)

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            autoBrowseMediaLibraryCallbacks.onGetItem(session, browser, mediaId)

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            autoBrowseMediaLibraryCallbacks.onPlaybackResumption(mediaSession, controller)

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> =
            autoBrowseMediaLibraryCallbacks.onSearch(session, browser, query, params)

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            autoBrowseMediaLibraryCallbacks.onGetSearchResult(session, browser, query, page, pageSize, params)

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            autoBrowseMediaLibraryCallbacks.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            autoBrowseMediaLibraryCallbacks.onAddMediaItems(mediaSession, controller, mediaItems)
    }

    private companion object {
        const val FOREIGN_CACHE_PURGE_DELAY_MS = 30_000L
        const val FOREIGN_CACHE_KEEP_AHEAD = 3

        val ROOT_CHILDREN: List<String> = listOf(
            MEDIA_QUICK_PICKS_ID,
            MEDIA_RECENTS_ID,
            MEDIA_PLAYLISTS_ID,
            MEDIA_LIKED_ID,
            MEDIA_MORE_ID,
        )

        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        const val CHANNEL_ID = "nuvx_playback"
        const val SESSION_ID = "NuvxPlayback"

        const val PROGRESS_SAMPLE_MS = 5_000L

        const val MIN_LOUDNESS_GAIN_MB = -1500
        const val MAX_LOUDNESS_GAIN_MB = 300

        const val LOUDNESS_RETRY_MS = 6_000L

        const val DISCORD_TEARDOWN_TIMEOUT_MS = 3_000L

        const val STREAM_CHUNK_BYTES = 1L * 1024 * 1024

        const val MIN_SILENCE_US = 1_000_000L

        const val LOOP_MIX_LYRICS_TIMEOUT_MS = 10_000L

        const val FAR_BUFFER_MS = 15 * 60 * 1000

        const val FAR_BUFFER_BYTES = 8 * 1024 * 1024

        const val BACK_BUFFER_MS = 30 * 1000

        const val START_PLAYBACK_MS = 500

        const val RESUME_PLAYBACK_MS = 2_000

        const val AUDITION_BUFFER_MS = 40_000

        const val AUDITION_BUFFER_BYTES = 24 * 1024 * 1024

    }
}
