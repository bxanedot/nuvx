package com.licht.nuvx.download

import android.content.Context
import android.net.Uri
import com.licht.nuvx.data.DebugLog as Log
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.settings.AppSettings
import com.licht.nuvx.data.settings.DownloadQuality
import com.licht.nuvx.playback.AudioCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.OutputStream

sealed interface DownloadState {

    data object Queued : DownloadState

    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

object Downloads {

    private const val TAG = "nuvx"
    private val library = DownloadLibraryController()

    private val _active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadState>> = _active.asStateFlow()

    private val _requested = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val requested: StateFlow<Map<String, Set<String>>> = _requested.asStateFlow()

    fun markRequested(browseId: String, videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        _requested.update { it + (browseId to (it[browseId].orEmpty() + videoIds)) }
    }

    val saved: StateFlow<Map<String, String>> = library.saved

    val collections: StateFlow<Map<String, SavedCollection>> = library.collections

    private var queueFile: java.io.File? = null
    private val resumed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun init(context: Context) {
        queueFile = java.io.File(context.filesDir, "download_queue.json")
        library.init(context)
    }

    /** Re-queues downloads that were waiting when Android last killed the app. Call from a visible activity. */
    fun resumeSaved(context: Context) {
        val file = queueFile ?: return
        if (!resumed.compareAndSet(false, true)) return
        val songs = runCatching { SavedDownloadQueue.read(file) }.getOrDefault(emptyList())
        if (songs.isNotEmpty()) Log.d(TAG, "resuming ${songs.size} downloads")
        songs.forEach { enqueue(context, it) }
    }

    private val fromCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val queueController = DownloadQueueController(
        active = _active,
        downloadsAllowedNow = { AppSettings.downloadsAllowedNow },
        servableFromCache = ::servableFromCache,
        run = ::run,
        clear = ::clear,
        fail = ::fail,
        onQueueChanged = { songs ->
            queueFile?.let { file ->
                runCatching { SavedDownloadQueue.write(file, songs) }
                    .onFailure { Log.w(TAG, "could not save the download queue: ${it.message}") }
            }
        },
    )

    fun enqueue(context: Context, song: Song, from: String? = null) =
        queueController.enqueue(context, song, from)

    fun preferCachedCopy(videoIds: Collection<String>) {
        fromCache += videoIds
    }

    fun servableFromCache(videoId: String): Boolean =
        videoId in fromCache && AudioCache.completeCopyOf(videoId)?.let(::cachedRoute) != null

    fun cancel(videoId: String) = queueController.cancel(videoId)

    internal suspend fun downloadNow(context: Context, songs: List<Song>, from: String?): Set<String> =
        queueController.downloadNow(context, songs, from)

    suspend fun savedUri(context: Context, videoId: String): Uri? = library.savedUri(context, videoId)

    fun isMissingLocalFile(uriString: String): Boolean = library.isMissingLocalFile(uriString)

    fun verifiedSavedUri(videoId: String): String? = library.verifiedSavedUri(videoId)

    suspend fun delete(context: Context, videoId: String): Boolean = library.delete(context, videoId)

    fun forgetMissing(videoId: String) = library.forgetMissing(videoId)

    fun rememberCollection(target: DownloadTarget, songs: List<Song>) = library.rememberCollection(target, songs)

    fun rememberCollectionArtwork(id: String, thumbnailUrl: String) =
        library.rememberCollectionArtwork(id, thumbnailUrl)

    fun forgetCollection(id: String) = library.forgetCollection(id)

    suspend fun deleteCollection(context: Context, id: String): Boolean = library.deleteCollection(context, id)

    internal fun tracksOnlyIn(
        id: String,
        collections: Map<String, SavedCollection>,
        saved: Map<String, String>,
    ): List<String> = DownloadCollectionCatalog.tracksOnlyIn(id, collections, saved)

    const val PLAYLIST_PREFIX = "local:playlist:"

    fun pageIdFor(id: String): String = PLAYLIST_PREFIX + id

    fun recordIdOf(browseId: String): String? =
        browseId.removePrefix(PLAYLIST_PREFIX).takeIf { it != browseId && it.isNotEmpty() && !it.startsWith("sp_local_") }

    fun savedReleases(onDisk: Map<String, String> = saved.value): List<SavedCollection> =
        library.savedReleases(onDisk)

    fun collectionsAmong(songs: List<Song>): List<DownloadedCollection> = library.collectionsAmong(songs)

    private fun remember(
        asked: Song,
        fetched: Song,
        uri: Uri,
        downloadFormat: String? = null,
        artworkUri: String? = null,
    ) = library.remember(asked, fetched, uri, downloadFormat, artworkUri)

    suspend fun getDownloadedSongs(context: Context): List<Song> = library.getDownloadedSongs(context)

    suspend fun getCollectionSongs(context: Context, collectionId: String): List<Song> =
        library.getCollectionSongs(context, collectionId)

    internal fun takeNext(): Song? = queueController.takeNext()

    internal fun onRunning(videoId: String, job: kotlinx.coroutines.Job) = queueController.onRunning(videoId, job)

    internal fun onIdle(videoId: String) = queueController.onIdle(videoId)

    internal fun busy(): Boolean = queueController.busy()

    internal fun onStopped() = queueController.onStopped()

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
        // Never fill the phone: a full disk breaks other apps and Android itself.
        if (DownloadStore.freeBytes(context) < MIN_FREE_BYTES) error(LOW_STORAGE_REFUSAL)
        val limitGb = AppSettings.downloadLimitGb.value
        // shortcut: re-sums every saved file per download; cache a running total if limits on big libraries feel slow.
        if (limitGb > 0 && limitReached(DownloadStore.usedBytes(context, saved.value.values), limitGb)) {
            error(limitRefusal(limitGb))
        }

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

    internal const val LOW_STORAGE_REFUSAL = "Less than 500 MB of storage is free"

    /**
     * Sends one of this phone's downloads to the paired computer, which adds it to its own downloads. Only single-file
     * downloads (offline stream folders stay on the phone). Blocking: run on Dispatchers.IO.
     */
    fun sendToComputer(context: Context, songId: String) {
        val meta = library.savedSongs().firstOrNull { it.videoId == songId } ?: error("Not downloaded")
        val uri = Uri.parse(meta.uri)
        val size = when (uri.scheme) {
            "file" -> uri.path?.let { java.io.File(it).length() } ?: 0L
            else -> context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }
        if (size <= 0) error("The file is missing")
        val durationMs = meta.durationText?.split(':')?.mapNotNull(String::toLongOrNull)
            ?.fold(0L) { acc, part -> acc * 60 + part }?.times(1_000)
        val input = context.contentResolver.openInputStream(uri) ?: error("The file is missing")
        input.use {
            com.licht.nuvx.data.sync.NuvxFiles.send(meta.videoId, meta.title, meta.artist, meta.thumbnailUrl, durationMs, size, it)
        }
    }

    /** This phone's downloads as seen by phones it hosts (copy to and from them). */
    fun hostFiles(context: Context) = object : com.licht.nuvx.data.sync.LanHost.Files {
        override fun list(): kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.buildJsonArray {
            library.savedSongs().filterNot { it.uri.endsWith("playlist.m3u8") }.forEach { meta ->
                val size = sizeOf(context, Uri.parse(meta.uri)).takeIf { it > 0 } ?: return@forEach
                val ext = meta.downloadFormat?.takeIf { it.length in 2..5 } ?: meta.uri.substringAfterLast('.', "m4a")
                add(kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(meta.videoId))
                    put("title", kotlinx.serialization.json.JsonPrimitive(meta.title))
                    put("artist", kotlinx.serialization.json.JsonPrimitive(meta.artist))
                    meta.thumbnailUrl?.let { put("art", kotlinx.serialization.json.JsonPrimitive(it)) }
                    put("size", kotlinx.serialization.json.JsonPrimitive(size))
                    put("ext", kotlinx.serialization.json.JsonPrimitive(ext))
                })
            }
        }

        override fun read(id: String, offset: Long, max: Int): Pair<ByteArray, Long>? {
            val meta = library.savedSongs().firstOrNull { it.videoId == id } ?: return null
            val uri = Uri.parse(meta.uri)
            val size = sizeOf(context, uri)
            if (size <= 0 || offset > size) return null
            val input = context.contentResolver.openInputStream(uri) ?: return null
            return input.use { s ->
                var skipped = 0L
                while (skipped < offset) {
                    val n = s.skip(offset - skipped)
                    if (n <= 0) return null
                    skipped += n
                }
                val buf = ByteArray(minOf(max.toLong(), size - offset).toInt())
                var at = 0
                while (at < buf.size) {
                    val n = s.read(buf, at, buf.size - at)
                    if (n < 0) break
                    at += n
                }
                buf.copyOf(at) to size
            }
        }

        override fun has(id: String): Boolean = saved.value.containsKey(id)

        override fun receive(id: String, file: java.io.File, title: String, artist: String, art: String?, durationMs: Long?, ext: String) {
            if (saved.value.containsKey(id)) return
            if (DownloadStore.freeBytes(context) < MIN_FREE_BYTES + file.length()) error(LOW_STORAGE_REFUSAL)
            val song = Song(
                videoId = id,
                title = title.ifBlank { id },
                artist = artist,
                thumbnailUrl = art,
                durationText = durationMs?.let { ms -> "%d:%02d".format(ms / 60_000, ms / 1_000 % 60) },
            )
            val pending = DownloadStore.begin(context, DownloadStore.fileNameFor(song, ext), if (ext == "webm") "audio/webm" else "audio/mp4")
            try {
                pending.openStream().use { out -> file.inputStream().use { it.copyTo(out) } }
                library.remember(song, song, pending.commit(), ext, art)
            } catch (e: Throwable) {
                pending.abort()
                throw e
            }
        }
    }

    private fun sizeOf(context: Context, uri: Uri): Long = when (uri.scheme) {
        "file" -> uri.path?.let { java.io.File(it).length() } ?: 0L
        else -> runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
    }

    /** Single-file songs this phone could send (offline stream folders are not portable). */
    fun sendableSongIds(): List<String> = library.savedSongs()
        .filterNot { it.uri.endsWith("playlist.m3u8") }
        .map { it.videoId }

    /**
     * Copies a song the paired computer downloaded into this phone's downloads, as if it had been downloaded here.
     * Same storage rules as a normal download. Blocking: run on Dispatchers.IO.
     */
    fun copyFromComputer(context: Context, file: com.licht.nuvx.data.sync.NuvxFiles.Remote, onProgress: (Long) -> Unit = {}) {
        if (saved.value.containsKey(file.id)) return
        if (DownloadStore.freeBytes(context) < MIN_FREE_BYTES + file.size) error(LOW_STORAGE_REFUSAL)
        val limitGb = AppSettings.downloadLimitGb.value
        if (limitGb > 0 && limitReached(DownloadStore.usedBytes(context, saved.value.values) + file.size, limitGb)) error(limitRefusal(limitGb))
        val song = Song(
            videoId = file.id,
            title = file.title.ifBlank { file.id },
            artist = file.artist,
            thumbnailUrl = file.art,
            durationText = file.durationMs?.let { ms -> "%d:%02d".format(ms / 60_000, ms / 1_000 % 60) },
        )
        val mime = when (file.ext) {
            "m4a" -> "audio/mp4"
            "webm", "opus" -> "audio/webm"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            else -> "audio/ogg"
        }
        val pending = DownloadStore.begin(context, DownloadStore.fileNameFor(song, file.ext), mime)
        try {
            pending.openStream().use { out -> com.licht.nuvx.data.sync.NuvxFiles.fetch(file, out, onProgress) }
            val uri = pending.commit()
            library.remember(song, song, uri, file.ext, file.art)
        } catch (e: Throwable) {
            pending.abort()
            throw e
        }
    }

    internal fun limitReached(usedBytes: Long, limitGb: Int): Boolean = limitGb > 0 && usedBytes >= limitGb * 1_000_000_000L

    internal fun limitRefusal(limitGb: Int) = "Downloads have reached your $limitGb GB limit. Raise it in Settings → Downloads."

    private const val MIN_FREE_BYTES = 500L * 1024 * 1024

    fun dismissFailure(videoId: String) {
        if (_active.value[videoId] is DownloadState.Failed) clear(videoId)
    }

}
