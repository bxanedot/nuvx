@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bxane.nuvx

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bxane.nuvx.core.resolveAudio
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
    private var cache: androidx.media3.datasource.cache.SimpleCache? = null

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
                isLocalPlaybackScheme(spec.uri.scheme.orEmpty()) -> spec
                else -> throw IOException("Unsupported media request")
            }
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        cache?.release()
        cache = null
        super.onDestroy()
    }
}
