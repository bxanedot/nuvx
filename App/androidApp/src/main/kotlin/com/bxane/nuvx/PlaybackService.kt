@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bxane.nuvx

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bxane.nuvx.core.resolveAudio
import com.bxane.nuvx.core.VideoStream
import com.bxane.nuvx.core.CrossfadeSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun isLocalPlaybackScheme(scheme: String): Boolean = scheme == "content" || scheme == "file"

internal fun shouldCachePlaybackScheme(scheme: String): Boolean = scheme == "http" || scheme == "https"

private class CacheRoutingDataSource(
    private val directFactory: DataSource.Factory,
    private val cachedFactory: DataSource.Factory,
) : DataSource {
    private var delegate: DataSource? = null
    private val transferListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val factory = when {
            isLocalPlaybackScheme(dataSpec.uri.scheme.orEmpty()) -> directFactory
            shouldCachePlaybackScheme(dataSpec.uri.scheme.orEmpty()) -> cachedFactory
            else -> throw IOException("Unsupported media request")
        }
        val source = factory.createDataSource()
        delegate = source
        transferListeners.forEach(source::addTransferListener)
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireDelegate().read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    private fun requireDelegate(): DataSource = delegate ?: throw IOException("Media source is not open")
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var standbyPlayer: ExoPlayer? = null
    private var crossfadePlayer: AndroidCrossfadePlayer? = null
    private var crossfadePreferences: SharedPreferences? = null
    private var cache: androidx.media3.datasource.cache.SimpleCache? = null
    private val videoLock = Any()
    private val videoStreams = VideoStreamCache()
    private var currentAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    private val audioSessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) = updateAudioEffectSession()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateAudioEffectSession()
        override fun onPlaybackStateChanged(playbackState: Int) = updateAudioEffectSession()
    }
    private val crossfadePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CrossfadeSettings.STORAGE_KEY) {
            crossfadePlayer?.setCrossfadeSeconds(CrossfadeSettings.read((application as NuvxApplication).store))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val router = (application as NuvxApplication).router
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val playbackCache = androidx.media3.datasource.cache.SimpleCache(
            File(cacheDir, "nuvx-playback-cache"),
            LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(this),
        )
        cache = playbackCache
        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(client))
        val cached = CacheDataSource.Factory().setCache(playbackCache).setUpstreamDataSourceFactory(upstream)
        val routed = DataSource.Factory { CacheRoutingDataSource(upstream, cached) }
        val source = ResolvingDataSource.Factory(routed) { spec ->
            when {
                spec.uri.scheme == "nuvx" && spec.uri.host == "track" -> {
                    // Media3 invokes this on its loading thread; never resolve a signed URL on the UI thread.
                    val stream = try {
                        runBlocking { withTimeout(45_000) { router.resolveAudio(spec.uri.lastPathSegment.orEmpty()) } }
                    } catch (_: Exception) {
                        throw IOException("Could not resolve this track. Check your connection or streaming engine.")
                    }
                    spec.withUri(Uri.parse(stream.url)).withRequestHeaders(spec.httpRequestHeaders + stream.headers)
                }
                spec.uri.scheme == "nuvx" && spec.uri.host in setOf("video-audio", "video-picture") -> {
                    val videoId = spec.uri.lastPathSegment.orEmpty()
                    val stream = resolveVideo(router, videoId)
                    val url = if (spec.uri.host == "video-audio") stream.audioUrl else stream.videoUrl
                    spec.withUri(Uri.parse(url)).withRequestHeaders(spec.httpRequestHeaders + stream.headers)
                }
                isLocalPlaybackScheme(spec.uri.scheme.orEmpty()) -> spec
                else -> throw IOException("Unsupported media request")
            }
        }
        val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val audioSessionId = (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
            .takeIf { it > 0 } ?: C.AUDIO_SESSION_ID_UNSET
        fun buildPlayer(handleAudioFocus: Boolean): ExoPlayer {
            val builder = ExoPlayer.Builder(this)
                .setMediaSourceFactory(VideoMediaSourceFactory(DefaultMediaSourceFactory(source)))
                .setAudioAttributes(audioAttributes, handleAudioFocus)
                .setHandleAudioBecomingNoisy(handleAudioFocus)
            if (handleAudioFocus) builder.setWakeMode(C.WAKE_MODE_NETWORK)
            return builder.build().apply { setAudioSessionId(audioSessionId) }
        }
        val player = buildPlayer(handleAudioFocus = true)
        val standby = buildPlayer(handleAudioFocus = false)
        exoPlayer = player
        standbyPlayer = standby
        crossfadePlayer = AndroidCrossfadePlayer(player, standby, audioSessionId > 0)
        player.addListener(audioSessionListener)
        updateAudioEffectSession()
        crossfadePreferences = getSharedPreferences("nuvx", MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(crossfadePreferenceListener)
            crossfadePlayer?.setCrossfadeSeconds(CrossfadeSettings.read((application as NuvxApplication).store))
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, crossfadePlayer!!).setSessionActivity(activity).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }

    override fun onDestroy() {
        crossfadePreferences?.unregisterOnSharedPreferenceChangeListener(crossfadePreferenceListener)
        crossfadePreferences = null
        crossfadePlayer?.close()
        crossfadePlayer = null
        exoPlayer?.removeListener(audioSessionListener)
        if (currentAudioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            sendBroadcast(audioEffectSessionIntent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, currentAudioSessionId))
        }
        currentAudioSessionId = C.AUDIO_SESSION_ID_UNSET
        session?.run { player.release(); release() }
        session = null
        exoPlayer = null
        standbyPlayer?.release()
        standbyPlayer = null
        cache?.release()
        cache = null
        synchronized(videoLock) { videoStreams.clear() }
        super.onDestroy()
    }

    private fun updateAudioEffectSession() {
        val player = exoPlayer
        val audioSessionId = activeAudioEffectSessionId(
            player?.playWhenReady == true,
            player?.playbackState ?: Player.STATE_IDLE,
            player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET,
        )
        if (audioSessionId == currentAudioSessionId) return
        if (currentAudioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            sendBroadcast(audioEffectSessionIntent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, currentAudioSessionId))
        }
        currentAudioSessionId = audioSessionId
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            sendBroadcast(audioEffectSessionIntent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, audioSessionId))
        }
    }

    private fun resolveVideo(router: com.bxane.nuvx.core.BackendRouter, videoId: String): VideoStream = synchronized(videoLock) {
        val quality = router.videoQuality.value
        val key = VideoStreamCacheKey(videoId, quality)
        val now = System.currentTimeMillis()
        videoStreams.get(key, now)?.let { return@synchronized it }
        val resolved = try {
            runBlocking { withTimeout(45_000) { router.resolveVideo(videoId, quality.maxHeight) } }
        } catch (_: Exception) {
            throw IOException("Could not resolve this video. Check your connection or streaming engine.")
        }
        videoStreams.put(key, resolved, System.currentTimeMillis() + 30_000)
        resolved
    }
}

private class VideoMediaSourceFactory(private val delegate: DefaultMediaSourceFactory) : MediaSource.Factory {
    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun createMediaSource(mediaItem: androidx.media3.common.MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.scheme != "nuvx" || uri.host != "video") return delegate.createMediaSource(mediaItem)
        val id = uri.lastPathSegment.orEmpty()
        val audio = mediaItem.buildUpon().setUri(Uri.parse("nuvx://video-audio/$id")).build()
        val picture = mediaItem.buildUpon().setUri(Uri.parse("nuvx://video-picture/$id")).build()
        return MergingMediaSource(delegate.createMediaSource(audio), delegate.createMediaSource(picture))
    }
}
