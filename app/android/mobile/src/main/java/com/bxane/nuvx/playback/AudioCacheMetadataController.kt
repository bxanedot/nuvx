package com.bxane.nuvx.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import com.bxane.nuvx.R
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.playback.smart.AutomixAnalysisSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class AudioCacheMetadataController(
    private val scope: CoroutineScope,
    private val cacheProvider: () -> SimpleCache,
    private val cacheInitialized: () -> Boolean,
    private val appContextProvider: () -> Context,
    private val keyFactory: CacheKeyFactory,
    private val cacheKeyBaseOf: (String) -> String,
) {
    private val cache: SimpleCache get() = cacheProvider()
    private val appContext: Context get() = appContextProvider()
    private companion object {
        const val TAG = "nuvx"
        const val MAX_NOTED_SONGS = 256
    }
    private val _contentsChanged = MutableStateFlow(0)

    val contentsChanged: StateFlow<Int> = _contentsChanged.asStateFlow()

    private val notedSongs = ConcurrentHashMap<String, Song>()

    private val playedIds = ConcurrentHashMap.newKeySet<String>()

    fun isKeptOrigin(key: String): Boolean = runCatching {
        val origin = cache.getContentMetadata(key).get(AudioCacheMetadataKeys.META_ORIGIN, null as String?)
        origin == AudioCache.Origin.YOUTUBE.name || origin == AudioCache.Origin.JIOSAAVN.name
    }.getOrDefault(false)

    fun originOf(uri: Uri): AudioCache.Origin? {
        if (!cacheInitialized() || uri.scheme != "nuvx") return null
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        return runCatching { cache.getContentMetadata(key).get(AudioCacheMetadataKeys.META_ORIGIN, null as String?) }
            .getOrNull()
            ?.let { name -> AudioCache.Origin.values().firstOrNull { it.name == name } }
    }

    fun heldByYouTube(key: String?): Boolean {
        if (key == null || !cacheInitialized()) return false
        return runCatching {
            cache.getCachedSpans(key).isNotEmpty() &&
                cache.getContentMetadata(key).get(AudioCacheMetadataKeys.META_ORIGIN, null as String?) == AudioCache.Origin.YOUTUBE.name
        }.getOrDefault(false)
    }

    fun noteSongs(songs: List<Song>) {
        val playable = songs.filter { it.localUri == null && it.videoId.isNotBlank() }
        if (playable.isEmpty()) return
        if (notedSongs.size > MAX_NOTED_SONGS) notedSongs.clear()
        playable.forEach { notedSongs[it.videoId] = it }
        if (!cacheInitialized()) return
        scope.launch {
            val keys = cache.keys
            playable.forEach { song ->
                val base = cacheKeyBaseOf(song.videoId)
                keys.filter { it == base || it.startsWith("$base#") }.forEach { key ->
                    runCatching {
                        val meta = cache.getContentMetadata(key)
                        if (meta.get(AudioCacheMetadataKeys.META_ORIGIN, null as String?) == null) return@runCatching
                        if (meta.get(AudioCacheMetadataKeys.META_TITLE, null as String?) != null &&
                            meta.get(AudioCacheMetadataKeys.META_ART, null as String?) != null
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
        if (!cacheInitialized() || uri?.scheme != "nuvx") return
        scope.launch {
            val key = keyFactory.buildCacheKey(DataSpec(uri))
            runCatching {
                val meta = cache.getContentMetadata(key)
                if (meta.get(AudioCacheMetadataKeys.META_ORIGIN, null as String?) == null) return@runCatching
                if (meta.get(AudioCacheMetadataKeys.META_PLAYED, null as String?) != null) return@runCatching
                cache.applyContentMetadataMutations(
                    key,
                    ContentMetadataMutations().set(AudioCacheMetadataKeys.META_PLAYED, "1"),
                )
            }
        }
    }

    fun recordServed(spec: DataSpec, servedUrl: String) {
        if (!cacheInitialized()) return
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
            val usedByPlayback = has(AudioCacheMetadataKeys.META_PLAYBACK) || !analysis
            val played = has(AudioCacheMetadataKeys.META_PLAYED) || (!analysis && mediaId in playedIds)
            val tagged = meta.get(AudioCacheMetadataKeys.META_ORIGIN, null as String?) == origin.name &&
                meta.get(AudioCacheMetadataKeys.META_MEDIA_ID, null as String?) == mediaId &&
                has(AudioCacheMetadataKeys.META_PLAYBACK) == usedByPlayback &&
                (usedByPlayback || has(AudioCacheMetadataKeys.META_ANALYSIS)) &&
                has(AudioCacheMetadataKeys.META_PLAYED) == played
            if (tagged && (song == null || has(AudioCacheMetadataKeys.META_TITLE))) return
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().also { mutations ->
                    mutations.set(AudioCacheMetadataKeys.META_ORIGIN, origin.name)
                    mutations.set(AudioCacheMetadataKeys.META_MEDIA_ID, mediaId)
                    if (usedByPlayback) mutations.set(AudioCacheMetadataKeys.META_PLAYBACK, "1") else mutations.set(AudioCacheMetadataKeys.META_ANALYSIS, "1")
                    if (played) mutations.set(AudioCacheMetadataKeys.META_PLAYED, "1")
                    describe(mutations, song, spec.uri)
                },
            )
        }.onFailure {
            TrackLog.d(TAG, "could not tag cache entry $key: ${it.message}", about = mediaId)
        }
    }

    private fun originOf(uri: Uri, mediaId: String, servedUrl: String): AudioCache.Origin {
        val host = runCatching { Uri.parse(servedUrl).host }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()
        if (host.endsWith("googlevideo.com") || host.endsWith("youtube.com")) return AudioCache.Origin.YOUTUBE
        if (host.endsWith("saavncdn.com") || host.endsWith("jiosaavn.com")) return AudioCache.Origin.JIOSAAVN
        val upgrade = QualityUpgrade.forcedStream(uri)
        val configId = when {
            upgrade != null -> upgrade.sourceConfigId
            uri.getQueryParameter("s") != null -> uri.getQueryParameter("s")
            else -> StreamChoice.of(mediaId)?.sourceConfigId
        }
        val kind = configId?.let { SourceRegistry.config(it)?.kind }
        return if (kind == SourceKind.JIOSAAVN) AudioCache.Origin.JIOSAAVN else AudioCache.Origin.OTHER
    }

    private fun describe(mutations: ContentMetadataMutations, song: Song?, uri: Uri?) {
        val title = song?.title?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("n")
        val artist = song?.artist?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("a")
        val album = song?.albumName?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("l")
        val duration = song?.durationText
            ?: uri?.getQueryParameter("d")?.toIntOrNull()?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) }
        title?.takeIf { it.isNotBlank() }?.let { mutations.set(AudioCacheMetadataKeys.META_TITLE, it) }
        artist?.takeIf { it.isNotBlank() }?.let { mutations.set(AudioCacheMetadataKeys.META_ARTIST, it) }
        album?.takeIf { it.isNotBlank() }?.let { mutations.set(AudioCacheMetadataKeys.META_ALBUM, it) }
        duration?.takeIf { it.isNotBlank() }?.let { mutations.set(AudioCacheMetadataKeys.META_DURATION, it) }
        song?.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { mutations.set(AudioCacheMetadataKeys.META_ART, it) }
    }

    suspend fun cachedSongs(): List<AudioCache.CachedSong> = withContext(Dispatchers.IO) {
        if (!cacheInitialized()) return@withContext emptyList()
        class Entry(
            val origin: AudioCache.Origin,
            val meta: ContentMetadata,
            val bytes: Long,
            val touched: Long,
            val complete: Boolean,
        )
        val byTrack = LinkedHashMap<String, MutableList<Entry>>()
        for (key in cache.keys) {
            val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: continue
            val origin = meta.get(AudioCacheMetadataKeys.META_ORIGIN, null as String?)
                ?.let { runCatching { AudioCache.Origin.valueOf(it) }.getOrNull() }
                ?: continue
            if (meta.get(AudioCacheMetadataKeys.META_PLAYED, null as String?) == null) continue
            val mediaId = meta.get(AudioCacheMetadataKeys.META_MEDIA_ID, null as String?) ?: continue
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
            val art = field(AudioCacheMetadataKeys.META_ART) ?: noted?.thumbnailUrl
                ?: mediaId.takeIf { SourceRegistry.parseTrackKey(it) == null }
                    ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            AudioCache.CachedSong(
                song = Song(
                    videoId = mediaId,
                    title = field(AudioCacheMetadataKeys.META_TITLE) ?: noted?.title?.takeIf(String::isNotBlank) ?: mediaId,
                    artist = field(AudioCacheMetadataKeys.META_ARTIST) ?: noted?.artist.orEmpty(),
                    thumbnailUrl = art,
                    durationText = field(AudioCacheMetadataKeys.META_DURATION) ?: noted?.durationText,
                    albumName = field(AudioCacheMetadataKeys.META_ALBUM) ?: noted?.albumName,
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

    fun keysOf(mediaId: String): List<String> {
        val base = cacheKeyBaseOf(mediaId)
        return cache.keys.filter { key ->
            key == base || key.startsWith("$base#") ||
                runCatching { cache.getContentMetadata(key).get(AudioCacheMetadataKeys.META_MEDIA_ID, null as String?) }
                    .getOrNull() == mediaId
        }
    }

    private val AudioCache.Origin.label: String
        get() = when (this) {
            AudioCache.Origin.YOUTUBE -> SourceKind.YOUTUBE.label
            AudioCache.Origin.JIOSAAVN -> SourceKind.JIOSAAVN.label
            AudioCache.Origin.OTHER -> "Addon"
        }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(Locale.ROOT, mb / 1024) else "%.1f MB".format(Locale.ROOT, mb)
    }


    fun forgetPlayed(mediaId: String) {
        playedIds.remove(mediaId)
    }

    fun publishContentsChanged() {
        _contentsChanged.value++
    }
}

internal object AudioCacheMetadataKeys {
    const val META_ORIGIN = "nx_origin"
    const val META_MEDIA_ID = "nx_media_id"
    const val META_TITLE = "nx_title"
    const val META_ARTIST = "nx_artist"
    const val META_ALBUM = "nx_album"
    const val META_DURATION = "nx_duration"
    const val META_ART = "nx_art"

    const val META_PLAYBACK = "nx_playback"

    const val META_ANALYSIS = "nx_analysis"

    const val META_PLAYED = "nx_played"
}
