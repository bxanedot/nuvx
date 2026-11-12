package com.bxane.nuvx.download

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

internal class DownloadLibraryController {
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val metadataSerializer = MapSerializer(String.serializer(), SavedSongMetadata.serializer())
    private val collectionSerializer = MapSerializer(String.serializer(), SavedCollection.serializer())

    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _savedMetadata = MutableStateFlow<Map<String, SavedSongMetadata>>(emptyMap())

    private val _collections = MutableStateFlow<Map<String, SavedCollection>>(emptyMap())
    val collections: StateFlow<Map<String, SavedCollection>> = _collections.asStateFlow()

    private val collectionLock = Any()
    private val recordLock = Any()

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
        if (deleted) forget(videoId)
        deleted
    }

    fun forget(videoId: String) {
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
        val currentCollections = _collections.value
        DownloadCollectionCatalog.tracksOnlyIn(id, currentCollections, saved.value)
            .forEach { videoId -> if (delete(context, videoId)) any = true }
        DownloadCollectionCatalog.droppedWith(id, currentCollections).forEach(::forgetCollection)
        return any
    }

    fun savedReleases(onDisk: Map<String, String>): List<SavedCollection> =
        DownloadCollectionCatalog.savedReleases(_collections.value, onDisk)

    fun collectionsAmong(songs: List<Song>): List<DownloadedCollection> =
        DownloadCollectionCatalog.collectionsAmong(_collections.value, _saved.value, songs)

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

    internal fun remember(
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
        synchronized(recordLock) {
            val savedMap = saved(_saved.value)
            val metaMap = meta(_savedMetadata.value)
            _saved.value = savedMap
            _savedMetadata.value = metaMap
            if (::prefs.isInitialized) {
                prefs.edit()
                    .putString(KEY_SAVED, json.encodeToString(serializer, savedMap))
                    .putString(KEY_SAVED_METADATA, json.encodeToString(metadataSerializer, metaMap))
                    .apply()
            }
        }
    }

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
            val collection = _collections.value[collectionId] ?: return@withContext emptyList()
            val metadata = _savedMetadata.value
            val saved = _saved.value
            val metadataByUri = metadata.values.associateBy { it.uri }
            val seenUris = HashSet<String>()

            collection.videoIds.mapNotNull { videoId ->
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

    private companion object {
        const val TAG = "nuvx"
        const val KEY_SAVED = "downloaded_tracks"
        const val KEY_SAVED_METADATA = "downloaded_tracks_metadata"
        const val KEY_SAVED_COLLECTIONS = "downloaded_collections"
    }
}
