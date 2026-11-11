package com.bxane.nuvx.download

import android.content.Context
import android.net.Uri
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.DownloadQuality
import com.bxane.nuvx.playback.AudioCache
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

    fun init(context: Context) = library.init(context)

    private val fromCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val queueController = DownloadQueueController(
        active = _active,
        downloadsAllowedNow = { AppSettings.downloadsAllowedNow },
        servableFromCache = ::servableFromCache,
        run = ::run,
        clear = ::clear,
        fail = ::fail,
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

    fun dismissFailure(videoId: String) {
        if (_active.value[videoId] is DownloadState.Failed) clear(videoId)
    }

}
