package com.bxane.nuvx.desktop

import com.bxane.nuvx.core.*
import com.sun.jna.NativeLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.player.base.State
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

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

internal class DesktopPlayer(private val router: BackendRouter) : NuvxPlayer, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val mutableState = MutableStateFlow(PlayerState())
    override val state = mutableState.asStateFlow()
    private val proxy = StreamProxy()
    private val factory: MediaPlayerFactory
    private val native: uk.co.caprica.vlcj.player.base.MediaPlayer
    private var loading: Job? = null

    init {
        val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
        val home = System.getenv("NUVX_VLC_HOME")?.let(::File) ?: bundled
        if (home?.isDirectory == true) NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), home.absolutePath)
        try {
            factory = MediaPlayerFactory("--no-video", "--quiet", "--no-metadata-network-access")
            native = factory.mediaPlayers().newMediaPlayer()
        } catch (e: Throwable) { proxy.close(); scope.cancel(); throw e }
        scope.launch {
            while (isActive) {
                if (loading?.isActive != true && state.value.current != null) {
                    val playback = native.status().state()
                    mutableState.value = state.value.copy(isPlaying = native.status().isPlaying,
                        positionMs = native.status().time().coerceAtLeast(0), durationMs = native.status().length().coerceAtLeast(0),
                        buffering = playback == State.BUFFERING || playback == State.OPENING,
                        error = if (playback == State.ERROR) "Playback failed. Retry or choose another engine." else state.value.error)
                    if (playback == State.ENDED) state.value.afterEnd()?.let { if (it == state.value.index) load() else skipTo(it) }
                }
                delay(500)
            }
        }
    }

    override fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        mutableState.value = PlayerState(tracks, startIndex)
        load()
    }
    private fun load() {
        loading?.cancel()
        native.controls().stop()
        val track = state.value.current ?: return
        mutableState.value = state.value.copy(isPlaying = false, buffering = true, positionMs = 0, durationMs = 0, error = null)
        loading = scope.launch {
            try {
                val localUri = track.localPlaybackUri()
                if (localUri != null) {
                    ensureActive()
                    check(native.media().play(localUri))
                } else {
                    val stream = withContext(Dispatchers.IO) { withTimeout(45_000) { router.resolveAudio(track.videoId) } }
                    ensureActive()
                    check(native.media().play(proxy.register(stream)))
                }
                mutableState.value = state.value.copy(buffering = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.value = state.value.copy(buffering = false, error = "Could not play this track. Check your connection or streaming engine.") }
        }
    }
    override fun enqueue(tracks: List<Track>) { mutableState.value = state.value.copy(queue = state.value.queue + tracks) }
    override fun playNext(tracks: List<Track>) {
        val s = state.value
        if (s.queue.isEmpty()) return playQueue(tracks, 0)
        mutableState.value = s.copy(queue = s.queue.take(s.index + 1) + tracks + s.queue.drop(s.index + 1))
    }
    override fun move(from: Int, to: Int) { mutableState.value = state.value.moving(from, to) ?: return }
    override fun remove(index: Int) {
        val (next, reload) = state.value.removing(index) ?: return
        mutableState.value = next
        if (reload) { if (next.current == null) native.controls().stop() else load() }
    }
    override fun togglePlay() {
        if (state.value.error != null || native.status().state() == State.ENDED) load()
        else native.controls().pause()
    }
    override fun seekTo(positionMs: Long) { native.controls().setTime(positionMs.coerceAtLeast(0)) }
    override fun next() { val s = state.value; skipTo(if (s.repeat == RepeatMode.ALL && s.index == s.queue.lastIndex) 0 else s.index + 1) }
    override fun setRepeat(mode: RepeatMode) { mutableState.value = state.value.copy(repeat = mode) }
    override fun shuffleUpNext() { mutableState.value = state.value.shufflingUpNext() }
    override fun previous() { if (state.value.positionMs > 3000) seekTo(0) else skipTo((state.value.index - 1).coerceAtLeast(0)) }
    override fun skipTo(index: Int) { if (index in state.value.queue.indices) { mutableState.value = state.value.copy(index = index); load() } }
    override fun close() { scope.cancel(); native.controls().stop(); native.release(); factory.release(); proxy.close() }
}
