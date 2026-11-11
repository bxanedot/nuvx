package com.bxane.nuvx.playback

import android.content.Context
import android.media.MediaDataSource
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.playback.smart.AutomixAnalysisSource
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.DeviceCodecs
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.TrackMatcher
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import com.bxane.nuvx.data.model.isLiveRadio

@UnstableApi
object AudioCache {

    private const val TAG = "nuvx"

    private val evictor = DynamicLruCacheEvictor(AppSettings.DEFAULT_CACHE_LIMIT_BYTES)

    private const val PRELOAD_BYTES = 1L * 1024 * 1024

    private const val CHUNK_BYTES = 2L * 1024 * 1024

    private const val LOCK_PROBE_BYTES = 64L * 1024

    private const val HEAD_PROBE_BYTES = 8L * 1024 * 1024

    private const val MAX_ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024

    private const val MAX_ANALYSIS_TRACK_BYTES = 16L * 1024 * 1024

    private const val RENDITION_KEYS_TTL_MS = 5_000L

    private const val PREFETCH_DELAY_MS = 8_000L

    private const val RETRY_DELAY_MS = 5_000L

    private const val MAX_ATTEMPTS = 4

    private const val QUEUE_LOOKAHEAD = 1

    private const val QUEUE_RESOLVE_STAGGER_MS = 500L

    const val QUEUE_DEPTH = QUEUE_LOOKAHEAD + 1

    private lateinit var cache: SimpleCache

    private lateinit var appContext: Context

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        appContext = context.applicationContext
        evictor.maxBytes = AppSettings.audioCacheLimitBytes.value
        val dir = File(context.noBackupFilesDir, CACHE_DIR_NAME)
        val databaseProvider = StandaloneDatabaseProvider(context)
        migrateFromCacheDir(File(context.cacheDir, LEGACY_CACHE_DIR_NAME), dir, databaseProvider)
        cache = SimpleCache(dir, evictor, databaseProvider)
        val state = context.getSharedPreferences(CACHE_STATE_PREFS, Context.MODE_PRIVATE)
        val format = state.getInt(KEY_CACHE_FORMAT, -1)
        if (format in 0 until CACHE_FORMAT) {
            cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
            TrackLog.d(TAG, "cleared the song cache for cache format $CACHE_FORMAT")
        }
        if (format != CACHE_FORMAT) state.edit().putInt(KEY_CACHE_FORMAT, CACHE_FORMAT).apply()
        if (state.getInt(KEY_MATCHING_SCHEMA, 0) < MATCHING_SCHEMA) {
            val stale = cache.keys.filter { it.endsWith(ALT_SUFFIX) }
            stale.forEach { runCatching { cache.removeResource(it) } }
            state.edit().putInt(KEY_MATCHING_SCHEMA, MATCHING_SCHEMA).apply()
            TrackLog.d(TAG, "invalidated ${stale.size} source cache entries after matcher upgrade")
        }
        dropForeignEntries(keep = emptySet())
        scope.launch {
            AppSettings.audioCacheLimitBytes.collect { maxBytes ->
                evictor.maxBytes = maxBytes
                evictor.applyNow(cache)
            }
        }
    }

    private fun migrateFromCacheDir(legacy: File, dir: File, databaseProvider: StandaloneDatabaseProvider) {
        if (!legacy.isDirectory) return
        if (!dir.exists()) {
            dir.parentFile?.mkdirs()
            if (legacy.renameTo(dir)) {
                TrackLog.d(TAG, "moved the song cache out of the system-clearable cache directory")
                return
            }
        }
        runCatching { SimpleCache.delete(legacy, databaseProvider) }
            .onFailure { legacy.deleteRecursively() }
    }

    fun clear(onComplete: () -> Unit = {}) {
        cancel()
        analysisHeadController.clear()
        renditionKeys.clear()
        scope.launch {
            cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
            metadataController.publishContentsChanged()
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    fun discard(uri: Uri, keepComplete: Boolean = false) {
        val exact = keyFactory.buildCacheKey(DataSpec(uri))
        val about = mediaIdIn(uri)
        val family = uri.getQueryParameter("v")?.let { videoId ->
            cache.keys.filter { it == videoId || it.startsWith("$videoId#") }
        } ?: emptyList()
        (family + exact).distinct().forEach { key ->
            if (keepComplete && isComplete(key)) {
                TrackLog.d(TAG, "kept complete cache entry $key through a network failure", about = about)
                return@forEach
            }
            runCatching { cache.removeResource(key) }
                .onSuccess { TrackLog.d(TAG, "discarded cache entry $key", about = about) }
                .onFailure { TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = about) }
        }
    }

    fun discardRendition(uri: Uri) {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        runCatching { cache.removeResource(key) }
            .onSuccess { TrackLog.d(TAG, "discarded unused rendition $key", about = mediaIdIn(uri)) }
            .onFailure { TrackLog.d(TAG, "rendition $key still in use: ${it.message}", about = mediaIdIn(uri)) }
    }

    fun discardBadRendition(uri: Uri, key: String): Boolean {
        if (!::cache.isInitialized) return false
        val about = mediaIdIn(uri)
        if (key == keyFactory.buildCacheKey(DataSpec(uri))) {
            TrackLog.d(TAG, "keeping undecodable rendition $key: it is the one playing", about = about)
            return false
        }
        return runCatching { cache.removeResource(key) }
            .onSuccess {
                TrackLog.d(TAG, "discarded undecodable rendition $key", about = about)
                uri.getQueryParameter("v")?.let(analysisHeadController::removeRequest)
            }
            .onFailure {
                TrackLog.d(TAG, "undecodable rendition $key still in use: ${it.message}", about = about)
            }
            .isSuccess
    }

    const val WATCH_READ_PARAM = "watch_read"

    private val keyFactory = CacheKeyFactory { spec ->
        val key = phoneKey(spec)
        if (spec.uri.getQueryParameter(WATCH_READ_PARAM) != null) "$key#watch" else key
    }

    private fun phoneKey(spec: androidx.media3.datasource.DataSpec): String =
        spec.uri.getQueryParameter("v")
            ?.let { videoId ->
                val rendition = QualityUpgrade.cacheTag(spec.uri)
                when {
                    rendition != null -> "$videoId#$rendition"
                    SourceResolver.canSubstituteForYouTube() ->
                        "$videoId#alt${atmosKeySuffix()}".let { completeSibling(videoId, it) ?: it }
                    else -> completeSibling(videoId, videoId) ?: videoId
                }
            }
            ?: spec.uri.takeIf { it.authority == "source" }?.let { uri ->
                val source = uri.getQueryParameter("s")
                val track = uri.getQueryParameter("t")
                if (source != null && track != null) {
                    val stereo = uri.getQueryParameter(SourceResolver.MIX_PARAM) == SourceResolver.MIX_STEREO
                    val base = if (stereo) "$source|$track#stereo" else "$source|$track"
                    QualityUpgrade.cacheTag(uri)?.let { "$base#$it" } ?: base
                } else {
                    null
                }
            }
            ?: spec.key
            ?: spec.uri.toString()

    private fun completeSibling(videoId: String, preferred: String): String? {
        if (!::cache.isInitialized) return null
        if (cache.getCachedSpans(preferred).isNotEmpty()) return null
        val siblings = listOf(videoId, "$videoId$ALT_SUFFIX", "$videoId$ALT_SUFFIX-noatmos")
            .filter { it != preferred }
        if (isOffline()) return siblings.firstOrNull(::isComplete)
        if (preferred != videoId) return null
        return siblings.firstOrNull { key -> isComplete(key) && isKeptOrigin(key) }
    }

    private fun isKeptOrigin(key: String): Boolean = metadataController.isKeptOrigin(key)

    fun originOf(uri: Uri): Origin? = metadataController.originOf(uri)

    fun heldByYouTube(key: String?): Boolean = metadataController.heldByYouTube(key)

    private fun isComplete(key: String): Boolean = runCatching {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        length > 0 && cache.isCached(key, 0, length)
    }.getOrDefault(false)

    fun isOffline(): Boolean {
        if (!::appContext.isInitialized) return false
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }
            .getOrNull()
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
    }

    private fun atmosKeySuffix(): String =
        if (DeviceCodecs.atmosWanted) "" else "-noatmos"

    enum class Origin { YOUTUBE, JIOSAAVN, OTHER }

    data class CachedSong(
        val song: Song,
        val origin: Origin,
        val bytes: Long,
        val lastUsedMs: Long,
        val complete: Boolean,
    )

    private val metadataController = AudioCacheMetadataController(
        scope = scope,
        cacheProvider = { cache },
        cacheInitialized = { ::cache.isInitialized },
        appContextProvider = { appContext },
        keyFactory = keyFactory,
        cacheKeyBaseOf = ::cacheKeyBaseOf,
    )

    val contentsChanged: StateFlow<Int> = metadataController.contentsChanged

    fun noteSongs(songs: List<Song>) = metadataController.noteSongs(songs)

    fun notePlayed(song: Song, uri: Uri?) = metadataController.notePlayed(song, uri)

    fun recordServed(spec: DataSpec, servedUrl: String) = metadataController.recordServed(spec, servedUrl)

    suspend fun cachedSongs(): List<CachedSong> = metadataController.cachedSongs()
    fun holds(mediaId: String): Boolean {
        if (!::cache.isInitialized || mediaId.isBlank()) return false
        return metadataController.keysOf(mediaId).any { key -> cache.getCachedSpans(key).any { it.isCached } }
    }

    suspend fun remove(mediaId: String): Int = withContext(Dispatchers.IO) {
        if (!::cache.isInitialized || mediaId.isBlank()) return@withContext 0
        metadataController.forgetPlayed(mediaId)
        analysisHeadController.removeState(mediaId)
        renditionKeys.remove(mediaId)
        var removed = 0
        metadataController.keysOf(mediaId).forEach { key ->
            runCatching { cache.removeResource(key) }
                .onSuccess { removed++ }
                .onFailure { TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = mediaId) }
        }
        TrackLog.d(TAG, "removed $removed cache entries at the listener's request", about = mediaId)
        metadataController.publishContentsChanged()
        removed
    }

    class CompleteCopy(
        internal val key: String,
        val length: Long,
        val extension: String,
        val mimeType: String,
    )

    fun completeCopyOf(mediaId: String): CompleteCopy? {
        if (!::cache.isInitialized || mediaId.isBlank()) return null
        return metadataController.keysOf(mediaId)
            .filterNot { it.endsWith("#watch") }
            .filterNot { key ->
                runCatching { cache.getContentMetadata(key).get(AudioCacheMetadataKeys.META_ORIGIN, null as String?) }
                    .getOrNull() == Origin.OTHER.name
            }
            .mapNotNull { key ->
                val length = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }
                    .getOrDefault(C.LENGTH_UNSET.toLong())
                if (length <= 0 || !cache.isCached(key, 0, length)) return@mapNotNull null
                val (extension, mime) = containerOf(key) ?: return@mapNotNull null
                CompleteCopy(key, length, extension, mime)
            }
            .maxByOrNull { it.length }
    }

    private fun containerOf(key: String): Pair<String, String>? {
        val head = cache.getCachedSpans(key)
            .firstOrNull { it.position == 0L && it.isCached }
            ?.file
            ?.takeIf { it.exists() }
            ?: return null
        val magic = ByteArray(CONTAINER_MAGIC_BYTES)
        val read = runCatching { head.inputStream().use { it.read(magic) } }.getOrDefault(-1)
        if (read < magic.size) return null
        return cachedContainerOf(magic)
    }

    suspend fun copyOut(
        copy: CompleteCopy,
        sink: java.io.OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val source = CacheDataSource.Factory().setCache(cache).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("nuvx://cache"))
            .setKey(copy.key)
            .setPosition(0)
            .setLength(copy.length)
            .build()
        try {
            source.open(spec)
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var written = 0L
            while (written < copy.length) {
                ensureActive()
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, copy.length)
            }
            if (written < copy.length) {
                throw IOException("cached copy ended at $written of ${copy.length} bytes")
            }
        } finally {
            source.close()
        }
    }

    fun dropForeignEntries(keep: Set<String>, keepAnalysis: Boolean = false) {
        if (!::cache.isInitialized) return
        scope.launch {
            var dropped = 0
            cache.keys.toList().forEach { key ->
                val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: return@forEach
                val foreign = meta.get(AudioCacheMetadataKeys.META_ORIGIN, null as String?) == Origin.OTHER.name &&
                    !keepsAddonCopy(key)
                val analysisOnly = !keepAnalysis &&
                    meta.get(AudioCacheMetadataKeys.META_ANALYSIS, null as String?) != null &&
                    meta.get(AudioCacheMetadataKeys.META_PLAYBACK, null as String?) == null &&
                    meta.get(AudioCacheMetadataKeys.META_PLAYED, null as String?) == null
                if (!foreign && !analysisOnly) return@forEach
                val mediaId = meta.get(AudioCacheMetadataKeys.META_MEDIA_ID, null as String?)
                if (mediaId != null && mediaId in keep) return@forEach
                runCatching { cache.removeResource(key) }
                    .onSuccess {
                        dropped++
                        mediaId?.let { id ->
                            analysisHeadController.removeRequestAndWant(id)
                            renditionKeys.remove(id)
                        }
                    }
                    .onFailure { TrackLog.d(TAG, "unkept cache entry $key still in use: ${it.message}") }
            }
            if (dropped > 0) TrackLog.d(TAG, "dropped $dropped addon / analysis-only cache entries")
        }
    }

    private fun keepsAddonCopy(key: String): Boolean =
        !key.contains("#hifi") && isComplete(key) && containerOf(key) != null

    private fun cacheKeyBaseOf(mediaId: String): String =
        SourceRegistry.parseTrackKey(mediaId)?.let { (source, track) -> "$source|$track" } ?: mediaId

    private const val COPY_BUFFER_BYTES = 64 * 1024

    private const val CACHE_DIR_NAME = "audio_cache"
    private const val LEGACY_CACHE_DIR_NAME = "audio"
    private const val CACHE_STATE_PREFS = "audio_cache_state"
    private const val KEY_CACHE_FORMAT = "cache_format"

    private const val CACHE_FORMAT = 1
    private const val KEY_MATCHING_SCHEMA = "matching_schema"
    private const val MATCHING_SCHEMA = 2
    private const val ALT_SUFFIX = "#alt"

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
            uri.getQueryParameter(WATCH_READ_PARAM) != null ||
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

    private fun dropYouTubeEntry(key: String, videoId: String) {
        runCatching { cache.removeResource(key) }
            .onSuccess { TrackLog.d(TAG, "dropped cached YouTube copy $key for the addon's", about = videoId) }
            .onFailure { TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = videoId) }
    }

    private fun cacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun readAheadCacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)

    private var upstreamFactory: DataSource.Factory? = null

    fun setUpstream(factory: DataSource.Factory) {
        upstreamFactory = factory
    }

    private var pendingQueue: List<String> = emptyList()

    private val readAheadController = AudioCacheReadAheadController(
        scope = scope,
        pendingQueue = { pendingQueue },
        setPendingQueue = { pendingQueue = it },
        tag = TAG,
        cacheKeyOf = ::cacheKeyOf,
        heldByYouTube = ::heldByYouTube,
        dropYouTubeEntry = ::dropYouTubeEntry,
        fetch = ::fetch,
        fetchWhole = ::fetchWhole,
        preloadBytes = PRELOAD_BYTES,
        prefetchDelayMs = PREFETCH_DELAY_MS,
        queueLookahead = QUEUE_LOOKAHEAD,
        queueResolveStaggerMs = QUEUE_RESOLVE_STAGGER_MS,
    )

    fun prefetchQueue(upcoming: List<Upcoming>) = readAheadController.prefetchQueue(upcoming)

    fun prefetchDirect(upcoming: Upcoming) = readAheadController.prefetchDirect(upcoming)

    fun cancelDirect() = readAheadController.cancelDirect()

    data class Upcoming(val mediaId: String, val target: TrackMatcher.Target)

    fun cancel() {
        pendingQueue = emptyList()
        readAheadController.cancelQueue()
    }

    private suspend fun fetchWhole(videoId: String) {
        repeat(MAX_ATTEMPTS) {
            if (pendingQueue.firstOrNull() != videoId) {
                TrackLog.d(TAG, "$videoId is no longer the read-ahead target; stopping", about = videoId)
                return
            }
            if (cacheWholeOnce(videoId)) return
            delay(RETRY_DELAY_MS)
        }
        TrackLog.d(TAG, "stopped short of caching $videoId in full", about = videoId)
    }

    private suspend fun cacheWholeOnce(videoId: String): Boolean {
        val total = runCatching { StreamResolver.contentLength(videoId) }.getOrNull()
            ?: return false

        var position = 0L
        while (position < total) {
            if (pendingQueue.firstOrNull() != videoId) return false
            val length = minOf(CHUNK_BYTES, total - position)
            if (cache.getCachedBytes(videoId, position, length) < length) {
                fetch(videoId, position, length)
                if (cache.getCachedBytes(videoId, position, length) < length) return false
            }
            position += length
        }
        return true
    }

    fun cachedSummary(uri: Uri): String {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val spans = cache.getCachedSpans(key).filter { it.isCached }
        if (spans.isEmpty()) return "$key holds nothing"
        val total = spans.sumOf { it.length }
        val ranges = spans.sortedBy { it.position }
            .joinToString(" ") { "[${it.position},${it.position + it.length})" }
        return "$key holds ${total / 1024}kB in ${spans.size} spans: $ranges"
    }

    suspend fun warmRange(uri: Uri, position: Long, length: Long) {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        fetch(key, uri, position, length)
    }

    const val MIN_ALIGNMENT_PREFIX_BYTES = 800L * 1024

    private val analysisHeadController = AudioCacheAnalysisHeadController(
        scope = scope,
        cache = { cache },
        cacheInitialized = { ::cache.isInitialized },
        upstreamAvailable = { upstreamFactory != null },
        renditionsOf = ::renditionsOf,
        cachedPrefixBytes = ::cachedPrefixBytes,
        fetch = { cacheKey, uri, position, length, pinKey -> fetch(cacheKey, uri, position, length, pinKey) },
        pull = ::pull,
        tag = TAG,
        minimumPrefixBytes = MIN_ALIGNMENT_PREFIX_BYTES,
        maximumAnalysisHeadBytes = MAX_ANALYSIS_HEAD_BYTES,
        maximumAnalysisTrackBytes = MAX_ANALYSIS_TRACK_BYTES,
    )

    suspend fun awaitAnalysisHead(uri: Uri, timeoutMs: Long = 20_000L): Boolean =
        analysisHeadController.awaitAnalysisHead(uri, timeoutMs)

    fun requestAnalysisHead(uri: Uri) = analysisHeadController.requestAnalysisHead(uri)

    fun isFullyCached(uri: Uri): Boolean {
        val contentLength = contentLengthOf(uri)
        if (contentLength <= 0) return false
        return cachedPrefixBytes(uri) >= contentLength
    }

    fun contentLengthOf(uri: Uri): Long {
        if (!::cache.isInitialized) return 0L
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        return ContentMetadata.getContentLength(cache.getContentMetadata(key)).coerceAtLeast(0L)
    }

    fun cachedPrefixBytes(uri: Uri): Long {
        if (!::cache.isInitialized) return 0L
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val probe = contentLengthOf(uri).takeIf { it > 0 } ?: HEAD_PROBE_BYTES
        return cache.getCachedLength(key, 0, probe).coerceAtLeast(0L)
    }

    data class Rendition(val key: String, val contentLength: Long, val cachedPrefix: Long) {
        val isComplete: Boolean get() = contentLength > 0 && cachedPrefix >= contentLength
    }

    fun renditionsOf(uri: Uri): List<Rendition> {
        if (!::cache.isInitialized) return emptyList()
        val videoId = uri.getQueryParameter("v")
            ?: return listOfNotNull(renditionFor(keyFactory.buildCacheKey(DataSpec(uri))))
        return renditionKeysFor(videoId)
            .mapNotNull(::renditionFor)
            .sortedBy { it.contentLength }
    }

    private fun renditionKeysFor(videoId: String): List<String> {
        val now = SystemClock.elapsedRealtime()
        renditionKeys[videoId]?.let { (at, keys) ->
            if (now - at < RENDITION_KEYS_TTL_MS) return keys
        }
        val keys = cache.keys.filter { it == videoId || it.startsWith("$videoId#") }
        if (keys.isNotEmpty()) {
            renditionKeys[videoId] = now to keys
        } else {
            renditionKeys.remove(videoId)
        }
        return keys
    }

    private val renditionKeys = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    fun cacheKeyOf(uri: Uri): String = keyFactory.buildCacheKey(DataSpec(uri))

    private fun renditionFor(key: String): Rendition? {
        val contentLength =
            ContentMetadata.getContentLength(cache.getContentMetadata(key)).coerceAtLeast(0L)
        val probe = contentLength.takeIf { it > 0 } ?: HEAD_PROBE_BYTES
        val prefix = cache.getCachedLength(key, 0, probe).coerceAtLeast(0L)
        return if (prefix <= 0L) null else Rendition(key, contentLength, prefix)
    }

    fun renditionDataSource(uri: Uri, rendition: Rendition): MediaDataSource =
        AudioCacheMediaDataSource(
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(AudioCacheNoUpstream)
                .setCacheKeyFactory { rendition.key }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource(),
            uri,
        )

    fun mediaDataSource(uri: Uri): MediaDataSource? {
        if (!isFullyCached(uri)) return null
        return AudioCacheMediaDataSource(cacheFactory(AudioCacheNoUpstream).createDataSource(), uri)
    }

    fun headMediaDataSource(uri: Uri): MediaDataSource? {
        if (cachedPrefixBytes(uri) <= 0L) return null
        return AudioCacheMediaDataSource(cacheFactory(AudioCacheNoUpstream).createDataSource(), uri)
    }

    private suspend fun fetch(videoId: String, position: Long, length: Long) =
        fetch(videoId, Uri.parse("nuvx://watch?v=$videoId"), position, length)

    private suspend fun fetch(
        cacheKey: String,
        uri: Uri,
        position: Long,
        length: Long,
        pinKey: Boolean = false,
    ) {
        if (upstreamFactory == null) return
        if (cache.getCachedBytes(cacheKey, position, length) >= length) return

        if (length > LOCK_PROBE_BYTES) {
            pull(cacheKey, uri, position, LOCK_PROBE_BYTES, pinKey)
            if (cache.getCachedBytes(cacheKey, position, LOCK_PROBE_BYTES) < LOCK_PROBE_BYTES) return
        }
        pull(cacheKey, uri, position, length, pinKey)
    }

    private suspend fun pull(cacheKey: String, uri: Uri, position: Long, length: Long, pinKey: Boolean) {
        val upstream = upstreamFactory ?: return

        val about = mediaIdIn(uri)
        val fetchStart = SystemClock.elapsedRealtime()
        TrackLog.d(TAG, "read-ahead fetching $cacheKey [$position, ${position + length})", about = about)

        val source = readAheadCacheFactory(upstream)
            .apply { if (pinKey) setCacheKeyFactory { cacheKey } }
            .createDataSource()
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(position)
            .setLength(length)
            .build()
        val writer = CacheWriter(source, spec, null, null)

        runCatching {
            withContext(Dispatchers.IO) {
                val handle = coroutineContext.job.invokeOnCompletion { writer.cancel() }
                try {
                    writer.cache()
                } finally {
                    handle.dispose()
                }
            }
        }.onFailure {
            TrackLog.d(TAG, "read-ahead stopped for $cacheKey: ${it.message}", about = about)
        }.onSuccess {
            TrackLog.d(
                TAG,
                "read-ahead fetched $cacheKey [$position, ${position + length}) in " +
                    "${SystemClock.elapsedRealtime() - fetchStart}ms",
                about = about,
            )
        }
    }
}

internal const val CONTAINER_MAGIC_BYTES = 12

internal fun cachedContainerOf(magic: ByteArray): Pair<String, String>? {
    if (magic.size < CONTAINER_MAGIC_BYTES) return null
    fun ascii(from: Int, to: Int) = String(magic, from, to - from, Charsets.US_ASCII)
    return when {
        magic[0] == 0x1A.toByte() && magic[1] == 0x45.toByte() &&
            magic[2] == 0xDF.toByte() && magic[3] == 0xA3.toByte() -> "webm" to "audio/webm"
        ascii(4, 8) == "ftyp" -> "m4a" to "audio/mp4"
        ascii(0, 4) == "fLaC" -> "flac" to "audio/flac"
        else -> null
    }
}
