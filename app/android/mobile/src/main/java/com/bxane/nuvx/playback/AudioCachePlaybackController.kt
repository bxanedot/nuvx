package com.bxane.nuvx.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.data.model.isLiveRadio
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.playback.smart.AutomixAnalysisSource
import kotlinx.coroutines.runBlocking

@UnstableApi
internal class AudioCachePlaybackController(
    private val cacheProvider: () -> SimpleCache,
    private val keyFactory: CacheKeyFactory,
    private val heldByYouTube: (String?) -> Boolean,
    private val isOffline: () -> Boolean,
    private val tag: String,
) {
    private val cache: SimpleCache get() = cacheProvider()
    fun playbackFactory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val cacheDs = cacheFactory(upstream).createDataSource()
        val upstreamDs = upstream.createDataSource()
        object : DataSource {
            private var activeDs: DataSource = cacheDs

            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                cacheDs.addTransferListener(transferListener)
                upstreamDs.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val scheme = dataSpec.uri.scheme
                activeDs = if (scheme == "file" || scheme == "content" || isLiveRadio(dataSpec.key)) {
                    upstreamDs
                } else {
                    settleDirectEntry(dataSpec.uri)
                    cacheDs
                }
                return activeDs.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                activeDs.read(buffer, offset, length)

            override fun getUri(): Uri? = activeDs.uri

            override fun close() {
                activeDs.close()
            }
        }
    }

    private fun settleDirectEntry(uri: Uri) {
        if (uri.scheme != "nuvx" || uri.authority != "watch") return
        val videoId = uri.getQueryParameter("v") ?: return
        if (QualityUpgrade.cacheTag(uri) != null ||
            uri.getQueryParameter(AudioCache.WATCH_READ_PARAM) != null ||
            AutomixAnalysisSource.requestsYouTubeOpus(
                uri.getQueryParameter(AutomixAnalysisSource.OPUS_QUERY_PARAMETER),
            )
        ) {
            return
        }
        if (!DirectSourceGate.isActive() || StreamChoice.of(videoId) != null) return
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        if (!heldByYouTube(key) || isOffline()) return
        try {
            runBlocking<Unit>(TrackLog.about(videoId)) {
                DirectSourceGate.awaitOpen(videoId)
                val addon = runCatching { DirectSourceGate.lookup(videoId, SourceResolver.targetIn(uri)) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                if (StreamChoice.of(videoId) != null) return@runBlocking
                if (addon != null) {
                    dropYouTubeEntry(key, videoId)
                    if (!heldByYouTube(key)) {
                        StreamChoice.remember(videoId, addon, substituted = true)
                        return@runBlocking
                    }
                }
                runCatching { StreamResolver.resolve(videoId) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                    ?.let { url ->
                        StreamChoice.remember(
                            videoId,
                            SourceStream(url, headers = StreamResolver.mediaHeadersFor(url)),
                            substituted = false,
                        )
                        DirectSourceGate.startedOnYouTube(videoId, SourceResolver.targetIn(uri))
                    }
            }
        } catch (e: InterruptedException) {
            throw java.io.InterruptedIOException("direct lookup for $videoId interrupted")
        }
    }

    fun dropYouTubeEntry(key: String, videoId: String) {
        runCatching { cache.removeResource(key) }
            .onSuccess { TrackLog.d(tag, "dropped cached YouTube copy $key for the addon's", about = videoId) }
            .onFailure { TrackLog.d(tag, "cache entry $key still in use: ${it.message}", about = videoId) }
    }

    fun cacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun readAheadCacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)

}
