package com.bxane.nuvx.download

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.bxane.nuvx.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.DownloadQuality
import com.bxane.nuvx.playback.AudioCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.io.File
import java.util.Locale

sealed interface DownloadState {

    data object Queued : DownloadState

    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

object Downloads {

    private const val TAG = "nuvx"
    private const val KEY_SAVED_METADATA = "downloaded_tracks_metadata"
    private const val KEY_SAVED_COLLECTIONS = "downloaded_collections"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val metadataSerializer = MapSerializer(String.serializer(), SavedSongMetadata.serializer())
    private val collectionSerializer = MapSerializer(String.serializer(), SavedCollection.serializer())

    private val _active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadState>> = _active.asStateFlow()

    private val _requested = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val requested: StateFlow<Map<String, Set<String>>> = _requested.asStateFlow()

    fun markRequested(browseId: String, videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        _requested.update { it + (browseId to (it[browseId].orEmpty() + videoIds)) }
    }

    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())

    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _savedMetadata = MutableStateFlow<Map<String, SavedSongMetadata>>(emptyMap())

    private val _collections = MutableStateFlow<Map<String, SavedCollection>>(emptyMap())

    val collections: StateFlow<Map<String, SavedCollection>> = _collections.asStateFlow()

    private val pending = LinkedHashMap<String, Song>()

    private val lock = Any()

    private val running = LinkedHashMap<String, Job?>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("nuvx_settings", Context.MODE_PRIVATE)
        _saved.value = runCatching {
            json.decodeFromString(serializer, prefs.getString(KEY_SAVED, null) ?: "{}")
        }.getOrDefault(emptyMap())
        _savedMetadata.value = runCatching {
            json.decodeFromString(metadataSerializer, prefs.getString(KEY_SAVED_METADATA, null) ?: "{}")
        }.getOrDefault(emptyMap())
        _collections.value = runCatching {
            json.decodeFromString(collectionSerializer, prefs.getString(KEY_SAVED_COLLECTIONS, null) ?: "{}")
        }.getOrDefault(emptyMap())
    }

    fun enqueue(context: Context, song: Song, from: String? = null) {
        val id = song.videoId
        if (!AppSettings.downloadsAllowedNow && !servableFromCache(id)) {
            val inFlight = _active.value[id]
            if (inFlight !is DownloadState.Queued && inFlight !is DownloadState.Running) {
                DownloadSession.queued(song, from)
                fail(id, WIFI_ONLY_REFUSAL)
            }
            return
        }
        synchronized(lock) {
            if (id in pending || id in running) return
            pending[id] = song
        }
        _active.update { it + (id to DownloadState.Queued) }
        DownloadSession.queued(song, from)

        val app = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        }.onFailure {
            Log.w(TAG, "could not start the download service: ${it.message}")
            synchronized(lock) { pending.remove(id) }
            fail(id, "Downloads can't start right now")
        }
    }

    private val fromCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun preferCachedCopy(videoIds: Collection<String>) {
        fromCache += videoIds
    }

    fun servableFromCache(videoId: String): Boolean =
        videoId in fromCache && AudioCache.completeCopyOf(videoId)?.let(::cachedRoute) != null

    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            if (videoId !in running) return@synchronized null
            running.remove(videoId)
        }
        job?.cancel()
        clear(videoId)
        DownloadSession.forget(videoId)
    }

    internal suspend fun downloadNow(context: Context, songs: List<Song>, from: String?): Set<String> {
        val handled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val claimed = synchronized(lock) {
            songs.filter { song ->
                val id = song.videoId
                if (id in pending || id in running) {
                    handled += id
                    false
                } else {
                    running[id] = null
                    true
                }
            }
        }
        claimed.forEach { song ->
            _active.update { it + (song.videoId to DownloadState.Queued) }
            DownloadSession.queued(song, from)
        }
        val slots = Semaphore(BATCH_WORKERS)
        try {
            coroutineScope {
                claimed.forEach { song ->
                    launch {
                        slots.withPermit {
                            val id = song.videoId
                            if (synchronized(lock) { id !in running }) {
                                handled += id
                                return@withPermit
                            }
                            if (!AppSettings.downloadsAllowedNow) return@withPermit
                            val job = launch { run(context, song) }
                            onRunning(id, job)
                            job.join()
                            val calledOff = synchronized(lock) { id !in running }
                            if (calledOff || id in _saved.value) handled += id
                        }
                    }
                }
            }
        } finally {
            claimed.forEach { song ->
                val id = song.videoId
                onIdle(id)
                val state = _active.value[id]
                if (state is DownloadState.Queued || state is DownloadState.Running) {
                    clear(id)
                    DownloadSession.forget(id)
                }
            }
        }
        return handled
    }

    suspend fun savedUri(context: Context, videoId: String): Uri? = withContext(Dispatchers.IO) {
        val recorded = _saved.value[videoId] ?: return@withContext null
        val uri = recorded.toUri()
        if (DownloadStore.exists(context, uri)) return@withContext uri
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        null
    }

    fun isMissingLocalFile(uriString: String): Boolean {
        if (!uriString.startsWith("file://")) return false
        val path = runCatching { uriString.toUri().path }.getOrNull() ?: return false
        return !File(path).exists()
    }

    fun verifiedSavedUri(videoId: String): String? {
        val recorded = _saved.value[videoId] ?: return null
        if (!isMissingLocalFile(recorded)) return recorded
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        return null
    }

    suspend fun delete(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        val uri = _saved.value[videoId]?.toUri() ?: return@withContext false
        val deleted = DownloadStore.delete(context, uri)
        forget(videoId)
        deleted
    }

    private fun forget(videoId: String) {
        record(saved = { it - videoId }, meta = { it - videoId })
    }

    fun forgetMissing(videoId: String) {
        if (videoId !in _saved.value) return
        Log.d(TAG, "$videoId could not be opened; forgetting the download")
        forget(videoId)
    }

    fun rememberCollection(target: DownloadTarget, songs: List<Song>) {
        if (songs.isEmpty()) return
        val ids = songs.map { it.videoId }.distinct()
        recordCollections { current ->
            val existing = current[target.id]
            val record = SavedCollection(
                id = target.id,
                title = target.title,
                subtitle = target.subtitle,
                thumbnailUrl = existing?.thumbnailUrl?.takeIf(::isLocalArtwork)
                    ?: target.thumbnailUrl
                    ?: existing?.thumbnailUrl,
                playlist = target.playlist,
                artist = target.artist,
                videoIds = (ids + (existing?.videoIds ?: emptyList())).distinct(),
            )
            current + (target.id to record)
        }
    }

    fun rememberCollectionArtwork(id: String, thumbnailUrl: String) {
        recordCollections { current ->
            val existing = current[id] ?: return@recordCollections current
            if (existing.thumbnailUrl == thumbnailUrl) current
            else current + (id to existing.copy(thumbnailUrl = thumbnailUrl))
        }
    }

    private fun isLocalArtwork(url: String): Boolean =
        url.startsWith("file:") || url.startsWith("content:")

    fun forgetCollection(id: String) {
        if (id !in _collections.value) return
        recordCollections { it - id }
    }

    suspend fun deleteCollection(context: Context, id: String): Boolean {
        var any = false
        val collections = _collections.value
        tracksOnlyIn(id, collections, saved.value)
            .forEach { videoId -> if (delete(context, videoId)) any = true }
        droppedWith(id, collections).forEach(::forgetCollection)
        return any
    }

    private fun droppedWith(id: String, collections: Map<String, SavedCollection>): Set<String> {
        val record = collections[id] ?: return emptySet()
        if (!record.artist) return setOf(id)
        val catalogue = record.videoIds.toHashSet()
        return collections.values
            .filter { it.id == id || (!it.artist && !it.playlist && it.videoIds.isNotEmpty() && catalogue.containsAll(it.videoIds)) }
            .mapTo(HashSet()) { it.id }
    }

    internal fun tracksOnlyIn(
        id: String,
        collections: Map<String, SavedCollection>,
        saved: Map<String, String>,
    ): List<String> {
        val record = collections[id] ?: return emptyList()
        val dropped = droppedWith(id, collections)
        val others = collections.values
            .filter { it.id !in dropped && (record.artist || !it.artist) }
            .flatMapTo(HashSet()) { it.videoIds }
        val otherFiles = others.mapNotNullTo(HashSet()) { saved[it] }
        return dropped.flatMap { collections.getValue(it).videoIds }.distinct()
            .filter { it !in others && saved[it] !in otherFiles }
    }

    const val PLAYLIST_PREFIX = "local:playlist:"

    fun pageIdFor(id: String): String = PLAYLIST_PREFIX + id

    fun recordIdOf(browseId: String): String? =
        browseId.removePrefix(PLAYLIST_PREFIX).takeIf { it != browseId && it.isNotEmpty() && !it.startsWith("sp_local_") }

    fun savedReleases(onDisk: Map<String, String> = _saved.value): List<SavedCollection> {
        if (_collections.value.isEmpty()) return emptyList()
        return _collections.value.values
            .filter { record -> record.videoIds.any { it in onDisk } }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun collectionsAmong(songs: List<Song>): List<DownloadedCollection> {
        if (songs.isEmpty() || _collections.value.isEmpty()) return emptyList()
        val byId = songs.associateBy { it.videoId }
        val byUri = songs.mapNotNull { song -> song.localUri?.let { it to song } }.toMap()
        val uris = _saved.value
        return _collections.value.values
            .mapNotNull { record ->
                val tracks = record.videoIds
                    .mapNotNull { id -> byId[id] ?: uris[id]?.let(byUri::get) }
                    .distinctBy { it.localUri ?: it.videoId }
                if (tracks.isEmpty()) {
                    null
                } else {
                    DownloadedCollection(
                        id = record.id,
                        title = record.title,
                        subtitle = record.subtitle,
                        thumbnailUrl = record.thumbnailUrl ?: tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        playlist = record.playlist,
                        songs = tracks,
                        artist = record.artist,
                    )
                }
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun recordCollections(update: (Map<String, SavedCollection>) -> Map<String, SavedCollection>) {
        synchronized(collectionLock) {
            val map = update(_collections.value)
            _collections.value = map
            if (::prefs.isInitialized) {
                prefs.edit()
                    .putString(KEY_SAVED_COLLECTIONS, json.encodeToString(collectionSerializer, map))
                    .apply()
            }
        }
    }

    private val collectionLock = Any()

    private fun remember(
        asked: Song,
        fetched: Song,
        uri: Uri,
        downloadFormat: String? = null,
        artworkUri: String? = null,
    ) {
        val ids = setOf(asked.videoId, fetched.videoId)
        val fileModifiedMillis = uri.takeIf { it.scheme == "file" }
            ?.path
            ?.let(::File)
            ?.lastModified()
            ?.takeIf { it > 0 }
        val existingAdded = ids.firstNotNullOfOrNull { _savedMetadata.value[it]?.dateAddedSeconds }
        val dateAddedSeconds = resolvedDownloadDates(existingAdded, fileModifiedMillis).first
            ?: System.currentTimeMillis() / 1_000
        val savedArtwork = uri.takeIf { it.scheme == "file" && it.lastPathSegment == "playlist.m3u8" }
            ?.path?.let(::File)?.parentFile
            ?.listFiles()?.firstOrNull { it.nameWithoutExtension == "cover" }
            ?.let(Uri::fromFile)?.toString()
        val album = fetched.albumName?.takeIf { it.isNotBlank() }
            ?: asked.albumName?.takeIf { it.isNotBlank() }
        val metaAsked = SavedSongMetadata(
            videoId = asked.videoId,
            title = asked.title,
            artist = asked.artist,
            thumbnailUrl = savedArtwork ?: artworkUri ?: asked.thumbnailUrl,
            durationText = asked.durationText,
            albumName = album,
            uri = uri.toString(),
            downloadFormat = downloadFormat,
            dateAddedSeconds = dateAddedSeconds,
        )
        val metaFetched = SavedSongMetadata(
            videoId = fetched.videoId,
            title = fetched.title,
            artist = fetched.artist,
            thumbnailUrl = savedArtwork ?: artworkUri ?: fetched.thumbnailUrl,
            durationText = fetched.durationText,
            albumName = album,
            uri = uri.toString(),
            downloadFormat = downloadFormat,
            dateAddedSeconds = dateAddedSeconds,
        )
        record(
            saved = { it + ids.associateWith { id -> uri.toString() } },
            meta = {
                it + mapOf(asked.videoId to metaAsked, fetched.videoId to metaFetched)
            },
        )
    }

    private fun record(
        saved: (Map<String, String>) -> Map<String, String>,
        meta: (Map<String, SavedSongMetadata>) -> Map<String, SavedSongMetadata>,
    ) {
        val savedMap = _saved.updateAndGet(saved)
        val metaMap = _savedMetadata.updateAndGet(meta)
        if (!::prefs.isInitialized) return
        synchronized(recordLock) {
            prefs.edit()
                .putString(KEY_SAVED, json.encodeToString(serializer, savedMap))
                .putString(KEY_SAVED_METADATA, json.encodeToString(metadataSerializer, metaMap))
                .apply()
        }
    }

    private val recordLock = Any()

    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val metaMap = _savedMetadata.value
        val result = mutableListOf<Song>()
        val seenUris = mutableSetOf<String>()
        val migratedAddedByUri = mutableMapOf<String, Long>()
        val migratedFormatByUri = mutableMapOf<String, String>()

        for ((_, meta) in metaMap) {
            if (!seenUris.add(meta.uri)) continue
            val uri = meta.uri.toUri()
            if (DownloadStore.exists(context, uri)) {
                val fileModifiedMillis = uri.takeIf { it.scheme == "file" }
                    ?.path
                    ?.let(::File)
                    ?.lastModified()
                    ?.takeIf { it > 0 }
                val (dateAddedSeconds, dateModifiedSeconds) = resolvedDownloadDates(
                    persistedAddedSeconds = meta.dateAddedSeconds,
                    fileModifiedMillis = fileModifiedMillis,
                )
                if (meta.dateAddedSeconds == null && dateAddedSeconds != null) {
                    migratedAddedByUri[meta.uri] = dateAddedSeconds
                }
                val downloadFormat = meta.downloadFormat
                    ?: legacyYoutubeDownloadBadge(context, uri)?.also {
                        migratedFormatByUri[meta.uri] = it
                    }
                result.add(
                    Song(
                        videoId = meta.videoId,
                        title = meta.title,
                        artist = meta.artist,
                        thumbnailUrl = meta.thumbnailUrl,
                        durationText = meta.durationText,
                        albumName = meta.albumName,
                        localUri = meta.uri,
                        downloadFormat = downloadFormat,
                        localDateAddedSeconds = dateAddedSeconds,
                        localDateModifiedSeconds = dateModifiedSeconds,
                    )
                )
            } else {
                metaMap.filterValues { it.uri == meta.uri }.keys.forEach(::forget)
            }
        }
        if (migratedAddedByUri.isNotEmpty() || migratedFormatByUri.isNotEmpty()) {
            record(
                saved = { it },
                meta = { current ->
                    current.mapValues { (_, saved) ->
                        saved.copy(
                            dateAddedSeconds = saved.dateAddedSeconds
                                ?: migratedAddedByUri[saved.uri],
                            downloadFormat = saved.downloadFormat
                                ?: migratedFormatByUri[saved.uri],
                        )
                    }
                },
            )
        }
        result
    }

    suspend fun getCollectionSongs(context: Context, collectionId: String): List<Song> =
        withContext(Dispatchers.IO) {
            val record = _collections.value[collectionId] ?: return@withContext emptyList()
            val metadata = _savedMetadata.value
            val saved = _saved.value
            val metadataByUri = metadata.values.associateBy { it.uri }
            val seenUris = HashSet<String>()

            record.videoIds.mapNotNull { videoId ->
                val uriString = saved[videoId] ?: metadata[videoId]?.uri ?: return@mapNotNull null
                if (!seenUris.add(uriString)) return@mapNotNull null
                val uri = uriString.toUri()
                if (!DownloadStore.exists(context, uri)) {
                    saved.filterValues { it == uriString }.keys.forEach(::forget)
                    return@mapNotNull null
                }

                val meta = metadata[videoId] ?: metadataByUri[uriString] ?: return@mapNotNull null
                val fileModifiedMillis = uri.takeIf { it.scheme == "file" }
                    ?.path
                    ?.let(::File)
                    ?.lastModified()
                    ?.takeIf { it > 0 }
                val (dateAddedSeconds, dateModifiedSeconds) = resolvedDownloadDates(
                    persistedAddedSeconds = meta.dateAddedSeconds,
                    fileModifiedMillis = fileModifiedMillis,
                )
                Song(
                    videoId = videoId,
                    title = meta.title,
                    artist = meta.artist,
                    thumbnailUrl = meta.thumbnailUrl,
                    durationText = meta.durationText,
                    albumName = meta.albumName,
                    localUri = uriString,
                    downloadFormat = meta.downloadFormat ?: legacyYoutubeDownloadBadge(context, uri),
                    localDateAddedSeconds = dateAddedSeconds,
                    localDateModifiedSeconds = dateModifiedSeconds,
                )
            }
        }

    private fun String.toUri(): Uri = Uri.parse(this)

    internal fun takeNext(): Song? = synchronized(lock) {
        val entry = pending.entries.firstOrNull() ?: return null
        pending.remove(entry.key)
        running[entry.key] = null
        entry.value
    }

    internal fun onRunning(videoId: String, job: Job) {
        val cancelled = synchronized(lock) {
            if (videoId !in running) return@synchronized true
            running[videoId] = job
            false
        }
        if (cancelled) job.cancel()
    }

    internal fun onIdle(videoId: String) {
        synchronized(lock) { running.remove(videoId) }
    }

    internal fun busy(): Boolean = synchronized(lock) { pending.isNotEmpty() || running.isNotEmpty() }

    internal fun onStopped() {
        synchronized(lock) { running.clear() }
    }

    internal suspend fun run(context: Context, song: Song) = withContext(Dispatchers.IO) {
        val id = song.videoId
        _active.update { it + (id to DownloadState.Running(0f)) }
        DownloadSession.running(id, 0f)

        try {
            val plan = prepare(context, song)
            DownloadSession.retitle(id, plan.track)
            transfer(context, song, plan)
        } catch (e: CancellationException) {
            clear(id)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed for $id: ${e.message}", e)
            fail(id, e.friendly())
        }
    }

    internal suspend fun prepare(context: Context, song: Song): Prepared = withContext(Dispatchers.IO) {
        val track = song
        val quality = AppSettings.downloadQuality.value

        LOSSLESS_EXTENSIONS.firstNotNullOfOrNull { extension ->
            DownloadStore.existing(context, DownloadStore.fileNameFor(track, extension))
        }?.let { uri ->
            return@withContext Prepared(song.videoId, track, route = null, alreadyAt = uri)
        }

        if (fromCache.remove(song.videoId)) {
            AudioCache.completeCopyOf(song.videoId)?.let(::cachedRoute)?.let { route ->
                Log.d(TAG, "downloading ${song.videoId} from the song cache as .${route.extension}")
                return@withContext Prepared(song.videoId, track, route = route, alreadyAt = null)
            }
        }
        val route = try {
            routeFor(context, track, quality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AudioCache.completeCopyOf(song.videoId)?.let(::cachedRoute)
                ?.also { Log.w(TAG, "${song.videoId} could not be fetched (${e.message}); saving the cached copy") }
                ?: throw e
        }
        Log.d(TAG, "downloading ${song.videoId} as .${route.extension} (${route.describe}, ${quality.label})")
        Prepared(song.videoId, track, route = route, alreadyAt = null)
    }

    internal class Prepared(
        val videoId: String,
        val track: Song,
        val route: Route?,
        val alreadyAt: Uri?,
    )

    private suspend fun transfer(context: Context, song: Song, plan: Prepared) {
        val id = song.videoId
        val track = plan.track

        plan.alreadyAt?.let { uri ->
            remember(song, track, uri)
            DownloadSession.done(id)
            clear(id)
            return
        }
        val route = plan.route ?: error("Nothing to download")

        var pending: DownloadStore.Pending? = null
        var lyrics: Deferred<LyricsTag.Embeddable?>? = null
        var artwork: Deferred<MediaTagger.Artwork?>? = null
        try {
            coroutineScope {
                if (route.taggable && (route.offlineHls != null || MediaTagger.carriesTags(route.extension))) {
                    lyrics = async { LyricsTag.forTrack(track) }
                    artwork = async { MediaTagger.artworkFor(context, track) }
                }

                val name = DownloadStore.fileNameFor(track, route.extension)
                val alreadyThere = DownloadStore.existing(context, name)
                if (alreadyThere != null) {
                    Log.d(TAG, "$name is already in Music; adopting it")
                    remember(song, track, alreadyThere)
                    DownloadSession.done(id)
                    clear(id)
                    return@coroutineScope
                }

                val manifest = route.offlineHls
                if (manifest != null) {
                    val onSegment: (Long, Long) -> Unit = { written, total ->
                        val fraction = written.toFloat() / total
                        _active.update { it + (id to DownloadState.Running(fraction)) }
                        DownloadSession.running(id, fraction)
                    }
                    val savedUri = if (manifest.dash) {
                        OfflineDash.save(
                            context = context,
                            id = id,
                            url = manifest.url,
                            headers = manifest.headers,
                            onProgress = onSegment,
                            lyrics = lyrics?.await(),
                            artwork = artwork?.await(),
                        )
                    } else {
                        OfflineHls.save(
                            context = context,
                            id = id,
                            url = manifest.url,
                            headers = manifest.headers,
                            onProgress = onSegment,
                            lyrics = lyrics?.await(),
                            artwork = artwork?.await(),
                        )
                    }
                    remember(song, track, savedUri, route.downloadFormat)
                    DownloadSession.done(id)
                    clear(id)
                    Log.d(TAG, "saved offline ${if (manifest.dash) "DASH" else "HLS"} package for $name")
                    return@coroutineScope
                }

                val destination = DownloadStore.begin(context, name, route.mimeType)
                pending = destination
                destination.openStream().use { sink ->
                    route.write(sink) { written, total ->
                        val fraction = written.toFloat() / total
                        _active.update { it + (id to DownloadState.Running(fraction)) }
                        DownloadSession.running(id, fraction)
                    }
                }
                val words = lyrics?.await()
                val cover = artwork?.await()
                MediaTagger.embed(context, destination.tagUri, track, route.extension, words, cover)
                val artworkUri = MediaTagger.persistArtwork(context, track.thumbnailUrl, cover)
                val savedUri = destination.commit()
                pending = null
                remember(song, track, savedUri, route.downloadFormat, artworkUri)
                DownloadSession.done(id)
                clear(id)
                Log.d(TAG, "saved $name")
            }
        } catch (e: Throwable) {
            pending?.abort()
            throw e
        } finally {
            lyrics?.cancel()
            artwork?.cancel()
        }
    }

    internal class Route(
        val extension: String,
        val mimeType: String,
        val describe: String,
        val downloadFormat: String? = null,
        val taggable: Boolean = true,
        val offlineHls: Manifest? = null,
        val write: suspend (OutputStream, (written: Long, total: Long) -> Unit) -> Unit,
    )

    internal class Manifest(
        val url: String,
        val headers: Map<String, String>,
        val dash: Boolean = false,
    )

    private suspend fun routeFor(context: Context, track: Song, quality: DownloadQuality): Route =
        DownloadRouteResolver.routeFor(context, track, quality)

    private fun cachedRoute(copy: AudioCache.CompleteCopy): Route? =
        DownloadRouteResolver.cachedRoute(copy)

    internal fun estimatedKbps(totalBytes: Long?, durationSec: Int?): Int? =
        DownloadRouteResolver.estimatedKbps(totalBytes, durationSec)

    private fun clear(videoId: String) {
        _active.update { it - videoId }
    }

    private fun fail(videoId: String, reason: String) {
        _active.update { it + (videoId to DownloadState.Failed(reason)) }
        DownloadSession.failed(videoId, reason)
    }

    private fun Exception.friendly(): String = when {
        (this is IllegalStateException || this is IllegalArgumentException) &&
            !message.isNullOrBlank() -> message!!
        else -> "Download failed — check your connection"
    }

    private val LOSSLESS_EXTENSIONS = listOf("flac", "wav")

    internal const val WIFI_ONLY_REFUSAL = "Downloads are set to Wi-Fi only"

    private const val BATCH_WORKERS = 4

    fun dismissFailure(videoId: String) {
        if (_active.value[videoId] is DownloadState.Failed) clear(videoId)
    }

    private const val KEY_SAVED = "downloaded_tracks"
}
