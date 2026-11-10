package com.bxane.nuvx.playback

import android.content.Context
import android.media.MediaDataSource
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import com.bxane.nuvx.R
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.playback.smart.AutomixAnalysisSource
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
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
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.TrackMatcher
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
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
        analysisHeads.clear()
        analysisHeadsInFlight.clear()
        analysisHeadWant.clear()
        renditionKeys.clear()
        scope.launch {
            cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
            _contentsChanged.value++
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
                uri.getQueryParameter("v")?.let(analysisHeads::remove)
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

    private fun isKeptOrigin(key: String): Boolean = runCatching {
        val origin = cache.getContentMetadata(key).get(META_ORIGIN, null as String?)
        origin == Origin.YOUTUBE.name || origin == Origin.JIOSAAVN.name
    }.getOrDefault(false)

    fun originOf(uri: Uri): Origin? {
        if (!::cache.isInitialized || uri.scheme != "nuvx") return null
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        return runCatching { cache.getContentMetadata(key).get(META_ORIGIN, null as String?) }
            .getOrNull()
            ?.let { name -> Origin.values().firstOrNull { it.name == name } }
    }

    fun heldByYouTube(key: String?): Boolean {
        if (key == null || !::cache.isInitialized) return false
        return runCatching {
            cache.getCachedSpans(key).isNotEmpty() &&
                cache.getContentMetadata(key).get(META_ORIGIN, null as String?) == Origin.YOUTUBE.name
        }.getOrDefault(false)
    }

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

    private val _contentsChanged = MutableStateFlow(0)

    val contentsChanged: StateFlow<Int> = _contentsChanged.asStateFlow()

    private val notedSongs = ConcurrentHashMap<String, Song>()

    private val playedIds = ConcurrentHashMap.newKeySet<String>()

    fun noteSongs(songs: List<Song>) {
        val playable = songs.filter { it.localUri == null && it.videoId.isNotBlank() }
        if (playable.isEmpty()) return
        if (notedSongs.size > MAX_NOTED_SONGS) notedSongs.clear()
        playable.forEach { notedSongs[it.videoId] = it }
        if (!::cache.isInitialized) return
        scope.launch {
            val keys = cache.keys
            playable.forEach { song ->
                val base = cacheKeyBaseOf(song.videoId)
                keys.filter { it == base || it.startsWith("$base#") }.forEach { key ->
                    runCatching {
                        val meta = cache.getContentMetadata(key)
                        if (meta.get(META_ORIGIN, null as String?) == null) return@runCatching
                        if (meta.get(META_TITLE, null as String?) != null &&
                            meta.get(META_ART, null as String?) != null
                        ) {
                            return@runCatching
                        }
                        cache.applyContentMetadataMutations(
                            key,
                            ContentMetadataMutations().also { describe(it, song, null) },
                        )
                    }
                }
            }
        }
    }

    fun notePlayed(song: Song, uri: Uri?) {
        if (song.localUri != null || song.videoId.isBlank()) return
        if (playedIds.size > MAX_NOTED_SONGS) playedIds.clear()
        playedIds += song.videoId
        noteSongs(listOf(song))
        if (!::cache.isInitialized || uri?.scheme != "nuvx") return
        scope.launch {
            val key = keyFactory.buildCacheKey(DataSpec(uri))
            runCatching {
                val meta = cache.getContentMetadata(key)
                if (meta.get(META_ORIGIN, null as String?) == null) return@runCatching
                if (meta.get(META_PLAYED, null as String?) != null) return@runCatching
                cache.applyContentMetadataMutations(
                    key,
                    ContentMetadataMutations().set(META_PLAYED, "1"),
                )
            }
        }
    }

    fun recordServed(spec: DataSpec, servedUrl: String) {
        if (!::cache.isInitialized) return
        if (spec.uri.scheme != "nuvx") return
        val mediaId = mediaIdIn(spec.uri) ?: return
        val key = spec.key ?: keyFactory.buildCacheKey(spec)
        val origin = originOf(spec.uri, mediaId, servedUrl)
        val song = notedSongs[mediaId]
        val analysis = AutomixAnalysisSource.requestsYouTubeOpus(
            spec.uri.getQueryParameter(AutomixAnalysisSource.OPUS_QUERY_PARAMETER),
        )
        runCatching {
            val meta = cache.getContentMetadata(key)
            fun has(name: String) = meta.get(name, null as String?) != null
            val usedByPlayback = has(META_PLAYBACK) || !analysis
            val played = has(META_PLAYED) || (!analysis && mediaId in playedIds)
            val tagged = meta.get(META_ORIGIN, null as String?) == origin.name &&
                meta.get(META_MEDIA_ID, null as String?) == mediaId &&
                has(META_PLAYBACK) == usedByPlayback &&
                (usedByPlayback || has(META_ANALYSIS)) &&
                has(META_PLAYED) == played
            if (tagged && (song == null || has(META_TITLE))) return
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().also { mutations ->
                    mutations.set(META_ORIGIN, origin.name)
                    mutations.set(META_MEDIA_ID, mediaId)
                    if (usedByPlayback) mutations.set(META_PLAYBACK, "1") else mutations.set(META_ANALYSIS, "1")
                    if (played) mutations.set(META_PLAYED, "1")
                    describe(mutations, song, spec.uri)
                },
            )
        }.onFailure {
            TrackLog.d(TAG, "could not tag cache entry $key: ${it.message}", about = mediaId)
        }
    }

    private fun originOf(uri: Uri, mediaId: String, servedUrl: String): Origin {
        val host = runCatching { Uri.parse(servedUrl).host }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()
        if (host.endsWith("googlevideo.com") || host.endsWith("youtube.com")) return Origin.YOUTUBE
        if (host.endsWith("saavncdn.com") || host.endsWith("jiosaavn.com")) return Origin.JIOSAAVN
        val upgrade = QualityUpgrade.forcedStream(uri)
        val configId = when {
            upgrade != null -> upgrade.sourceConfigId
            uri.getQueryParameter("s") != null -> uri.getQueryParameter("s")
            else -> StreamChoice.of(mediaId)?.sourceConfigId
        }
        val kind = configId?.let { SourceRegistry.config(it)?.kind }
        return if (kind == SourceKind.JIOSAAVN) Origin.JIOSAAVN else Origin.OTHER
    }

    private fun describe(mutations: ContentMetadataMutations, song: Song?, uri: Uri?) {
        val title = song?.title?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("n")
        val artist = song?.artist?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("a")
        val album = song?.albumName?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("l")
        val duration = song?.durationText
            ?: uri?.getQueryParameter("d")?.toIntOrNull()?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) }
        title?.takeIf { it.isNotBlank() }?.let { mutations.set(META_TITLE, it) }
        artist?.takeIf { it.isNotBlank() }?.let { mutations.set(META_ARTIST, it) }
        album?.takeIf { it.isNotBlank() }?.let { mutations.set(META_ALBUM, it) }
        duration?.takeIf { it.isNotBlank() }?.let { mutations.set(META_DURATION, it) }
        song?.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { mutations.set(META_ART, it) }
    }

    suspend fun cachedSongs(): List<CachedSong> = withContext(Dispatchers.IO) {
        if (!::cache.isInitialized) return@withContext emptyList()
        class Entry(
            val origin: Origin,
            val meta: ContentMetadata,
            val bytes: Long,
            val touched: Long,
            val complete: Boolean,
        )
        val byTrack = LinkedHashMap<String, MutableList<Entry>>()
        for (key in cache.keys) {
            val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: continue
            val origin = meta.get(META_ORIGIN, null as String?)
                ?.let { runCatching { Origin.valueOf(it) }.getOrNull() }
                ?: continue
            if (meta.get(META_PLAYED, null as String?) == null) continue
            val mediaId = meta.get(META_MEDIA_ID, null as String?) ?: continue
            val spans = cache.getCachedSpans(key).filter { it.isCached && it.file?.exists() == true }
            val bytes = spans.sumOf { it.length }
            if (bytes <= 0L) continue
            val touched = spans.maxOf { it.lastTouchTimestamp }
            val length = ContentMetadata.getContentLength(meta)
            val complete = length > 0 && bytes >= length && cache.isCached(key, 0, length)
            byTrack.getOrPut(mediaId) { mutableListOf() } += Entry(origin, meta, bytes, touched, complete)
        }
        byTrack.map { (mediaId, entries) ->
            fun field(name: String): String? =
                entries.firstNotNullOfOrNull { it.meta.get(name, null as String?)?.takeIf(String::isNotBlank) }
            val bytes = entries.sumOf { it.bytes }
            val lastUsed = entries.maxOf { it.touched }
            val origin = entries.maxBy { it.bytes }.origin
            val complete = entries.any { it.complete }
            val noted = notedSongs[mediaId]
            val art = field(META_ART) ?: noted?.thumbnailUrl
                ?: mediaId.takeIf { SourceRegistry.parseTrackKey(it) == null }
                    ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            CachedSong(
                song = Song(
                    videoId = mediaId,
                    title = field(META_TITLE) ?: noted?.title?.takeIf(String::isNotBlank) ?: mediaId,
                    artist = field(META_ARTIST) ?: noted?.artist.orEmpty(),
                    thumbnailUrl = art,
                    durationText = field(META_DURATION) ?: noted?.durationText,
                    albumName = field(META_ALBUM) ?: noted?.albumName,
                    downloadFormat = listOfNotNull(
                        origin.label,
                        formatBytes(bytes),
                        appContext.getString(R.string.cached_song_partial).takeUnless { complete },
                    ).joinToString(" · "),
                    localDateAddedSeconds = lastUsed / 1000,
                ),
                origin = origin,
                bytes = bytes,
                lastUsedMs = lastUsed,
                complete = complete,
            )
        }.sortedByDescending { it.lastUsedMs }
    }

    private fun keysOf(mediaId: String): List<String> {
        val base = cacheKeyBaseOf(mediaId)
        return cache.keys.filter { key ->
            key == base || key.startsWith("$base#") ||
                runCatching { cache.getContentMetadata(key).get(META_MEDIA_ID, null as String?) }
                    .getOrNull() == mediaId
        }
    }

    fun holds(mediaId: String): Boolean {
        if (!::cache.isInitialized || mediaId.isBlank()) return false
        return keysOf(mediaId).any { key -> cache.getCachedSpans(key).any { it.isCached } }
    }

    suspend fun remove(mediaId: String): Int = withContext(Dispatchers.IO) {
        if (!::cache.isInitialized || mediaId.isBlank()) return@withContext 0
        playedIds.remove(mediaId)
        analysisHeads.remove(mediaId)
        analysisHeadsInFlight.remove(mediaId)
        analysisHeadWant.remove(mediaId)
        renditionKeys.remove(mediaId)
        var removed = 0
        keysOf(mediaId).forEach { key ->
            runCatching { cache.removeResource(key) }
                .onSuccess { removed++ }
                .onFailure { TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = mediaId) }
        }
        TrackLog.d(TAG, "removed $removed cache entries at the listener's request", about = mediaId)
        _contentsChanged.value++
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
        return keysOf(mediaId)
            .filterNot { it.endsWith("#watch") }
            .filterNot { key ->
                runCatching { cache.getContentMetadata(key).get(META_ORIGIN, null as String?) }
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
                val foreign = meta.get(META_ORIGIN, null as String?) == Origin.OTHER.name &&
                    !keepsAddonCopy(key)
                val analysisOnly = !keepAnalysis &&
                    meta.get(META_ANALYSIS, null as String?) != null &&
                    meta.get(META_PLAYBACK, null as String?) == null &&
                    meta.get(META_PLAYED, null as String?) == null
                if (!foreign && !analysisOnly) return@forEach
                val mediaId = meta.get(META_MEDIA_ID, null as String?)
                if (mediaId != null && mediaId in keep) return@forEach
                runCatching { cache.removeResource(key) }
                    .onSuccess {
                        dropped++
                        mediaId?.let { id ->
                            analysisHeads.remove(id)
                            analysisHeadWant.remove(id)
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

    private val Origin.label: String
        get() = when (this) {
            Origin.YOUTUBE -> SourceKind.YOUTUBE.label
            Origin.JIOSAAVN -> SourceKind.JIOSAAVN.label
            Origin.OTHER -> "Addon"
        }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(Locale.ROOT, mb / 1024) else "%.1f MB".format(Locale.ROOT, mb)
    }

    private const val META_ORIGIN = "nx_origin"
    private const val META_MEDIA_ID = "nx_media_id"
    private const val META_TITLE = "nx_title"
    private const val META_ARTIST = "nx_artist"
    private const val META_ALBUM = "nx_album"
    private const val META_DURATION = "nx_duration"
    private const val META_ART = "nx_art"

    private const val META_PLAYBACK = "nx_playback"

    private const val META_ANALYSIS = "nx_analysis"

    private const val META_PLAYED = "nx_played"
    private const val MAX_NOTED_SONGS = 256
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

    private var job: Job? = null
    private var pendingQueue: List<String> = emptyList()

    fun prefetchQueue(upcoming: List<Upcoming>) {
        val mediaIds = upcoming.map { it.mediaId }
        if (mediaIds == pendingQueue) return
        android.util.Log.d("BCFetchDebug", "prefetchQueue: head ${pendingQueue.firstOrNull()} -> ${mediaIds.firstOrNull()}")
        pendingQueue = mediaIds
        job?.cancel()
        val videoIds = mediaIds.filter { SourceRegistry.parseTrackKey(it) == null }
            .filter { it !in Downloads.saved.value }
        val substitutable = SourceResolver.canSubstituteForYouTube()
        job = videoIds.firstOrNull()?.let { next ->
            val target = upcoming.firstOrNull { it.mediaId == next }?.target
            val pinnedToYouTube = OriginalVersion.isPinned(next)
            scope.launch {
                val youTubeFilled = heldByYouTube(cacheKeyOf(Uri.parse("nuvx://watch?v=$next")))
                val warmed = if (substitutable && target != null && !pinnedToYouTube && !youTubeFilled) {
                    runCatching { SourceResolver.prefetchSubstitute(target) }
                        .onFailure { TrackLog.d(TAG, "warm-up substitute failed for $next: ${it.message}", about = next) }
                        .getOrNull()
                        ?.also { StreamChoice.remember(next, it, substituted = true) }
                } else {
                    null
                }
                val cacheBytes = (!substitutable || warmed != null) && !pinnedToYouTube
                if (cacheBytes) {
                    launch(TrackLog.about(next)) {
                        delay(PREFETCH_DELAY_MS)
                        fetch(next, 0, PRELOAD_BYTES)
                        fetchWhole(next)
                    }
                }
                launch {
                    delay(PREFETCH_DELAY_MS)
                    for (id in videoIds.take(QUEUE_LOOKAHEAD + 1).let { if (cacheBytes) it.drop(1) else it }) {
                        if (id == next && warmed != null) continue
                        runCatching { StreamResolver.resolve(id) }
                            .onFailure { TrackLog.d(TAG, "queue warm-up skipped $id: ${it.message}", about = id) }
                        delay(QUEUE_RESOLVE_STAGGER_MS)
                    }
                }
            }
        }
    }

    private var directJob: Job? = null

    fun prefetchDirect(upcoming: Upcoming) {
        directJob?.cancel()
        val id = upcoming.mediaId
        if (SourceRegistry.parseTrackKey(id) != null || id in Downloads.saved.value || OriginalVersion.isPinned(id)) {
            directJob = null
            return
        }
        directJob = scope.launch(TrackLog.about(id)) {
            if (StreamChoice.of(id) == null) {
                val addon = runCatching { DirectSourceGate.lookup(id, upcoming.target) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                val stream = addon ?: runCatching {
                    val url = StreamResolver.resolve(id)
                    SourceStream(url, headers = StreamResolver.mediaHeadersFor(url))
                }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        TrackLog.d(TAG, "direct read-ahead could not resolve $id: ${it.message}", about = id)
                    }
                    .getOrNull()
                    ?: return@launch
                if (StreamChoice.of(id) == null) {
                    val key = cacheKeyOf(Uri.parse("nuvx://watch?v=$id"))
                    if (addon != null && heldByYouTube(key)) dropYouTubeEntry(key, id)
                    if (addon != null && heldByYouTube(key)) return@launch
                    StreamChoice.remember(id, stream, substituted = addon != null)
                    if (addon == null) DirectSourceGate.startedOnYouTube(id, upcoming.target)
                }
            }
            fetch(id, 0, PRELOAD_BYTES)
        }
    }

    fun cancelDirect() {
        directJob?.cancel()
        directJob = null
    }

    data class Upcoming(val mediaId: String, val target: TrackMatcher.Target)

    fun cancel() {
        pendingQueue = emptyList()
        job?.cancel()
        job = null
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

    private val analysisHeads = ConcurrentHashMap<String, Boolean>()

    private val analysisHeadsInFlight = ConcurrentHashMap.newKeySet<String>()

    private val analysisHeadWant = ConcurrentHashMap<String, Long>()

    const val MIN_ALIGNMENT_PREFIX_BYTES = 800L * 1024

    private const val MAX_ALIGNMENT_HEAD_ROUNDS = 3

    private const val HEAD_ROUND_SETTLE_MS = 500L

    suspend fun awaitAnalysisHead(uri: Uri, timeoutMs: Long = 20_000L): Boolean {
        if (!::cache.isInitialized) return false
        val videoId = uri.getQueryParameter("v") ?: return false
        if (alignmentHeadComplete(videoId)) return true
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        if (videoId !in analysisHeadsInFlight) {
            analysisHeads.remove(videoId)
        }
        requestAnalysisHead(opus)
        var rounds = 1
        var askedAt = SystemClock.elapsedRealtime()
        val deadline = askedAt + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (alignmentHeadComplete(videoId)) return true
            if (videoId !in analysisHeadsInFlight) {
                if (rounds >= MAX_ALIGNMENT_HEAD_ROUNDS) return alignmentHeadComplete(videoId)
                val now = SystemClock.elapsedRealtime()
                if (now - askedAt >= HEAD_ROUND_SETTLE_MS) {
                    if (alignmentPrefixBytes(videoId) < MIN_ALIGNMENT_PREFIX_BYTES) {
                        analysisHeads.remove(videoId)
                        requestAnalysisHead(opus)
                        rounds++
                    }
                    askedAt = now
                }
            }
            delay(40)
        }
        return alignmentHeadComplete(videoId)
    }

    private fun alignmentHeadComplete(videoId: String): Boolean {
        val held = alignmentPrefixBytes(videoId)
        if (held >= MIN_ALIGNMENT_PREFIX_BYTES) return true
        val want = analysisHeadWant[videoId] ?: return false
        if (videoId in analysisHeadsInFlight) return false
        return held >= want
    }

    private fun alignmentPrefixBytes(videoId: String): Long {
        val watch = Uri.parse("nuvx://watch?v=$videoId")
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        val renditions = renditionsOf(watch)
        val sameSource = renditions
            .filter { !it.key.endsWith("#alt") }
            .maxOfOrNull { it.cachedPrefix } ?: 0L
        if (sameSource >= MIN_ALIGNMENT_PREFIX_BYTES) return sameSource
        return maxOf(
            cachedPrefixBytes(opus),
            cachedPrefixBytes(watch),
            renditions.maxOfOrNull { it.cachedPrefix } ?: 0L,
        )
    }

    fun requestAnalysisHead(uri: Uri) {
        if (!::cache.isInitialized) {
            TrackLog.w(TAG, "analysis head for $uri not started: cache is not initialised")
            return
        }
        if (upstreamFactory == null) {
            TrackLog.w(TAG, "analysis head for $uri not started: no upstream yet")
            return
        }
        val videoId = uri.getQueryParameter("v") ?: return
        if (analysisHeads.putIfAbsent(videoId, true) != null) return
        if (!analysisHeadsInFlight.add(videoId)) return
        scope.launch {
            try {
                val total = runCatching { StreamResolver.contentLength(videoId) }
                    .onFailure {
                        TrackLog.w(TAG, "contentLength failed for $videoId: ${it.message}", videoId)
                    }
                    .getOrNull() ?: 0L
                val want = analysisHeadSize(total)
                analysisHeadWant[videoId] = want
                TrackLog.w(
                    TAG,
                    "analysis head round for $videoId: total=$total want=$want " +
                        "held=${alignmentPrefixBytes(videoId)}",
                    videoId,
                )
                if (!clearPartialHead(videoId, want)) {
                    TrackLog.w(TAG, "analysis head round for $videoId skipped: partial is held elsewhere", videoId)
                    return@launch
                }
                fetch(
                    cacheKey = videoId,
                    uri = Uri.parse(AutomixAnalysisSource.opusUri(videoId)),
                    position = 0,
                    length = want,
                    pinKey = true,
                )
                if (alignmentPrefixBytes(videoId) < MIN_ALIGNMENT_PREFIX_BYTES) {
                    pull(
                        videoId,
                        Uri.parse("nuvx://watch?v=$videoId"),
                        0,
                        want,
                        false,
                    )
                }
                if (total > 0) recordContentLength(videoId, total)
                val held = alignmentPrefixBytes(videoId)
                if (held < MIN_ALIGNMENT_PREFIX_BYTES) {
                    TrackLog.w(
                        TAG,
                        "analysis head for $videoId came up short: " +
                            "held=$held want=$want total=$total " +
                            "(cause in the read-ahead lines around this one)",
                        videoId,
                    )
                }
            } finally {
                analysisHeadsInFlight.remove(videoId)
            }
        }
    }

    private fun analysisHeadSize(total: Long): Long =
        if (total > 0) minOf(total, MAX_ANALYSIS_TRACK_BYTES) else MAX_ANALYSIS_HEAD_BYTES

    private fun clearPartialHead(videoId: String, want: Long): Boolean {
        val held = cache.getCachedBytes(videoId, 0, want)
        if (held <= 0L) return true
        if (held >= want) return false
        return runCatching { cache.removeResource(videoId) }
            .onSuccess {
                TrackLog.d(TAG, "cleared partial analysis head for $videoId ($held of $want)", about = videoId)
            }
            .onFailure {
                TrackLog.d(TAG, "partial head for $videoId is in use: ${it.message}", about = videoId)
            }
            .isSuccess
    }

    private fun recordContentLength(videoId: String, total: Long) {
        if (ContentMetadata.getContentLength(cache.getContentMetadata(videoId)) > 0) return
        if (total <= 0) return
        runCatching {
            cache.applyContentMetadataMutations(
                videoId,
                ContentMetadataMutations().apply { ContentMetadataMutations.setContentLength(this, total) },
            )
        }.onFailure { TrackLog.d(TAG, "could not record length for $videoId: ${it.message}", about = videoId) }
    }

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
        CacheMediaDataSource(
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(NoUpstream)
                .setCacheKeyFactory { rendition.key }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource(),
            uri,
        )

    fun mediaDataSource(uri: Uri): MediaDataSource? {
        if (!isFullyCached(uri)) return null
        return CacheMediaDataSource(cacheFactory(NoUpstream).createDataSource(), uri)
    }

    fun headMediaDataSource(uri: Uri): MediaDataSource? {
        if (cachedPrefixBytes(uri) <= 0L) return null
        return CacheMediaDataSource(cacheFactory(NoUpstream).createDataSource(), uri)
    }

    private object NoUpstream : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long =
                throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
            override fun getUri(): Uri? = null
            override fun close() {}
        }
    }

    private class CacheMediaDataSource(
        private val dataSource: DataSource,
        private val uri: Uri,
    ) : MediaDataSource() {
        private var isOpen = false
        private var openPosition = -1L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (!isOpen || position != openPosition) {
                closeUpstream()
                val available = try {
                    dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                } catch (error: IOException) {
                    return -1
                }
                isOpen = true
                openPosition = position
                if (available == 0L) return -1
            }
            val count = try {
                dataSource.read(buffer, offset, size)
            } catch (error: IOException) {
                closeUpstream()
                return -1
            }
            if (count == C.RESULT_END_OF_INPUT) {
                closeUpstream()
                return -1
            }
            openPosition += count
            return count
        }

        override fun getSize(): Long {
            closeUpstream()
            return try {
                dataSource.open(DataSpec(uri))
            } catch (error: IOException) {
                -1L
            } finally {
                closeUpstream()
            }
        }

        override fun close() {
            closeUpstream()
        }

        private fun closeUpstream() {
            if (isOpen) {
                runCatching { dataSource.close() }
                isOpen = false
            }
        }
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
