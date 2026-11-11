package com.bxane.nuvx.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import com.bxane.nuvx.data.TelemetryProvenance
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.session.CommandButton
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
import com.bxane.nuvx.data.listentogether.partyQueueIndexOf
import com.bxane.nuvx.data.listentogether.partyUpcomingAfter
import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.model.NOTIFICATION_ART_PX
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.BrowseItem
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.artworkAt
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
import com.bxane.nuvx.widget.MediaWidget
import com.bxane.nuvx.widget.MediaWidgetSnapshot
import com.bxane.nuvx.wear.WearBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.CancellationException
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

private const val ALIGNMENT_HEAD_TIMEOUT_MS = 20_000L

private const val ALIGNMENT_MEASURE_TIMEOUT_MS = 30_000L

private fun Song.canStartStation(): Boolean =
    videoId.isNotBlank() &&
        !videoId.startsWith("content://") &&
        !videoId.startsWith("file://")

internal const val RECOVERY_DELAY_MS = 350L

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val searchResults = ConcurrentHashMap<String, List<Song>>()
    private val searchBrowseResults = ConcurrentHashMap<String, List<BrowseItem>>()
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

    private var advertisedAudioEffectSessionId: Int = C.AUDIO_SESSION_ID_UNSET

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

    private val boundedQueue = BoundedQueueController(
        scope = scope,
        player = { player },
        isTransitioning = { crossfade?.isTransitioning() == true },
        isCasting = { CastController.state.value.casting || castPlayback.active },
        refreshLayouts = { refreshCustomLayouts() },
        onContextStarted = {
            repeatAllStash = emptyList()
            repeatAllStashSeed = null
        },
        onRestoring = { persistedQueueStart = 0 },
    )

    private val streamResolution = StreamResolutionController(
        context = this,
        scope = scope,
        player = { player },
        crossfade = { crossfade },
        audioFormatFor = { formatListener.audioFormatFor },
        buildAuditionPlayer = ::buildAuditionPlayer,
        onSwapping = { swappingMediaId = it },
        onSwapCut = { formatListener.markSwapCut() },
    )

    private val errorRecovery = PlaybackErrorRecovery(
        scope = scope,
        player = { player },
        onSwapping = { swappingMediaId = it },
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

    private val localBitrateCache = ConcurrentHashMap<String, Int>()

    private val measuredBitrateCache = ConcurrentHashMap<String, Int>()
    private val measuringBitrate: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    private var favoriteActionJob: Job? = null
    private var stationActionJob: Job? = null
    private var autoplayLoadJob: Job? = null
    private var preferredPrefetchRequest: List<String>? = null
    private var autoplaySeed: String? = null

    private var persistedQueueStart = 0

    private var repeatAllStash: List<MediaItem> = emptyList()

    private var repeatAllStashSeed: String? = null

    private var lastRepeatMode = Player.REPEAT_MODE_OFF

    private val sessionSongHistory = mutableListOf<Song>()

    private val lyricsController = PlaybackLyricsController(
        context = { this@PlaybackService },
        scope = scope,
        player = { player },
        crossfade = { crossfade },
        loopMixLyricsTimeoutMs = LOOP_MIX_LYRICS_TIMEOUT_MS,
    )
    private val playbackListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val activePlayer = this@PlaybackService.player ?: return
            if (player === activePlayer) updateAudioEffectSession(activePlayer)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val exoPlayer = player ?: return
            if (isPlaying) {
                trackSelectedAt?.let {
                    TrackLog.d(
                        "nuvx",
                        "TIMING first audio: ${SystemClock.elapsedRealtime() - it}ms since track selected",
                        about = exoPlayer.currentMediaItem?.mediaId,
                    )
                    trackSelectedAt = null
                }
                errorRecovery.onAudible(exoPlayer.currentMediaItem?.mediaId)
            }
            if (isPlaying) registerCurrentPlay()
            if (isPlaying) prefetchAround(exoPlayer) else cancelPrefetch()
            if (isPlaying) streamResolution.lookForBetterCopy(exoPlayer)
            savePlaybackState(exoPlayer)
            publishWidgetState()

            val song = exoPlayer.currentMediaItem?.toSong()
            val durationMs = exoPlayer.duration.takeIf { it > 0 }
            scrobbling.onPlayerStateChanged(isPlaying, song, durationMs)

            if (!isPlaying) ListeningRecorder.onStopped()

            if (isPlaying && song != null) {
                listenBrainz.onIsPlayingChanged(song, exoPlayer.currentPosition, durationMs)
            }

            if (isPlaying) {
                pushDiscordPresence(exoPlayer)
                startLyricsTicker()
            } else {
                clearDiscordPresence()
                stopLyricsTicker()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) player?.let(speakers::onLocalStartedPlaying)
            publishWidgetState(playing = playWhenReady)
            partySync?.onPlayWhenReadyChanged(playWhenReady, reason)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val exoPlayer = player ?: return
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                lastSeekAt = SystemClock.elapsedRealtime()
                if (exoPlayer.isPlaying) pushDiscordPresence(exoPlayer)
                updateLyricSubtitle()
                WearBridge.republish(this@PlaybackService, exoPlayer)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val exoPlayer = player ?: return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                mediaItem?.mediaId != null &&
                mediaItem.mediaId == swappingMediaId
            ) {
                swappingMediaId = null
                return
            }

            speakers.onLocalTransition(mediaItem, reason)

            onTrackBecameCurrent(
                mediaItem,
                previousEnded = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                reason = reason,
            )
            QueueCoordinator.consumePlayedUserQueue(exoPlayer)

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                partySync?.onLocalIntent()
            }
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            loadAutoplayForCurrentTrack()
            loadLyricsForCurrentTrack()
            if (exoPlayer.isPlaying) startLyricsTicker()
            refreshCustomLayouts()
        }

        override fun onPlayerError(error: PlaybackException) {
            val exoPlayer = player ?: return
            errorRecovery.recoverFrom(error, exoPlayer)
        }

        override fun onPlaybackStateChanged(state: Int) {
            val exoPlayer = player ?: return
            if (state == Player.STATE_ENDED) {
                SleepTimer.cancel()
                PlaybackTracker.onPlaybackFinished(lastPositionSeconds)
                lastPositionSeconds = 0
                listenBrainz.onPlaybackEnded(exoPlayer)
                refreshAutoplayIfQueueEmpty()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val previous = lastRepeatMode
            lastRepeatMode = repeatMode
            speakers.onLocalRepeatChanged(repeatMode)
            player?.let { WearBridge.republish(this@PlaybackService, it) }
            player?.let { activeLoudness().nextMediaId = nextMediaIdOf(it) }
            when {
                repeatMode == Player.REPEAT_MODE_ALL -> {
                    stashAutoplayTracks()
                    if (boundedQueue.active) boundedQueue.scheduleTopUp()
                }
                previous == Player.REPEAT_MODE_ALL -> restoreAutoplayTracks()
            }
            loadAutoplayForCurrentTrack()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val exoPlayer = player ?: return
            convertVideosAround(exoPlayer)
            if (exoPlayer.isPlaying) prefetchAround(exoPlayer)
            activeLoudness().nextMediaId = nextMediaIdOf(exoPlayer)
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                speakers.onLocalQueueChanged()
                saveQueueSnapshot(exoPlayer)
                refreshCustomLayouts()
                refreshAutoplayIfQueueEmpty()
            }
        }
    }

    private val formatListener = PlaybackAudioAnalyticsListener(
        onDolbyAtmosChanged = { activeTrackIsDolbyAtmos = it },
        applySpatialAudioEnabled = ::applySpatialAudioEnabled,
        publishNerdStats = ::publishNerdStats,
        outputRouter = outputRouter,
    )

    override fun onCreate() {
        super.onCreate()

        if (com.bxane.nuvx.data.innertube.Innertube.cookie == null) {
            com.bxane.nuvx.data.innertube.Innertube.cookie = com.bxane.nuvx.auth.AuthStore(this).cookie
        }

        NerdStats.forgetLastSession()
        localBitrateCache.clear()
        measuredBitrateCache.clear()
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
            MediaWidgetSnapshot.save(this, MediaWidgetSnapshot.EMPTY)
            MediaWidget.refresh(this)
        }
        lastRepeatMode = exoPlayer.repeatMode
        exoPlayer.addListener(playbackListener)
        partySync = PartySync(scope) { player }.also { it.start() }
        scope.launch {
            ListenTogether.state
                .map { state -> Triple(state.code, state.playback.autoplayEnabled, autoplaySupplierId(state)) }
                .distinctUntilChanged()
                .collectLatest { (code, enabled, supplierId) ->
                    if (code != null && enabled && supplierId == ListenTogether.state.value.you?.memberId) {
                        autoplayLoadJob?.cancel()
                        autoplayLoadJob = null
                        autoplaySeed = null
                        loadAutoplayForCurrentTrack()
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
        loadAutoplayForCurrentTrack()

        exoPlayer.addAnalyticsListener(formatListener)

        reportProgress()
        watchDirectWindow()

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
            versionSwapActive = { versionSwapJob?.isActive == true },
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

    private fun notificationButtons(): List<CommandButton> {
        val liked = isLiked(player?.currentMediaItem)
        val favorite = if (!canLike(player?.currentMediaItem)) null else CommandButton.Builder(
            if (liked) CommandButton.ICON_STAR_FILLED else CommandButton.ICON_STAR_UNFILLED,
        )
            .setCustomIconResId(if (liked) R.drawable.ic_notification_star_filled else R.drawable.ic_notification_star)
            .setSessionCommand(favoriteCommand)
            .setDisplayName("Favorite")
            .build()
        val shuffleEnabled = QueueShuffle.enabled.value
        val shuffle = CommandButton.Builder(
            if (shuffleEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            },
        )
            .setSessionCommand(shuffleCommand)
            .setDisplayName(if (shuffleEnabled) "Shuffle off" else "Shuffle on")
            .build()
        return listOfNotNull(favorite, shuffle)
    }

    private fun carButtons(): List<CommandButton> {
        val current = player?.currentMediaItem?.toSong()
        val station = current
            ?.takeIf { it.canStartStation() }
            ?.let {
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setSessionCommand(startStationCommand)
                    .setDisplayName(getString(R.string.start_radio))
                    .build()
            }
        val revert = current
            ?.takeIf {
                it.hasYouTubeOriginal() &&
                    it.localUri == null &&
                    !OriginalVersion.isPinned(it.videoId)
            }
            ?.let {
                CommandButton.Builder(CommandButton.ICON_SYNC)
                    .setSessionCommand(revertToOriginalCommand)
                    .setDisplayName(getString(R.string.revert_to_original))
                    .build()
            }
        return notificationButtons() + listOfNotNull(station, revert)
    }

    private val carControllers = mutableSetOf<MediaSession.ControllerInfo>()

    private fun refreshCustomLayouts() {
        val session = mediaSession ?: return
        val car = carButtons()
        session.setCustomLayout(car)
        session.getMediaNotificationControllerInfo()?.let {
            session.setCustomLayout(it, if (carControllers.isEmpty()) notificationButtons() else car)
        }
    }

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

    private var versionSwapJob: Job? = null
    private var versionSwapGeneration = 0

    private fun smoothSwapCurrentTrackVersion(
        targetMediaItem: MediaItem,
        onSwapCommitted: (() -> Unit)? = null,
    ) {
        val activePlayer = player ?: return
        val currentIndex = activePlayer.currentMediaItemIndex
        if (currentIndex !in 0 until activePlayer.mediaItemCount) return
        val currentItem = activePlayer.currentMediaItem ?: return
        if (ListenTogether.state.value.inParty) {
            TrackLog.d("nuvx", "version swap ignored: in a Listen Together party", about = currentItem.mediaId)
            return
        }
        if (crossfade?.isTransitioning() == true) {
            TrackLog.d("nuvx", "version swap deferred: Automix is mid-transition", about = currentItem.mediaId)
            return
        }

        val sourceSong = currentItem.toSong()
        val targetSong = targetMediaItem.toSong()
        val officialMap = YtMusicRepository.versionTimeMap(sourceSong.videoId, targetSong.videoId)
        val measureAlignment = officialMap == null && AppSettings.smartVersionAlignment.value
        val holdsSwitchBar = measureAlignment || officialMap != null

        versionSwapJob?.cancel()
        val generation = ++versionSwapGeneration
        versionSwapJob = scope.launch(Dispatchers.Main) {
            val mediaId = targetMediaItem.mediaId
            swappingMediaId = mediaId
            if (holdsSwitchBar) {
                AppSettings.versionAlignmentInProgress.value = true
            }
            try {
                val startPosition = activePlayer.currentPosition
                val alignmentOffsetMs = if (measureAlignment) {
                    prepareVersionAlignment(sourceSong, targetSong, startPosition)
                } else {
                    0L
                }
                val alignedPosition: (Long) -> Long = if (officialMap != null) {
                    { pos -> officialMap.map(pos).coerceAtLeast(0L) }
                } else {
                    { pos -> (pos + alignmentOffsetMs).coerceAtLeast(0L) }
                }
                if (officialMap != null) {
                    val mapped = alignedPosition(startPosition)
                    VersionAudioAligner.publishPhase(
                        VersionAudioAligner.beginAttempt(sourceSong.videoId, targetSong.videoId),
                        VersionAudioAligner.CutPhase.ALIGNED,
                        mapped - startPosition,
                    )
                    TrackLog.d(
                        "nuvx",
                        "version swap using YouTube's time map: ${startPosition}ms -> ${mapped}ms",
                        about = sourceSong.videoId,
                    )
                }

                if (player !== activePlayer ||
                    activePlayer.currentMediaItemIndex != currentIndex ||
                    activePlayer.currentMediaItem?.mediaId != sourceSong.videoId
                ) {
                    swappingMediaId = null
                    return@launch
                }

                val standbyPlayer = spare
                val canCrossfade = standbyPlayer != null &&
                    (activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_READY)

                if (!canCrossfade || standbyPlayer == null) {
                    val pos = alignedPosition(activePlayer.currentPosition)
                    val wasPlaying = activePlayer.playWhenReady
                    activePlayer.replaceMediaItem(currentIndex, targetMediaItem)
                    activePlayer.seekTo(currentIndex, pos)
                    if (wasPlaying) activePlayer.play()
                    onSwapCommitted?.invoke()
                    refreshCustomLayouts()
                    swappingMediaId = null
                    return@launch
                }

                val newItems = (0 until activePlayer.mediaItemCount).map { i ->
                    if (i == currentIndex) targetMediaItem else activePlayer.getMediaItemAt(i)
                }
                val wasPlaying = activePlayer.playWhenReady
                val alignedStartPos = alignedPosition(activePlayer.currentPosition)

            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
            standbyPlayer.skipSilenceEnabled = activePlayer.skipSilenceEnabled
            standbyPlayer.repeatMode = activePlayer.repeatMode
            standbyPlayer.shuffleModeEnabled = activePlayer.shuffleModeEnabled
            standbyPlayer.setPlaybackSpeed(AppSettings.playbackSpeed.value)
            standbyPlayer.volume = 0f
            standbyPlayer.setMediaItems(newItems, currentIndex, alignedStartPos)
            spareLoudness().track(mediaId, nextMediaIdOf(standbyPlayer))
            standbyPlayer.playWhenReady = false
            standbyPlayer.prepare()

            val timeoutAt = SystemClock.elapsedRealtime() + 7_000L
            var isReady = false
            while (isActive && SystemClock.elapsedRealtime() < timeoutAt) {
                if (player !== activePlayer ||
                    activePlayer.currentMediaItemIndex != currentIndex
                ) {
                    standbyPlayer.stop()
                    standbyPlayer.clearMediaItems()
                    standbyPlayer.volume = 1f
                    swappingMediaId = null
                    return@launch
                }

                if (standbyPlayer.playbackState == Player.STATE_READY) {
                    isReady = true
                    break
                }
                if (standbyPlayer.playbackState == Player.STATE_IDLE && standbyPlayer.playerError != null) {
                    break
                }
                delay(20)
            }

            if (!isReady) {
                TrackLog.w("nuvx", "version swap standby player failed to prepare, applying directly", about = mediaId)
                standbyPlayer.stop()
                standbyPlayer.clearMediaItems()
                standbyPlayer.volume = 1f
                swappingMediaId = null
                val pos = alignedPosition(activePlayer.currentPosition)
                activePlayer.replaceMediaItem(currentIndex, targetMediaItem)
                activePlayer.seekTo(currentIndex, pos)
                if (wasPlaying) activePlayer.play()
                onSwapCommitted?.invoke()
                onTrackBecameCurrent(
                    targetMediaItem,
                    previousEnded = false,
                    reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                    alreadyAudible = true,
                )
                refreshCustomLayouts()
                return@launch
            }

            standbyPlayer.seekTo(currentIndex, alignedPosition(activePlayer.currentPosition))

            val willPlay = wasPlaying && activePlayer.playWhenReady
            if (willPlay) {
                standbyPlayer.play()
                val runningTimeout = SystemClock.elapsedRealtime() + 600L
                while (isActive && !standbyPlayer.isPlaying && SystemClock.elapsedRealtime() < runningTimeout) {
                    delay(10)
                }
                if (standbyPlayer.isPlaying) {
                    standbyPlayer.seekTo(currentIndex, alignedPosition(activePlayer.currentPosition))
                    val resyncTimeout = SystemClock.elapsedRealtime() + 300L
                    while (isActive && standbyPlayer.playbackState != Player.STATE_READY && SystemClock.elapsedRealtime() < resyncTimeout) {
                        delay(10)
                    }
                }
            } else {
                val seekTimeout = SystemClock.elapsedRealtime() + 400L
                while (isActive && standbyPlayer.playbackState != Player.STATE_READY && SystemClock.elapsedRealtime() < seekTimeout) {
                    delay(10)
                }
            }

            val swapCrossfadeMs = 550L
            val fadeStart = SystemClock.elapsedRealtime()
            if (!holdsSwitchBar) {
                AppSettings.smartMixInProgress.value = true
            }

            try {
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime() - fadeStart
                    val progress = (elapsed.toFloat() / swapCrossfadeMs).coerceIn(0f, 1f)

                    if (player !== activePlayer || activePlayer.currentMediaItemIndex != currentIndex) {
                        standbyPlayer.stop()
                        standbyPlayer.clearMediaItems()
                        standbyPlayer.volume = 1f
                        activePlayer.volume = 1f
                        swappingMediaId = null
                        return@launch
                    }

                    if (standbyPlayer.playWhenReady != activePlayer.playWhenReady) {
                        standbyPlayer.playWhenReady = activePlayer.playWhenReady
                    }

                    val inGain = sin(progress * (PI / 2.0)).toFloat()
                    val outGain = cos(progress * (PI / 2.0)).toFloat()
                    standbyPlayer.volume = inGain
                    activePlayer.volume = outGain
                    val swapTrim = 1f / sqrt((inGain + outGain).coerceAtLeast(1f))
                    loudnessA.setBlendTrim(swapTrim)
                    loudnessB.setBlendTrim(swapTrim)

                    if (progress >= 1f) break
                    delay(16)
                }

                adoptPlayerForVersionSwap(outgoing = activePlayer, incoming = standbyPlayer)
                onSwapCommitted?.invoke()
            } finally {
                loudnessA.setBlendTrim(1f)
                loudnessB.setBlendTrim(1f)
                if (!holdsSwitchBar) {
                    AppSettings.smartMixInProgress.value = false
                }
            }
            } finally {
                if (generation == versionSwapGeneration) {
                    AppSettings.versionAlignmentInProgress.value = false
                }
            }
        }
    }

    private val versionAlignmentMeasurements = ConcurrentHashMap<Pair<String, String>, Deferred<Long?>>()

    private fun measureVersionAlignmentAsync(sourceSong: Song, targetSong: Song): Deferred<Long?> {
        val sourceId = sourceSong.videoId
        val targetId = targetSong.videoId
        val key = sourceId to targetId
        VersionAudioAligner.getCachedOffsetMs(sourceId, targetId)?.let { return CompletableDeferred(it) }
        versionAlignmentMeasurements[key]?.let { return it }
        val deferred = scope.async(Dispatchers.Main) {
            try {
                val sourceUri = Uri.parse(sourceSong.localUri ?: AutomixAnalysisSource.opusUri(sourceId))
                val targetUri = Uri.parse(targetSong.localUri ?: AutomixAnalysisSource.opusUri(targetId))
                withContext(Dispatchers.IO) {
                    val sourceHead = async {
                        sourceSong.localUri != null ||
                            AudioCache.awaitAnalysisHead(sourceUri, ALIGNMENT_HEAD_TIMEOUT_MS)
                    }
                    val targetHead = async {
                        targetSong.localUri != null ||
                            AudioCache.awaitAnalysisHead(targetUri, ALIGNMENT_HEAD_TIMEOUT_MS)
                    }
                    val sourceReady = sourceHead.await()
                    val targetReady = targetHead.await()
                    if (!sourceReady || !targetReady) {
                        TrackLog.w(
                            "nuvx",
                            "version alignment head short on disk (source=$sourceReady, " +
                                "target=$targetReady) for '${sourceSong.title}'",
                            sourceId,
                        )
                    }
                }
                withTimeoutOrNull(ALIGNMENT_MEASURE_TIMEOUT_MS) {
                    VersionAudioAligner.findOffsetMs(
                        context = this@PlaybackService,
                        sourceSong = sourceSong,
                        targetSong = targetSong,
                    )
                }
            } finally {
                versionAlignmentMeasurements.remove(key)
            }
        }
        versionAlignmentMeasurements[key] = deferred
        return deferred
    }

    private suspend fun prepareVersionAlignment(
        sourceSong: Song,
        targetSong: Song,
        currentPosMs: Long,
    ): Long {
        val sourceId = sourceSong.videoId
        val targetId = targetSong.videoId

        VersionAudioAligner.getCachedOffsetMs(sourceId, targetId)?.let { cached ->
            val cachedToken = VersionAudioAligner.beginAttempt(sourceId, targetId)
            VersionAudioAligner.publishPhase(
                cachedToken,
                VersionAudioAligner.CutPhase.ALIGNED,
                cached,
                fromCache = true,
            )
            return cached
        }

        val token = VersionAudioAligner.beginAttempt(sourceId, targetId)
        try {
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FETCHING)
            val deferred = measureVersionAlignmentAsync(sourceSong, targetSong)
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.MEASURING)
            val measured = deferred.await()
            if (measured == null) {
                TrackLog.w(
                    "nuvx",
                    "version alignment found nothing to measure for '${sourceSong.title}'; switching with no shift",
                    sourceId,
                )
                VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FAILED)
                return 0L
            }
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.ALIGNED, measured)
            return measured
        } catch (e: CancellationException) {
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FAILED)
            throw e
        }
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

    private fun revertCurrentToOriginal() {
        val activePlayer = player ?: return
        val index = activePlayer.currentMediaItemIndex
        if (index !in 0 until activePlayer.mediaItemCount) return
        val song = activePlayer.currentMediaItem?.toSong() ?: return
        if (!song.hasYouTubeOriginal() ||
            song.localUri != null ||
            OriginalVersion.isPinned(song.videoId)
        ) {
            return
        }

        OriginalVersion.pin(song.videoId)
        val position = activePlayer.currentPosition
        val wasPlaying = activePlayer.isPlaying
        swappingMediaId = song.videoId
        activePlayer.replaceMediaItem(index, song.toDirectYouTubeMediaItem())
        activePlayer.seekTo(index, position)
        if (wasPlaying) activePlayer.play()
        refreshCustomLayouts()
    }

    private fun swapCurrentToVersion(args: Bundle) {
        val activePlayer = player ?: return
        val index = activePlayer.currentMediaItemIndex
        if (index !in 0 until activePlayer.mediaItemCount) return
        val song = activePlayer.currentMediaItem?.toSong() ?: return

        val targetVideoId = args.getString("targetVideoId")
        val targetIsVideo = args.getBoolean("targetIsVideo", false)

        if (targetVideoId == null || targetVideoId == song.videoId) return

        val position = activePlayer.currentPosition
        val wasPlaying = activePlayer.isPlaying
        swappingMediaId = targetVideoId
        PlaybackTracker.onVersionSwapped(song.videoId, targetVideoId)

        val targetSong = song.copy(
            videoId = targetVideoId,
            isVideo = targetIsVideo,
            isVideoOrigin = if (targetIsVideo) false else song.isVideoOrigin,
        )

        activePlayer.replaceMediaItem(index, targetSong.toMediaItem())
        activePlayer.seekTo(index, position)
        if (wasPlaying) activePlayer.play()
        refreshCustomLayouts()
    }

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
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            loadAutoplayForCurrentTrack()
        } else {
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            dropAutoplayTracksFromQueue()
            repeatAllStash = emptyList()
            repeatAllStashSeed = null
        }
        refreshCustomLayouts()
    }

    private fun loadAutoplayForCurrentTrack() {
        val exoPlayer = player ?: return
        val party = ListenTogether.state.value
        if (!autoplayEnabled(party) ||
            exoPlayer.repeatMode == Player.REPEAT_MODE_ALL ||
            (party.inParty && autoplaySupplierId(party) != party.you?.memberId)
        ) {
            return
        }
        if (boundedQueue.active && !boundedQueue.autoplayEligible(exoPlayer)) {
            return
        }
        val current = exoPlayer.currentMediaItem?.toSong() ?: return
        if (isLiveRadio(current.videoId)) return
        if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += current
        val queuedAutoplay = partyAutoplayWaiting(party, current.videoId)
            ?: (exoPlayer.currentMediaItemIndex + 1 until exoPlayer.mediaItemCount)
                .count { exoPlayer.getMediaItemAt(it).fromAutoplay }
        val needed = MAX_QUEUED_AUTOPLAY - queuedAutoplay
        if (needed <= 0) return
        if (autoplaySeed == current.videoId) return
        autoplaySeed = current.videoId
        autoplayLoadJob = scope.launch {
            var remaining = needed
            var emptyRefreshesRemaining = MAX_AUTOPLAY_EMPTY_REFRESHES
            var seedBack = 0
            while (isActive) {
                val activePlayer = player ?: return@launch
                val activeParty = ListenTogether.state.value
                if (!autoplayEnabled(activeParty) ||
                    activePlayer.repeatMode == Player.REPEAT_MODE_ALL ||
                    activePlayer.currentMediaItem?.mediaId != current.videoId ||
                    (activeParty.inParty && autoplaySupplierId(activeParty) != activeParty.you?.memberId)
                ) {
                    return@launch
                }
                val queueSongs = (0 until activePlayer.mediaItemCount)
                    .map { activePlayer.getMediaItemAt(it).toSong() } +
                    activeParty.queue.items.takeIf { activeParty.inParty }.orEmpty().map { it.toSong() }
                val existing = if (AppSettings.dontRepeatSuggestions.value) {
                    queueSongs + sessionSongHistory
                } else {
                    queueSongs
                }
                val seedSong = if (seedBack == 0) {
                    current
                } else {
                    val at = activePlayer.currentMediaItemIndex - seedBack
                    if (at >= 0) activePlayer.getMediaItemAt(at).toSong() else current
                }
                val result = loadAutoplayTracks(existing, seedSong, remaining)
                val resolved = result.getOrElse {
                    TrackLog.w(
                        "nuvx",
                        "notification autoplay failed: ${it.message}",
                        about = current.videoId,
                    )
                    emptyList()
                }
                val latestPlayer = player ?: return@launch
                val latestParty = ListenTogether.state.value
                if (!autoplayEnabled(latestParty) ||
                    latestPlayer.repeatMode == Player.REPEAT_MODE_ALL ||
                    latestPlayer.currentMediaItem?.mediaId != current.videoId ||
                    (latestParty.inParty && autoplaySupplierId(latestParty) != latestParty.you?.memberId)
                ) {
                    return@launch
                }
                if (resolved.isNotEmpty()) {
                    if (latestParty.inParty) {
                        val landed = topUpPartyAutoplay(current.videoId, resolved)
                        if (!landed) {
                            TrackLog.w(
                                "nuvx",
                                "party did not take AutoPlay's tracks; releasing the seed to retry",
                                about = current.videoId,
                            )
                            autoplaySeed = null
                            return@launch
                        }
                    } else {
                        val queuedNow = (0 until latestPlayer.mediaItemCount)
                            .map { latestPlayer.getMediaItemAt(it).toSong() }
                        val room = MAX_QUEUED_AUTOPLAY -
                            (latestPlayer.currentMediaItemIndex + 1 until latestPlayer.mediaItemCount)
                                .count { latestPlayer.getMediaItemAt(it).fromAutoplay }
                        val fresh = resolved
                            .filterNot { song -> queuedNow.any { QueueBuilder.isSameRecording(it, song) } }
                            .take(room.coerceAtLeast(0))
                        if (fresh.isNotEmpty()) latestPlayer.addMediaItems(fresh.map { it.toMediaItem() })
                    }
                    if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += resolved
                    return@launch
                }

                val at = latestPlayer.currentMediaItemIndex
                if (at < 0 || at != latestPlayer.mediaItemCount - 1) return@launch
                if (emptyRefreshesRemaining-- > 0) {
                    delay(AUTOPLAY_EMPTY_REFRESH_DELAY_MS)
                } else {
                    delay(AUTOPLAY_WATCHDOG_MS)
                    while (isActive && player?.playWhenReady == false) delay(AUTOPLAY_WATCHDOG_MS)
                    seedBack = if (at - (seedBack + 1) >= 0) seedBack + 1 else 0
                }
                remaining = MAX_QUEUED_AUTOPLAY
            }
        }
    }

    private fun partyAutoplayWaiting(party: ListenTogether.State, videoId: String): Int? {
        if (!party.inParty) return null
        if (partyQueueIndexOf(party.queue, party.playback, videoId) < 0) return null
        return partyUpcomingAfter(party.queue, party.playback, videoId).count { it.fromAutoplay }
    }

    private val partyAutoplayLock = Mutex()

    private suspend fun topUpPartyAutoplay(currentId: String, suggestions: List<Song>): Boolean =
        partyAutoplayLock.withLock {
            withContext(NonCancellable) {
                val party = ListenTogether.state.value
                val waiting = partyUpcomingAfter(party.queue, party.playback, currentId)
                val waitingIds = waiting.mapTo(HashSet()) { it.videoId }
                val room = MAX_QUEUED_AUTOPLAY - waiting.count { it.fromAutoplay }
                val fresh = suggestions.filterNot { it.videoId in waitingIds }.take(room.coerceAtLeast(0))
                if (fresh.isEmpty()) return@withContext true
                ListenTogether.queueAdd(fresh.map { it.toPartyTrack(0L) })
                val added = fresh.first().videoId
                withTimeoutOrNull(PARTY_QUEUE_ECHO_TIMEOUT_MS) {
                    ListenTogether.state.first { state -> state.queue.items.any { it.videoId == added } }
                } != null
            }
        }

    private var autoplayAheadSeen = 0

    private fun refreshAutoplayIfQueueEmpty() {
        val exoPlayer = player ?: return
        val aheadNow = (exoPlayer.currentMediaItemIndex + 1 until exoPlayer.mediaItemCount)
            .count { exoPlayer.getMediaItemAt(it).fromAutoplay }
        val tailShrank = aheadNow < autoplayAheadSeen
        autoplayAheadSeen = aheadNow
        if (!autoplayQueueNeedsRefresh(
                enabled = autoplayEnabled(ListenTogether.state.value),
                repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL,
                currentIndex = exoPlayer.currentMediaItemIndex,
                itemCount = exoPlayer.mediaItemCount,
                loadInProgress = autoplayLoadJob?.isActive == true,
            ) && !(tailShrank && aheadNow < MAX_QUEUED_AUTOPLAY &&
                autoplayEnabled(ListenTogether.state.value) &&
                exoPlayer.repeatMode != Player.REPEAT_MODE_ALL &&
                autoplayLoadJob?.isActive != true)
        ) {
            return
        }
        autoplayLoadJob = null
        autoplaySeed = null
        loadAutoplayForCurrentTrack()
    }

    private fun autoplayEnabled(party: ListenTogether.State): Boolean =
        autoplayEnabledFor(party, AppSettings.autoplay.value)

    private fun dropAutoplayTracksFromQueue(): List<MediaItem> {
        val exoPlayer = player ?: return emptyList()
        val dropped = mutableListOf<MediaItem>()
        for (index in exoPlayer.mediaItemCount - 1 downTo exoPlayer.currentMediaItemIndex + 1) {
            val item = exoPlayer.getMediaItemAt(index)
            if (!item.fromAutoplay) continue
            dropped += item
            exoPlayer.removeMediaItem(index)
        }
        return dropped.reversed()
    }

    private fun stashAutoplayTracks() {
        val exoPlayer = player ?: return
        if (repeatAllStash.isNotEmpty()) return
        val dropped = dropAutoplayTracksFromQueue()
        if (dropped.isEmpty()) return
        repeatAllStash = dropped
        repeatAllStashSeed = exoPlayer.currentMediaItem?.mediaId
    }

    private fun restoreAutoplayTracks() {
        val exoPlayer = player ?: return
        val stashed = repeatAllStash
        val seed = repeatAllStashSeed
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        if (stashed.isEmpty() || !autoplayEnabled(ListenTogether.state.value)) return
        if (exoPlayer.currentMediaItem?.mediaId != seed) return
        val present = (0 until exoPlayer.mediaItemCount)
            .mapTo(mutableSetOf()) { exoPlayer.getMediaItemAt(it).mediaId }
        val restored = stashed.filter { it.mediaId !in present }
        if (restored.isEmpty()) return
        exoPlayer.addMediaItems(restored)
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

        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        loadAutoplayForCurrentTrack()
    }

    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    private fun updateAudioEffectSession(activePlayer: ExoPlayer) {
        val sessionId = activePlayer.audioSessionId
        val shouldAdvertise = activePlayer.playWhenReady &&
            (activePlayer.playbackState == Player.STATE_BUFFERING ||
                activePlayer.playbackState == Player.STATE_READY) &&
            sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0

        if (!shouldAdvertise) {
            closeAudioEffectSession()
            return
        }
        if (advertisedAudioEffectSessionId == sessionId) return

        closeAudioEffectSession()
        advertisedAudioEffectSessionId = sessionId
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        val sessionId = advertisedAudioEffectSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

        advertisedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
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
        measuredBitrateCache.clear()
        AudioOutputStatus.onTrackTransition()
        mediaItem?.let { AudioCache.notePlayed(it.toSong(), it.localConfiguration?.uri) }
        scheduleForeignCachePurge()
        mediaItem?.mediaId?.takeUnless(::isLiveRadio)?.let { id ->
            resolveLocalBitrate(id, mediaItem)
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
        convertVideosAround(exoPlayer)
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

    private fun isLocalPlayback(mediaId: String, mediaItem: MediaItem?): Boolean {
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://") || mediaId.startsWith("/")) {
            return true
        }
        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null && (uri.scheme == "file" || uri.scheme == "content")) {
            return true
        }
        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return true
        }
        val extras = mediaItem?.mediaMetadata?.extras
        val localUri = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUri.isNullOrBlank() && (localUri.startsWith("content://") || localUri.startsWith("file://"))) {
            return true
        }
        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return true
        }
        if (Downloads.verifiedSavedUri(mediaId) != null) {
            return true
        }
        return false
    }

    private fun currentSourceName(mediaId: String?, mediaItem: MediaItem?): String? {
        val id = mediaId ?: return null
        if (isLocalPlayback(id, mediaItem)) {
            return "Local Storage"
        }

        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null) {
            QualityUpgrade.forcedStream(uri)?.let { upgraded ->
                val name = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                if (!name.isNullOrBlank()) return name
            }
        }

        val exactRecorded = NerdStats.exactSourceFor(id)
        if (!exactRecorded.isNullOrBlank()) return exactRecorded

        val sourceTrack = SourceRegistry.parseTrackKey(id)
        if (sourceTrack != null) {
            val config = SourceRegistry.config(sourceTrack.first)
            if (config != null) return config.displayName
            return "Unknown"
        }

        StreamChoice.of(id)?.let { serving ->
            val name = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: if (StreamChoice.isSubstitute(id)) "Module" else null
            if (!name.isNullOrBlank()) return name
        }

        if (uri?.authority == "source") {
            val configId = uri.getQueryParameter("s")
            val name = configId?.let { SourceRegistry.config(it)?.displayName }
            if (!name.isNullOrBlank()) return name
            return "Unknown"
        }

        val recorded = NerdStats.sourceFor(id)
        if (!recorded.isNullOrBlank()) return recorded

        if (id.length == 11 || uri?.getQueryParameter("v") != null) {
            return "YouTube"
        }

        return "Unknown"
    }

    private fun resolveLocalBitrate(mediaId: String, mediaItem: MediaItem?) {
        if (!isLocalPlayback(mediaId, mediaItem)) return
        if (localBitrateCache.containsKey(mediaId)) return

        val uri = streamResolution.resolveLocalUri(mediaId, mediaItem) ?: return
        scope.launch(Dispatchers.IO) {
            val kbps = runCatching {
                val retriever = MediaMetadataRetriever()
                var bps: Long? = null
                var durMs: Long? = null
                try {
                    retriever.setDataSource(this@PlaybackService, uri)
                    bps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    retriever.release()
                }

                if (bps != null && bps > 0) {
                    ((bps + 500L) / 1000L).toInt()
                } else if (durMs != null && durMs > 0) {
                    val sizeBytes = when (uri.scheme) {
                        "file" -> uri.path?.let { java.io.File(it).length() }
                        "content" -> contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                        else -> null
                    }
                    if (sizeBytes != null && sizeBytes > 0) {
                        ((sizeBytes * 8000L) / durMs / 1000L).toInt().takeIf { it > 0 }
                    } else null
                } else null
            }.getOrNull()

            if (kbps != null && kbps > 0) {
                localBitrateCache[mediaId] = kbps
                withContext(Dispatchers.Main) {
                    if (player?.currentMediaItem?.mediaId == mediaId) {
                        publishNerdStats()
                    }
                }
            }
        }
    }

    private fun publishNerdStats() {
        val player = player ?: return
        val format = player.audioFormat
        val mediaId = player.currentMediaItem?.mediaId
        val generation = NerdStats.currentGeneration
        val measured = format?.measure()
        val dsd = format?.codecs?.takeIf { it.startsWith("dsd") }
        val dsdRateHz = dsd?.removePrefix("dsd")?.toIntOrNull()?.times(44_100)
        val isLossless = dsd != null || NerdStats.isLosslessMime(format?.sampleMimeType)
        val isRawPcm = NerdStats.isRawPcm(format?.sampleMimeType)
        val pcmDataRate = measured?.pcmBitrateKbps

        val (bitrate, provenance) = when {
            format?.averageBitrate != null && format.averageBitrate > 0 ->
                Pair((format.averageBitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            format?.bitrate != null && format.bitrate > 0 ->
                Pair((format.bitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            mediaId != null && localBitrateCache.containsKey(mediaId) ->
                Pair(localBitrateCache[mediaId], TelemetryProvenance.AUTHORITATIVE)
            dsd == null && isRawPcm && pcmDataRate != null ->
                Pair(pcmDataRate, TelemetryProvenance.DERIVED)
            else ->
                NerdStats.sourceBitrate(mediaId) { measuredStreamKbps(player) }
                    ?: Pair(null, TelemetryProvenance.UNKNOWN)
        }

        NerdStats.publish(NerdStats.Snapshot(
            mimeType = dsd?.let { "audio/$it" } ?: format?.sampleMimeType,
            bitrateKbps = bitrate,
            bitrateProvenance = provenance,
            pcmDataRateKbps = if (isLossless) pcmDataRate else null,
            sampleRateHz = dsdRateHz ?: measured?.sampleRateHz,
            channels = measured?.channels,
            bitDepth = if (dsd != null) 1 else if (isLossless) measured?.bitDepth else null,
            claimed = NerdStats.declaredFormat(mediaId),
            sourceName = currentSourceName(mediaId, player.currentMediaItem),
            container = when (format?.containerMimeType) {
                "audio/x-dsf" -> "DSF"
                "audio/x-dff" -> "DFF"
                else -> null
            },
            mediaId = mediaId,
        ), expectedGeneration = generation)
    }

    private fun measuredStreamKbps(player: Player): Int? {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return null
        val key = uri.toString()
        measuredBitrateCache[key]?.let { return it }
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        if (measuringBitrate.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = AudioCache.contentLengthOf(uri)
                    if (bytes > 0) {
                        val kbps = (bytes * 8 / durationMs).toInt()
                        if (kbps > 0) measuredBitrateCache[key] = kbps
                    }
                } finally {
                    measuringBitrate.remove(key)
                }
            }
        }
        return null
    }

    private fun saveQueueSnapshot(player: ExoPlayer) {
        if (ListenTogether.state.value.inParty) return
        if (player.mediaItemCount == 0) {
            persistedQueueStart = 0
            LastPlayed.clear()
            BoundedContextPersistence.clear()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        if (range.isEmpty()) return
        persistedQueueStart = range.first
        LastPlayed.saveQueue(
            songs = range.map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex - range.first,
        )
        boundedQueue.saveSnapshot(player)
        savePlaybackState(player)
    }

    private fun saveQueueSnapshotImmediately(player: ExoPlayer) {
        if (ListenTogether.state.value.inParty) return
        if (player.mediaItemCount == 0) {
            persistedQueueStart = 0
            LastPlayed.clearImmediately()
            BoundedContextPersistence.clearImmediately()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        persistedQueueStart = range.first
        LastPlayed.saveQueueImmediately(
            songs = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
        )
        boundedQueue.saveSnapshot(player, immediately = true)
    }

    private fun beginRadioQueue() {
        crossfade?.onSkipRequested()
        boundedQueue.clear()
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
        sessionSongHistory.clear()
        persistedQueueStart = 0
        LastPlayed.clearImmediately()
    }

    private fun savePlaybackState(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        boundedQueue.savePlaybackState(player)
        LastPlayed.savePlaybackState(
            index = player.currentMediaItemIndex - persistedQueueStart,
            positionMs = player.currentPosition,
        )
    }

    private fun restoreLastQueue(player: ExoPlayer): Boolean {
        val last = LastPlayed.load() ?: return false
        persistedQueueStart = 0
        player.setMediaItems(last.songs.map { it.toMediaItem() }, last.index, last.positionMs)
        return true
    }

    private fun restoreBoundedQueueOrLast(player: ExoPlayer): Boolean {
        val persisted = BoundedContextPersistence.load()
        if (persisted != null && boundedQueue.restore(player, persisted)) return true
        if (persisted != null) BoundedContextPersistence.clearImmediately()
        return restoreLastQueue(player)
    }

    private fun publishWidgetState(playing: Boolean? = null) {
        val exoPlayer = player ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        val liked = (if (song.localUri != null) OfflinePlaylists.isLiked(song) else null)
            ?: LikeState.overrides.value[song.videoId]?.let { it == LikeStatus.LIKE }
            ?: MediaWidgetSnapshot.load(this).takeIf { it.mediaId == song.videoId }?.isLiked
            ?: false
        val reported: Player = mediaSession?.player ?: exoPlayer
        val snapshot = MediaWidgetSnapshot(
            mediaId = song.videoId,
            title = song.title,
            artist = song.artist,
            artworkUrl = song.thumbnailUrl,
            isPlaying = playing ?: reported.playWhenReady,
            hasPrevious = exoPlayer.hasPreviousMediaItem(),
            hasNext = exoPlayer.hasNextMediaItem(),
            isLiked = liked,
            canLike = canLike(exoPlayer.currentMediaItem),
            shuffleEnabled = QueueShuffle.enabled.value,
        )
        MediaWidgetSnapshot.save(this, snapshot)
        MediaWidget.refresh(this)
        WearBridge.publish(this, snapshot, reported, playing)
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
            driveDirectWindow()
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

    private val musicOnlyLookups = HashMap<String, Job>()

    private fun convertVideosAround(exoPlayer: ExoPlayer) {
        if (!MusicOnlyPlayback.isActive()) return
        if (crossfade?.isTransitioning() == true || versionSwapJob?.isActive == true) return
        val rows = listOf(exoPlayer.currentMediaItemIndex, exoPlayer.nextMediaItemIndex)
            .filter { it in 0 until exoPlayer.mediaItemCount }
            .distinct()
        MusicOnlyPlayback.forgetExcept(rows.map { exoPlayer.getMediaItemAt(it).mediaId }.toSet())
        for (index in rows) {
            val item = exoPlayer.getMediaItemAt(index)
            if (!MusicOnlyPlayback.wants(item)) continue
            val video = item.toSong()
            val audio = YtMusicRepository.cachedAudioVersion(video.videoId)
            when {
                audio == null -> lookUpMusicOnly(video)
                audio.videoId == video.videoId || audio.isVideo -> MusicOnlyPlayback.settle(video.videoId)
                else -> {
                    applyMusicOnly(exoPlayer, index, item, video, audio)
                    convertVideosAround(exoPlayer)
                    return
                }
            }
        }
    }

    private fun lookUpMusicOnly(video: Song) {
        if (musicOnlyLookups[video.videoId]?.isActive == true) return
        musicOnlyLookups[video.videoId] = scope.launch {
            val audio = withTimeoutOrNull(MusicOnlyPlayback.ANSWER_TIMEOUT_MS) {
                runCatching { YtMusicRepository.resolveAudio(video) }.getOrNull()
            }
            musicOnlyLookups.remove(video.videoId)
            if (audio == null || audio.videoId == video.videoId) {
                TrackLog.d("nuvx", "music-only: no audio version, playing the video's own audio", about = video.videoId)
                MusicOnlyPlayback.settle(video.videoId)
            }
            val live = player ?: return@launch
            convertVideosAround(live)
            if (live.isPlaying) prefetchAround(live)
        }
    }

    private fun applyMusicOnly(exoPlayer: ExoPlayer, index: Int, item: MediaItem, video: Song, audio: Song) {
        TrackLog.d("nuvx", "music-only: playing '${audio.title}' (${audio.videoId})", about = video.videoId)
        YtMusicRepository.rememberVideoVersion(audio.videoId, video)
        val replacement = QueueShuffle.withQueueMetadata(
            audio.asMusicOnlyOf(video).toMediaItem(),
            newCanonicalIndex = item.canonicalIndex,
            newContextCycleId = item.contextCycleId,
        )
        val isCurrent = index == exoPlayer.currentMediaItemIndex
        if (isCurrent && exoPlayer.isPlaying) {
            smoothSwapCurrentTrackVersion(replacement)
            return
        }
        val position = if (isCurrent) exoPlayer.currentPosition else 0L
        exoPlayer.replaceMediaItem(index, replacement)
        if (position > 0L) {
            val mapped = YtMusicRepository.versionTimeMap(video.videoId, audio.videoId)?.map(position) ?: position
            exoPlayer.seekTo(exoPlayer.currentMediaItemIndex, mapped.coerceAtLeast(0L))
        }
    }

    private var lengthCheckedUri: String? = null

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
            substituted -> abs(decodedMs - expectedMs) > streamResolution.upgradeLengthSlackMs + 1_000L
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

    private var lastSeekAt = 0L

    private fun watchDirectWindow() {
        scope.launch {
            while (isActive) {
                driveDirectWindow()
                delay(DIRECT_WINDOW_STEP_MS)
            }
        }
    }

    private fun driveDirectWindow() {
        val exoPlayer = player ?: return
        checkSubstituteLength(exoPlayer)
        val audible = buildSet {
            exoPlayer.currentMediaItem?.mediaId?.let(::add)
            if (crossfade?.isTransitioning() == true) spare?.currentMediaItem?.mediaId?.let(::add)
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
        val boundary = crossfade?.plannedTransitionFor(current.mediaId) ?: duration
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
                    convertVideosAround(live)
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
        closeAudioEffectSession()
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
        localBitrateCache.clear()
        measuredBitrateCache.clear()
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
                carControllers += controller
                refreshCustomLayouts()
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (carControllers.remove(controller)) refreshCustomLayouts()
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
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val isRecent = params?.isRecent == true ||
                params?.extras?.getBoolean("android.service.media.extra.RECENT") == true ||
                params?.extras?.getBoolean("androidx.media.MediaBrowserCompat.EXTRA_RECENT") == true

            val rootId = if (isRecent) MEDIA_RECENTS_ID else MEDIA_ROOT_ID
            val rootExtras = autoBrowse.buildContentStyleBundle(
                CONTENT_STYLE_GRID_ITEM_HINT_VALUE,
                CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
            )
            val rootItem = MediaItem.Builder()
                .setMediaId(rootId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .setExtras(rootExtras)
                        .build(),
                )
                .build()
            val rootParams = LibraryParams.Builder()
                .setExtras(rootExtras)
                .build()
            scope.launch(Dispatchers.IO) {
                try {
                    autoBrowse.cachedHomeFeed()
                } catch (_: Exception) {}
            }

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val isGridFolder = parentId == MEDIA_ROOT_ID || parentId == MEDIA_PLAYLISTS_ID
            val items: List<MediaItem> = when (parentId) {
                MEDIA_ROOT_ID -> listOf(
                    autoBrowse.createFolderItem(MEDIA_QUICK_PICKS_ID, getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    autoBrowse.createFolderItem(MEDIA_RECENTS_ID, getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    autoBrowse.createFolderItem(MEDIA_PLAYLISTS_ID, getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true),
                    autoBrowse.createFolderItem(MEDIA_LIKED_ID, getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    autoBrowse.createFolderItem(MEDIA_MORE_ID, getString(R.string.more), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                )
                MEDIA_MORE_ID -> listOf(
                    autoBrowse.createFolderItem(MEDIA_DOWNLOADS_ID, getString(R.string.downloads), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                    autoBrowse.createFolderItem(MEDIA_LOCAL_MUSIC_ID, getString(R.string.local_music), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                )
                MEDIA_RECENTS_ID -> {
                    val snapshot = autoBrowse.snapshotRecents()
                    val songs = if (snapshot.isNotEmpty()) {
                        if (!autoBrowse.isRecentsFresh()) {
                            autoBrowse.refreshFolderInBackground(session, browser, parentId, params) {
                                autoBrowse.cachedRecentsSongs().size
                            }
                        }
                        snapshot
                    } else {
                        autoBrowse.cachedRecentsSongs()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_recents",
                            getString(R.string.auto_empty_recents_title),
                            getString(R.string.auto_empty_recents_subtitle),
                        ),
                    )
                }
                MEDIA_QUICK_PICKS_ID -> {
                    val snapshot = autoBrowse.snapshotQuickPicks()
                    val songs = if (snapshot.isNotEmpty()) {
                        if (!autoBrowse.isQuickPicksFresh()) {
                            autoBrowse.refreshFolderInBackground(session, browser, parentId, params) {
                                autoBrowse.cachedQuickPicksSongs().size
                            }
                        }
                        snapshot
                    } else {
                        autoBrowse.cachedQuickPicksSongs().ifEmpty {
                            autoBrowse.snapshotHome()?.shelves?.flatMap { it.items }
                                ?.mapNotNull { it.toSongOrNull() }
                                ?.distinctBy { it.videoId }
                                .orEmpty()
                        }
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_quick_picks",
                            getString(R.string.auto_empty_quick_picks_title),
                            getString(R.string.auto_empty_quick_picks_subtitle),
                        ),
                    )
                }
                MEDIA_LIKED_ID -> {
                    if (com.bxane.nuvx.data.innertube.Innertube.cookie == null) {
                        listOf(createLoginPromptItem())
                    } else {
                        val snapshot = autoBrowse.snapshotLiked()
                        val songs = if (snapshot.isNotEmpty()) {
                            if (!autoBrowse.isLikedFresh()) {
                                autoBrowse.refreshFolderInBackground(session, browser, parentId, params) {
                                    autoBrowse.cachedLikedSongs().size
                                }
                            }
                            snapshot
                        } else {
                            autoBrowse.cachedLikedSongs()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        if (songs.isNotEmpty()) {
                            songs.map { it.toMediaItem() }
                        } else {
                            listOf(
                                createInfoItem(
                                    "msg:empty_liked",
                                    getString(R.string.auto_empty_liked_title),
                                    getString(R.string.auto_empty_liked_subtitle),
                                ),
                            )
                        }
                    }
                }
                MEDIA_DOWNLOADS_ID -> {
                    val downloaded = Downloads.getDownloadedSongs(this@PlaybackService)
                    downloaded.forEach { songCache[it.videoId] = it }
                    if (downloaded.isNotEmpty()) downloaded.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_downloads",
                            getString(R.string.auto_empty_offline_title),
                            getString(R.string.auto_empty_offline_subtitle),
                        ),
                    )
                }
                MEDIA_PLAYLISTS_ID -> {
                    if (com.bxane.nuvx.data.innertube.Innertube.cookie == null) {
                        listOf(createLoginPromptItem())
                    } else {
                        val snapshot = autoBrowse.snapshotPlaylistCards()
                        val playlists = if (snapshot.isNotEmpty()) {
                            if (!autoBrowse.isPlaylistsFresh()) {
                                autoBrowse.refreshFolderInBackground(session, browser, parentId, params) {
                                    autoBrowse.cachedPlaylistCards().size
                                }
                            }
                            snapshot
                        } else {
                            autoBrowse.cachedPlaylistCards()
                        }
                        if (playlists.isNotEmpty()) playlists.map(autoBrowse::shelfToGridPlaylistItem) else listOf(
                            createInfoItem(
                                "msg:empty_playlists",
                                getString(R.string.auto_empty_playlists_title),
                                getString(R.string.auto_empty_playlists_subtitle),
                            ),
                        )
                    }
                }
                MEDIA_LOCAL_MUSIC_ID -> {
                    val local = LocalMediaRepository.getLocalMusic(this@PlaybackService)
                    local.forEach { songCache[it.videoId] = it }
                    if (local.isNotEmpty()) local.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_local",
                            getString(R.string.auto_empty_offline_title),
                            getString(R.string.auto_empty_offline_subtitle),
                        ),
                    )
                }
                else -> {
                    when {
                        parentId.startsWith("playlist:") -> {
                            val playlistId = parentId.removePrefix("playlist:")
                            val songs = try {
                                withTimeoutOrNull(2500L) {
                                    YtMusicRepository.browseSongs("VL$playlistId").getOrNull()?.songs
                                        ?: YtMusicRepository.browseSongs(playlistId).getOrNull()?.songs
                                } ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                            songs.forEach { songCache[it.videoId] = it }
                            songs.map { it.toMediaItem() }
                        }
                        parentId.startsWith("browse:") -> {
                            val browseId = parentId.removePrefix("browse:")
                            val songs = try {
                                withTimeoutOrNull(2500L) {
                                    YtMusicRepository.browseSongs(browseId).getOrNull()?.songs
                                } ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                            songs.forEach { songCache[it.videoId] = it }
                            songs.map { it.toMediaItem() }
                        }
                        else -> {
                            val cachedSong = songCache[parentId]
                            if (cachedSong != null) {
                                listOf(cachedSong.toMediaItem())
                            } else {
                                emptyList()
                            }
                        }
                    }
                }
            }
            val returnParams = LibraryParams.Builder().setExtras(
                autoBrowse.buildContentStyleBundle(
                    if (isGridFolder) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                ),
            ).build()
            LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), returnParams)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            val item = when (mediaId) {
                MEDIA_ROOT_ID -> MediaItem.Builder()
                    .setMediaId(MEDIA_ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(getString(R.string.app_name))
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                            .build(),
                    ).build()
                MEDIA_RECENTS_ID -> autoBrowse.createFolderItem(MEDIA_RECENTS_ID, getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_QUICK_PICKS_ID -> autoBrowse.createFolderItem(MEDIA_QUICK_PICKS_ID, getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_PLAYLISTS_ID -> autoBrowse.createFolderItem(MEDIA_PLAYLISTS_ID, getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true)
                MEDIA_MORE_ID -> autoBrowse.createFolderItem(MEDIA_MORE_ID, getString(R.string.more), folderType = MediaMetadata.FOLDER_TYPE_MIXED)
                MEDIA_LIKED_ID -> autoBrowse.createFolderItem(MEDIA_LIKED_ID, getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_DOWNLOADS_ID -> autoBrowse.createFolderItem(MEDIA_DOWNLOADS_ID, getString(R.string.downloads))
                MEDIA_LOCAL_MUSIC_ID -> autoBrowse.createFolderItem(MEDIA_LOCAL_MUSIC_ID, getString(R.string.local_music))
                "msg:login_required" -> createLoginPromptItem()
                else -> {
                    if (mediaId.startsWith("msg:")) {
                        createInfoItem(
                            mediaId,
                            getString(R.string.auto_unavailable_title),
                            getString(R.string.auto_unavailable_subtitle),
                        )
                    } else {
                        val cached = songCache[mediaId]
                        if (cached != null) {
                            cached.toMediaItem()
                        } else if (mediaId.startsWith("playlist:") || mediaId.startsWith("browse:")) {
                            autoBrowse.createGridPlaylistItem(
                                mediaId = mediaId,
                                title = if (mediaId.contains("MPREb_")) "Album" else getString(R.string.playlists),
                                folderType = if (mediaId.contains("MPREb_")) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                            )
                        } else {
                            createInfoItem(
                                "msg:unavailable|$mediaId",
                                getString(R.string.auto_unavailable_title),
                                getString(R.string.auto_unavailable_subtitle),
                            )
                        }
                    }
                }
            }
            LibraryResult.ofItem(item, null)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val activePlayer = player
            if (activePlayer != null && activePlayer.mediaItemCount > 0) {
                val currentItems = (0 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it) }
                val startIndex = activePlayer.currentMediaItemIndex.coerceIn(0, currentItems.size - 1)
                val startPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(currentItems),
                    startIndex,
                    startPositionMs,
                )
            }

            val downloaded = Downloads.getDownloadedSongs(this@PlaybackService)
            if (downloaded.isNotEmpty()) {
                val items = downloaded.take(BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT).map { it.toMediaItem() }
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(items),
                    0,
                    0L,
                )
            }

            val localSongs = LocalMediaRepository.getLocalMusic(this@PlaybackService)
            if (localSongs.isNotEmpty()) {
                val items = localSongs.take(BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT).map { it.toMediaItem() }
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(items),
                    0,
                    0L,
                )
            }

            val fallbackSongs = try {
                withTimeoutOrNull(2500L) {
                    val home = YtMusicRepository.home().getOrNull()
                    home?.shelves?.flatMap { shelf ->
                        shelf.items.mapNotNull { it.toMediaItemOrNull() }
                    }?.distinctBy { it.mediaId }
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            if (fallbackSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(fallbackSongs),
                    0,
                    0L,
                )
            }

            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.of(),
                0,
                0L,
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
            val (songs, browses) = searchAll(query)
            storeSearchResults(query, songs, browses)
            session.notifySearchResultChanged(browser, query, songs.size + browses.size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val cachedSongs = searchResults[query]
            val cachedBrowses = searchBrowseResults[query]
            val (songs, browses) = if (cachedSongs != null || cachedBrowses != null) {
                cachedSongs.orEmpty() to cachedBrowses.orEmpty()
            } else {
                searchAll(query).also { storeSearchResults(query, it.first, it.second) }
            }
            songs.forEach { songCache[it.videoId] = it }
            val items = songs.map { it.toMediaItem() } + browses.map(autoBrowse::browseItemToGridItem)
            LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val resolved = resolveMediaItems(mediaItems)
            val validIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(resolved),
                validIndex,
                startPositionMs.coerceAtLeast(0L),
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            resolveMediaItems(mediaItems)
        }

        private suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
            val resolved = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val id = item.mediaId
                val searchQuery = item.requestMetadata.searchQuery
                when {
                    !searchQuery.isNullOrBlank() -> {
                        val cached = searchResults[searchQuery]
                        val songs = if (!cached.isNullOrEmpty()) {
                            cached
                        } else {
                            try {
                                withTimeoutOrNull(3000L) {
                                    YtMusicRepository.search(searchQuery, SearchFilter.SONGS).getOrNull()
                                        ?.mapNotNull { if (it is SearchResult.Track) it.song else null }
                                } ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        if (songs.isNotEmpty()) {
                            resolved.addAll(songs.map { it.toMediaItem() })
                        }
                    }
                    id.startsWith("playlist:") -> {
                        val playlistId = id.removePrefix("playlist:")
                        val songs = try {
                            withTimeoutOrNull(3000L) {
                                YtMusicRepository.browseSongs("VL$playlistId").getOrNull()?.songs
                                    ?: YtMusicRepository.browseSongs(playlistId).getOrNull()?.songs
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id.startsWith("browse:") -> {
                        val browseId = id.removePrefix("browse:")
                        val songs = try {
                            withTimeoutOrNull(3000L) {
                                YtMusicRepository.browseSongs(browseId).getOrNull()?.songs
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id == MEDIA_ROOT_ID || id == MEDIA_RECENTS_ID -> {
                        val songs = autoBrowse.cachedRecentsSongs()
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id == MEDIA_QUICK_PICKS_ID -> {
                        val songs = autoBrowse.cachedQuickPicksSongs()
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id == MEDIA_LIKED_ID -> {
                        val songs = autoBrowse.cachedLikedSongs()
                        if (songs.isNotEmpty()) {
                            resolved.addAll(songs.map { it.toMediaItem() })
                        }
                    }
                    id == MEDIA_DOWNLOADS_ID -> {
                        val downloaded = Downloads.getDownloadedSongs(this@PlaybackService)
                        downloaded.forEach { songCache[it.videoId] = it }
                        resolved.addAll(downloaded.map { it.toMediaItem() })
                    }
                    id == MEDIA_PLAYLISTS_ID -> {
                        val songs = try {
                            withTimeoutOrNull(2500L) {
                                val playlists = YtMusicRepository.userPlaylists().getOrNull() ?: emptyList()
                                val first = playlists.firstOrNull()
                                if (first != null) {
                                    YtMusicRepository.browseSongs("VL${first.playlistId}").getOrNull()?.songs
                                        ?: YtMusicRepository.browseSongs(first.playlistId).getOrNull()?.songs
                                } else null
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                    id == MEDIA_LOCAL_MUSIC_ID -> {
                        val local = LocalMediaRepository.getLocalMusic(this@PlaybackService)
                        local.forEach { songCache[it.videoId] = it }
                        resolved.addAll(local.map { it.toMediaItem() })
                    }
                    songCache.containsKey(id) -> {
                        resolved.add(songCache[id]!!.toMediaItem())
                    }
                    item.localConfiguration != null -> {
                        resolved.add(item)
                    }
                    item.requestMetadata.mediaUri != null -> {
                        val uri = item.requestMetadata.mediaUri!!
                        val videoId = uri.getQueryParameter("v") ?: uri.lastPathSegment.orEmpty()
                        if (videoId.isNotEmpty() && !videoId.startsWith("http")) {
                            val cached = songCache[videoId]
                            if (cached != null) {
                                resolved.add(cached.toMediaItem())
                            } else {
                                resolved.add(Song(videoId = videoId, title = item.mediaMetadata.title?.toString() ?: "Track", artist = item.mediaMetadata.artist?.toString() ?: "Artist", thumbnailUrl = null).toMediaItem())
                            }
                        } else {
                            resolved.add(item.buildUpon().setUri(uri).build())
                        }
                    }
                    else -> {
                        val cached = songCache[id]
                        if (cached != null) {
                            resolved.add(cached.toMediaItem())
                        } else {
                            val song = item.toSong()
                            resolved.add(song.toMediaItem())
                        }
                    }
                }
            }
            return if (resolved.isEmpty()) mediaItems else resolved
        }
    }

    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val safePageSize = pageSize.coerceAtMost(MAX_AUTO_PAGE_SIZE)
        val fromLong = page.toLong() * safePageSize.toLong()
        if (fromLong >= items.size.toLong()) return emptyList()
        val from = fromLong.toInt()
        val to = (fromLong + safePageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, to)
    }

    private suspend fun searchAll(query: String): Pair<List<Song>, List<BrowseItem>> {
        val safeQuery = query.trim().take(MAX_AUTO_SEARCH_QUERY_CHARS)
        if (safeQuery.isEmpty()) return emptyList<Song>() to emptyList()
        val results = try {
            withTimeoutOrNull(3_500L) {
                YtMusicRepository.search(safeQuery, SearchFilter.ALL).getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val songs = results.mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }.distinctBy { it.videoId }
        val browses = results.mapNotNull { (it as? SearchResult.Browse)?.item }
            .distinctBy { it.browseId }
        return songs to browses
    }

    private fun storeSearchResults(query: String, songs: List<Song>, browses: List<BrowseItem>) {
        while (searchResults.size >= MAX_AUTO_SEARCH_CACHE_ENTRIES) {
            val key = searchResults.keys.firstOrNull() ?: break
            searchResults.remove(key)
            searchBrowseResults.remove(key)
        }
        searchResults[query] = songs
        searchBrowseResults[query] = browses
        songs.forEach { songCache[it.videoId] = it }
    }

    private fun ShelfItem.toMediaItemOrNull(): MediaItem? {
        val vid = videoId
        if (vid != null) {
            val song = Song(
                videoId = vid,
                title = title,
                artist = InnertubeParser.artistFromSubtitle(subtitle),
                thumbnailUrl = thumbnailUrl,
            )
            songCache[vid] = song
            return song.toMediaItem()
        }
        val bid = browseId
        if (bid != null) {
            return MediaItem.Builder()
                .setMediaId("browse:$bid")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setArtworkUri(thumbnailUrl.artworkAt(NOTIFICATION_ART_PX)?.let { Uri.parse(it) })
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                        .build(),
                )
                .build()
        }
        return null
    }

    private fun ShelfItem.toSongOrNull(): Song? {
        val id = videoId ?: return null
        return Song(
            videoId = id,
            title = title,
            artist = InnertubeParser.artistFromSubtitle(subtitle),
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun createLoginPromptItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId("msg:login_required")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(R.string.auto_login_title))
                    .setSubtitle(getString(R.string.auto_login_subtitle))
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    private fun createInfoItem(mediaId: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setExtras(autoBrowse.buildContentStyleBundle(CONTENT_STYLE_LIST_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()

    private companion object {
        const val MEDIA_ROOT_ID = "root"
        const val MEDIA_RECENTS_ID = "recents"
        const val MEDIA_QUICK_PICKS_ID = "quick_picks"
        const val MEDIA_PLAYLISTS_ID = "playlists"
        const val MEDIA_MORE_ID = "more"
        const val MEDIA_LIKED_ID = "liked"
        const val FOREIGN_CACHE_PURGE_DELAY_MS = 30_000L
        const val FOREIGN_CACHE_KEEP_AHEAD = 3
        const val MEDIA_DOWNLOADS_ID = "downloads"
        const val MEDIA_LOCAL_MUSIC_ID = "local_music"
        const val MAX_AUTO_PAGE_SIZE = 50
        const val MAX_AUTO_SEARCH_QUERY_CHARS = 200
        const val MAX_AUTO_SEARCH_CACHE_ENTRIES = 20

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

        const val DIRECT_WINDOW_STEP_MS = 500L

        const val SEEK_SETTLE_MS = 2_000L

        const val MIN_WINDOW_SPEED = 0.25f

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

        const val AUTOPLAY_EMPTY_REFRESH_DELAY_MS = 2_000L

        const val MAX_AUTOPLAY_EMPTY_REFRESHES = 1

        const val AUTOPLAY_WATCHDOG_MS = 15_000L

        const val PARTY_QUEUE_ECHO_TIMEOUT_MS = 5_000L
    }
}

private open class FfmpegFallbackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            withoutPlatformAlac(mediaCodecSelector),
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}

private fun withoutPlatformAlac(selector: MediaCodecSelector) =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        if (mimeType == MimeTypes.AUDIO_ALAC && ffmpegDecodesAlac) {
            emptyList()
        } else {
            selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        }
    }

private val ffmpegDecodesAlac by lazy { FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_ALAC) }
