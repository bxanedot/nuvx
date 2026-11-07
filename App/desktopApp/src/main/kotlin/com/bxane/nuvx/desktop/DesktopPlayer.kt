package com.bxane.nuvx.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.bxane.nuvx.core.*
import com.sun.jna.NativeLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

internal fun Track.localPlaybackUri(): String? {
    if (!isLocal) {
        require(localUri == null) { "Remote tracks cannot use local media URIs" }
        return null
    }
    val uri = requireNotNull(localUri) { "Local track URI is missing" }
    val parsed = URI(uri)
    require(parsed.scheme == "file" && Files.isRegularFile(Path.of(parsed))) { "Local track file is unavailable" }
    return uri
}

internal class DesktopPlaybackStart(positionMs: Long, var autoplay: Boolean) {
    var positionMs = positionMs.coerceAtLeast(0)
        set(value) { field = value.coerceAtLeast(0) }
    private var appliedPositionMs: Long? = null
    private var requestedPlaying: Boolean? = null

    fun apply(playback: State, seek: (Long) -> Unit, resume: () -> Unit, pause: () -> Unit): Boolean {
        if (playback != State.PAUSED && playback != State.PLAYING) return false
        if (appliedPositionMs == null && playback != State.PAUSED) return false
        if (appliedPositionMs != positionMs) {
            seek(positionMs)
            appliedPositionMs = positionMs
        }
        val playing = playback == State.PLAYING
        if (requestedPlaying == null) requestedPlaying = playing
        if (requestedPlaying != autoplay) {
            if (autoplay) resume() else pause()
            requestedPlaying = autoplay
            return false
        }
        return playing == autoplay
    }
}

internal suspend fun loadDesktopPlayback(
    state: MutableStateFlow<PlayerState>,
    clearPendingStart: () -> Unit,
    load: suspend () -> Unit,
) {
    val track = state.value.current ?: return
    try {
        load()
    } catch (e: Exception) {
        // A resolver deadline fails this load only while its caller is still active.
        currentCoroutineContext().ensureActive()
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        clearPendingStart()
        state.value = state.value.copy(buffering = false, error = track.playbackFailureMessage(), audioFormat = null, videoResolution = null)
    }
}

internal fun PlayerState.playbackErrorFor(playback: State): String? {
    val track = current ?: return error
    return when {
        playback == State.ERROR -> track.playbackFailureMessage()
        // Treat a known-duration early end as an incomplete play.
        playback == State.ENDED && durationMs > 0 && durationMs - positionMs > 1_000 -> track.playbackFailureMessage()
        // The poller can miss ENDED by a tick; don't report a normal completion as failure.
        playback == State.STOPPED && isPlaying && !buffering &&
            (durationMs <= 0 || durationMs - positionMs > 1_000) -> track.playbackFailureMessage()
        else -> error
    }
}

internal class DesktopPlayer(private val router: BackendRouter) : NuvxPlayer, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val mutableState = MutableStateFlow(PlayerState())
    override val state = mutableState.asStateFlow()
    private var proxy = StreamProxy()
    private var standbyProxy = StreamProxy()
    private val factory: MediaPlayerFactory
    @Volatile private var native: EmbeddedMediaPlayer
    @Volatile private lateinit var standby: EmbeddedMediaPlayer
    private var loading: Job? = null
    private var standbyLoading: Job? = null
    private var crossfade: Job? = null
    private var standbyFor: String? = null
    private var standbyFormat: AudioFormatInfo? = null
    private var failedCrossfadeFor: String? = null
    private var crossfadeSeconds = 0
    private var crossfadeProgress = 0f
    private var pendingStart: DesktopPlaybackStart? = null
    private var requestedPlaybackSpeed = PlaybackSpeed.DEFAULT
    private var appliedPlaybackSpeed: Float? = null
    private var restoringVolume = false
    @Volatile private var preferredAudioOutput = DesktopAudioOutputSelection.DEFAULT
    @Volatile private var videoVisible = false
    private val mutableFrame = MutableStateFlow<ImageBitmap?>(null)
    val frame = mutableFrame.asStateFlow()

    fun availableAudioOutputs(): List<DesktopAudioOutputOption> = runCatching {
        val options = factory.audio().audioOutputs().flatMap { output ->
            output.devices.mapNotNull { device ->
                val id = device.deviceId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = output.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val label = "${device.longName?.takeIf(String::isNotBlank) ?: id} · ${output.description?.takeIf(String::isNotBlank) ?: name}"
                DesktopAudioOutputOption(name, id, label)
            }
        }.distinctBy { it.outputName to it.deviceId }
        val duplicateLabels = options.groupingBy(DesktopAudioOutputOption::label).eachCount()
        options.map { option ->
            if (duplicateLabels[option.label] == 1) option else option.copy(label = "${option.label} · ${option.outputName}")
        }
    }.getOrDefault(emptyList())

    fun setPreferredAudioOutput(selection: DesktopAudioOutputSelection) {
        val next = if (selection.isSystemDefault || availableAudioOutputs().any { it.selection == selection }) {
            selection
        } else DesktopAudioOutputSelection.DEFAULT
        val previous = preferredAudioOutput
        if (next == previous) return
        preferredAudioOutput = next
        scope.launch {
            loading?.cancelAndJoin()
            if (preferredAudioOutput != next) return@launch
            val snapshot = state.value
            val position = pendingStart?.positionMs ?: snapshot.positionMs
            val autoplay = pendingStart?.autoplay ?: snapshot.isPlaying
            clearStandby()
            val oldPlayer = native
            val replacement = runCatching { createNativePlayer().also { applyPreferredAudioOutput(it, next) } }.getOrNull()
            if (replacement == null) {
                preferredAudioOutput = previous
                return@launch
            }
            restoringVolume = true
            oldPlayer.controls().stop()
            native = replacement
            oldPlayer.release()
            applyPreferredAudioOutput(standby, next)
            if (snapshot.current != null) load(position, autoplay)
        }
    }

    private fun applyPreferredAudioOutput(target: EmbeddedMediaPlayer, selection: DesktopAudioOutputSelection) {
        if (selection.isSystemDefault) return
        runCatching {
            target.audio().setOutputDevice(selection.outputName, selection.deviceId)
            if (!target.audio().setOutput(selection.outputName)) preferredAudioOutput = DesktopAudioOutputSelection.DEFAULT
        }.onFailure { preferredAudioOutput = DesktopAudioOutputSelection.DEFAULT }
    }

    private fun createNativePlayer(): EmbeddedMediaPlayer {
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        val surface = factory.videoSurfaces().newVideoSurface(object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat = RV32BufferFormat(sourceWidth, sourceHeight)
            override fun newFormatSize(width: Int, height: Int, visibleWidth: Int, visibleHeight: Int) = Unit
            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
        }, object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun unlock(mediaPlayer: MediaPlayer) = Unit
            override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, format: BufferFormat, displayWidth: Int, displayHeight: Int) {
                if (videoVisible && nativeBuffers.isNotEmpty()) {
                    runCatching { rv32Image(nativeBuffers[0], format.width, format.height, format.pitches[0]).toComposeImageBitmap() }
                        .onSuccess { mutableFrame.value = it }
                }
            }
        }, true)
        player.videoSurface().set(surface)
        return player
    }

    init {
        val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
        val home = System.getenv("NUVX_VLC_HOME")?.let(::File) ?: bundled
        if (home?.isDirectory == true) NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), home.absolutePath)
        try {
            factory = MediaPlayerFactory("--quiet", "--no-metadata-network-access")
            native = createNativePlayer()
            standby = createNativePlayer()
        } catch (e: Throwable) { proxy.close(); standbyProxy.close(); scope.cancel(); throw e }
        scope.launch {
            while (isActive) {
                if (loading?.isActive != true && state.value.current != null) {
                    val playback = native.status().state()
                    if (playback == State.ERROR) pendingStart = null
                    if (native.status().isPlaying && appliedPlaybackSpeed != requestedPlaybackSpeed) {
                        native.controls().setRate(requestedPlaybackSpeed)
                        appliedPlaybackSpeed = requestedPlaybackSpeed
                    }
                    if (native.status().isPlaying && restoringVolume && native.audio().setVolume((state.value.volume * 100).toInt())) {
                        restoringVolume = false
                    }
                    pendingStart?.let { start ->
                        if (start.apply(playback, { native.controls().setTime(it) }, { native.controls().play() }, { native.controls().setPause(true) })) pendingStart = null
                    }
                    val starting = pendingStart != null
                    mutableState.value = state.value.copy(isPlaying = !starting && native.status().isPlaying,
                        positionMs = pendingStart?.positionMs ?: native.status().time().coerceAtLeast(0), durationMs = native.status().length().coerceAtLeast(0),
                        buffering = starting || playback == State.BUFFERING || playback == State.OPENING,
                        error = state.value.playbackErrorFor(playback),
                        nativePlaybackState = playback.name,
                        videoResolution = currentVideoResolution())
                    if (playback == State.ENDED && crossfade?.isActive != true) {
                        clearStandby()
                        state.value.afterEnd()?.let { if (it == state.value.index) load() else skipTo(it) }
                    } else {
                        maybeStartCrossfade()
                        prepareStandby()
                    }
                }
                delay(200)
            }
        }
    }

    override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        mutableState.value = PlayerState(tracks, startIndex, playbackSpeed = requestedPlaybackSpeed)
        load(initialPositionMs, autoplay)
    }
    override fun replaceCurrent(track: Track, positionMs: Long) {
        val state = state.value
        if (state.current?.videoId != track.videoId) return
        mutableState.value = state.copy(queue = state.queue.toMutableList().also { it[state.index] = track }, error = null)
        load(positionMs.coerceAtLeast(0), pendingStart?.autoplay ?: state.isPlaying)
    }
    private fun load(positionMs: Long = 0, autoplay: Boolean = true) {
        loading?.cancel()
        clearStandby()
        native.controls().stop()
        appliedPlaybackSpeed = null
        proxy.clear()
        val track = state.value.current ?: return
        pendingStart = DesktopPlaybackStart(positionMs, autoplay)
        mutableState.value = state.value.copy(isPlaying = false, buffering = true, positionMs = positionMs.coerceAtLeast(0), durationMs = 0,
            error = null, audioFormat = null, videoResolution = null, nativePlaybackState = null)
        loading = scope.launch {
            loadDesktopPlayback(mutableState, { pendingStart = null }) {
                val localUri = track.localPlaybackUri()
                if (localUri != null) {
                    ensureActive()
                    check(native.media().play(localUri, "start-paused"))
                } else {
                    if (track.mediaKind == MediaKind.VIDEO) {
                        val stream = withContext(Dispatchers.IO) { withTimeout(45_000) { router.resolveVideo(track.playbackVideoId) } }
                        ensureActive()
                        mutableState.value = state.value.copy(audioFormat = AudioFormatInfo(mimeType = stream.audioMimeType))
                        val (picture, audio) = proxy.registerVideo(stream)
                        check(native.media().play(picture, "start-paused", ":input-slave=$audio"))
                    } else {
                        val stream = withContext(Dispatchers.IO) { withTimeout(45_000) { router.resolveAudio(track.playbackVideoId) } }
                        ensureActive()
                        mutableState.value = state.value.copy(audioFormat = AudioFormatInfo(stream.mimeType, stream.codecs, stream.bitrateKbps, stream.sampleRateHz))
                        check(native.media().play(proxy.register(stream), "start-paused"))
                    }
                }
                mutableState.value = state.value.copy(buffering = true)
            }
        }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val next = CrossfadeAudio.normalizeDurationSeconds(seconds)
        if (next == crossfadeSeconds) return
        crossfadeSeconds = next
        failedCrossfadeFor = null
        clearStandby()
    }

    private fun prepareStandby() {
        if (crossfadeSeconds == 0 || crossfade?.isActive == true || standbyLoading?.isActive == true) return
        val snapshot = state.value
        val current = snapshot.current ?: return
        val nextIndex = snapshot.afterEnd()
        val next = nextIndex?.let(snapshot.queue::getOrNull)
        if (current.mediaKind == MediaKind.VIDEO || nextIndex == null || nextIndex == snapshot.index || next == null || next.mediaKind == MediaKind.VIDEO) {
            if (standbyFor != null) clearStandby()
            return
        }
        val key = "$nextIndex:${next.videoId}"
        if (failedCrossfadeFor == key) return
        if (standbyFor == key) return

        clearStandby()
        standbyFor = key
        standbyLoading = scope.launch {
            try {
                standby.audio().setVolume(0)
                val localUri = next.localPlaybackUri()
                val format = if (localUri != null) {
                    check(standby.media().play(localUri))
                    null
                } else {
                    val stream = withContext(Dispatchers.IO) { withTimeout(45_000) { router.resolveAudio(next.playbackVideoId) } }
                    ensureActive()
                    check(standby.media().play(standbyProxy.register(stream)))
                    AudioFormatInfo(stream.mimeType, stream.codecs, stream.bitrateKbps, stream.sampleRateHz)
                }
                withTimeout(30_000) {
                    while (!standby.status().isPlaying) {
                        ensureActive()
                        check(standby.status().state() != State.ERROR)
                        delay(100)
                    }
                }
                standby.controls().pause()
                standby.controls().setTime(0)
                standbyFormat = format
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                runCatching { standby.controls().stop() }
                standbyProxy.clear()
            }
        }
    }

    private fun maybeStartCrossfade() {
        if (crossfadeSeconds == 0 || crossfade?.isActive == true || standbyLoading?.isActive == true) return
        val snapshot = state.value
        val current = snapshot.current ?: return
        if (!snapshot.isPlaying || snapshot.buffering || current.mediaKind == MediaKind.VIDEO) return
        val nextIndex = snapshot.afterEnd() ?: return
        if (nextIndex == snapshot.index) return
        val next = snapshot.queue.getOrNull(nextIndex) ?: return
        if (next.mediaKind == MediaKind.VIDEO || standbyFor != "$nextIndex:${next.videoId}" || standby.status().state() != State.PAUSED) return

        val duration = CrossfadeAudio.transitionDurationMs(crossfadeSeconds, snapshot.durationMs, standby.status().length())
        if (duration == 0L || snapshot.durationMs - snapshot.positionMs > duration) return

        val outgoing = native
        val incoming = standby
        val outgoingProxy = proxy
        proxy = standbyProxy
        standbyProxy = outgoingProxy
        native = incoming
        standby = outgoing
        crossfadeProgress = 0f
        mutableState.value = snapshot.copy(index = nextIndex, isPlaying = true, buffering = false, positionMs = 0,
            durationMs = incoming.status().length().coerceAtLeast(0), error = null, audioFormat = standbyFormat, videoResolution = null)
        incoming.controls().setRate(requestedPlaybackSpeed)
        appliedPlaybackSpeed = null
        incoming.controls().setTime(0)
        incoming.controls().play()
        applyCrossfadeVolumes(0f)

        crossfade = scope.launch {
            var elapsedNanos = 0L
            var previousTick = System.nanoTime()
            val durationNanos = duration * 1_000_000L
            try {
                while (elapsedNanos < durationNanos) {
                    val incomingState = native.status().state()
                    val outgoingState = standby.status().state()
                    if (isCrossfadeDeckTerminal(incomingState)) {
                        if (outgoingState == State.PLAYING) {
                            failedCrossfadeFor = standbyFor
                            val failedPlayer = native
                            native = standby
                            standby = failedPlayer
                            val failedProxy = proxy
                            proxy = standbyProxy
                            standbyProxy = failedProxy
                            val restored = native.status()
                            mutableState.value = snapshot.copy(
                                isPlaying = restored.isPlaying,
                                buffering = false,
                                positionMs = restored.time().coerceAtLeast(0),
                                durationMs = restored.length().coerceAtLeast(0),
                                error = null,
                                audioFormat = snapshot.audioFormat,
                                videoResolution = null,
                            )
                        }
                        break
                    }
                    if (isCrossfadeDeckTerminal(outgoingState)) break
                    delay(40)
                    val now = System.nanoTime()
                    if (native.status().isPlaying && standby.status().isPlaying) elapsedNanos += now - previousTick
                    previousTick = now
                    crossfadeProgress = (elapsedNanos.toDouble() / durationNanos).toFloat().coerceIn(0f, 1f)
                    applyCrossfadeVolumes(crossfadeProgress)
                }
                if (!isCrossfadeDeckTerminal(native.status().state())) {
                    crossfadeProgress = 1f
                    applyCrossfadeVolumes(1f)
                }
            } finally {
                runCatching { standby.controls().stop() }
                standbyProxy.clear()
                standby.audio().setVolume(0)
                standbyFor = null
                standbyFormat = null
                crossfadeProgress = 0f
                native.audio().setVolume((state.value.volume * 100).roundToInt())
                crossfade = null
            }
        }
    }

    private fun isCrossfadeDeckTerminal(state: State): Boolean =
        state == State.STOPPED || state == State.ENDED || state == State.ERROR

    private fun applyCrossfadeVolumes(progress: Float) {
        val levels = CrossfadeAudio.levels(progress)
        val volume = state.value.volume
        native.audio().setVolume((volume * levels.incoming * 100).roundToInt())
        standby.audio().setVolume((volume * levels.outgoing * 100).roundToInt())
    }

    private fun clearStandby() {
        standbyLoading?.cancel()
        standbyLoading = null
        crossfade?.cancel()
        crossfade = null
        runCatching { standby.controls().stop() }
        runCatching { standby.audio().setVolume(0) }
        standbyProxy.clear()
        standbyFor = null
        standbyFormat = null
        crossfadeProgress = 0f
        if (state.value.current != null) runCatching { native.audio().setVolume((state.value.volume * 100).roundToInt()) }
    }

    private fun currentVideoResolution(): VideoResolution? {
        if (state.value.current?.mediaKind != MediaKind.VIDEO) return null
        val size = runCatching { native.video().videoDimension() }.getOrNull() ?: return null
        return VideoResolution(size.width, size.height).takeIf { it.width > 0 && it.height > 0 }
    }
    override fun enqueue(tracks: List<Track>) { clearStandby(); mutableState.value = state.value.copy(queue = state.value.queue + tracks) }
    override fun syncQueue(current: Track, upNext: List<Track>) {
        val next = state.value.syncingQueue(current, upNext) ?: return
        clearStandby()
        mutableState.value = next
    }
    override fun playNext(tracks: List<Track>) {
        val s = state.value
        if (s.queue.isEmpty()) return playQueue(tracks, 0)
        clearStandby()
        mutableState.value = s.copy(queue = s.queue.take(s.index + 1) + tracks + s.queue.drop(s.index + 1))
    }
    override fun move(from: Int, to: Int) { val next = state.value.moving(from, to) ?: return; clearStandby(); mutableState.value = next }
    override fun remove(index: Int) {
        val (next, reload) = state.value.removing(index) ?: return
        clearStandby()
        mutableState.value = next
        if (reload) { if (next.current == null) native.controls().stop() else load() }
    }
    override fun togglePlay() {
        val start = pendingStart
        if (start != null) start.autoplay = !start.autoplay
        else if (state.value.error != null || native.status().state() == State.ENDED) load()
        else if (crossfade?.isActive == true) {
            if (state.value.isPlaying) { native.controls().pause(); standby.controls().pause() }
            else { native.controls().play(); standby.controls().play() }
        } else if (state.value.isPlaying) native.controls().pause() else native.controls().play()
    }
    override fun seekTo(positionMs: Long) {
        clearStandby()
        val position = positionMs.coerceAtLeast(0)
        val start = pendingStart
        if (start != null) {
            start.positionMs = position
            mutableState.value = state.value.copy(positionMs = position)
        } else native.controls().setTime(position)
    }
    override fun next() { val s = state.value; skipTo(if (s.repeat == RepeatMode.ALL && s.index == s.queue.lastIndex) 0 else s.index + 1) }
    override fun setRepeat(mode: RepeatMode) { clearStandby(); mutableState.value = state.value.copy(repeat = mode) }
    override fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        mutableState.value = state.value.copy(volume = v)
        if (crossfade?.isActive == true) applyCrossfadeVolumes(crossfadeProgress)
        else native.audio().setVolume((v * 100).roundToInt())
    }
    override fun setPlaybackSpeed(speed: Float) {
        requestedPlaybackSpeed = PlaybackSpeed.options.firstOrNull { it == speed } ?: PlaybackSpeed.DEFAULT
        if (crossfade?.isActive == true) {
            native.controls().setRate(requestedPlaybackSpeed)
            standby.controls().setRate(requestedPlaybackSpeed)
            appliedPlaybackSpeed = requestedPlaybackSpeed
        } else appliedPlaybackSpeed = null
        mutableState.value = state.value.copy(playbackSpeed = requestedPlaybackSpeed)
    }
    override fun shuffleUpNext() { clearStandby(); mutableState.value = state.value.shufflingUpNext() }
    override fun previous() { if (state.value.positionMs > 3000) seekTo(0) else skipTo((state.value.index - 1).coerceAtLeast(0)) }
    override fun skipTo(index: Int) { if (index in state.value.queue.indices) { mutableState.value = state.value.copy(index = index); load() } }
    fun setVideoVisible(visible: Boolean) { videoVisible = visible; if (!visible) mutableFrame.value = null }
    override fun close() {
        videoVisible = false
        clearStandby()
        scope.cancel()
        native.controls().stop()
        native.release()
        standby.release()
        factory.release()
        proxy.close()
        standbyProxy.close()
    }
}
