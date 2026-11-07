package com.bxane.nuvx.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** YouTube Music's own search filter tokens (the `params` its web client sends for each chip). */
enum class SearchFilter(val label: String, val params: String?) {
    ALL("All", null),
    SONGS("Songs", "EgWKAQIIAWoMEA4QChADEAQQCRAF"),
    VIDEOS("Videos", "EgWKAQIQAWoMEA4QChADEAQQCRAF"),
    ALBUMS("Albums", "EgWKAQIYAWoMEA4QChADEAQQCRAF"),
    PLAYLISTS("Playlists", "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"),
    ARTISTS("Artists", "EgWKAQIgAWoMEA4QChADEAQQCRAF"),
}

/** An album or playlist page (same browse shape): header details plus its playable tracks in order. */
data class Album(val browseId: String, val title: String, val artist: String?, val year: String?, val artworkUrl: String?, val tracks: List<Track>)

/** An artist page: top songs plus browsable shelves (albums, singles, playlists, similar artists). */
data class Artist(val browseId: String, val name: String, val artworkUrl: String?, val topSongs: List<Track>, val shelves: List<ArtistShelf>, val videoShelves: List<ArtistVideoShelf> = emptyList())

data class ArtistShelf(val title: String, val items: List<Collection>)

enum class ArtistVideoCategory { MUSIC_VIDEO, LIVE_PERFORMANCE, ACOUSTIC, LYRIC_VIDEO, VISUALIZER, INTERVIEW, BEHIND_THE_SCENES, OTHER }

data class ArtistVideoShelf(
    val title: String,
    val category: ArtistVideoCategory,
    val items: List<Track>,
    val continuation: String? = null,
    val moreBrowseId: String? = null,
    val moreBrowseParams: String? = null,
    val continuationBrowseId: String? = null,
)

fun appendArtistVideoPage(current: ArtistVideoShelf, page: ArtistVideoShelf, consumedContinuations: Set<String>): ArtistVideoShelf {
    val continuation = page.continuation?.takeUnless { it == current.continuation || it in consumedContinuations }
    return current.copy(
        items = (current.items + page.items).distinctBy { it.videoId },
        continuation = continuation,
        continuationBrowseId = page.continuationBrowseId.takeIf { continuation != null },
        moreBrowseId = page.moreBrowseId,
        moreBrowseParams = page.moreBrowseParams,
    )
}

fun filterArtistVideoShelves(shelves: List<ArtistVideoShelf>, category: ArtistVideoCategory?): List<ArtistVideoShelf> =
    category?.let { selected -> shelves.filter { it.category == selected } } ?: shelves

enum class BackendId(val label: String) { INNERTUBEX("InnerTubeX"), EXCOR("eXcor") }

/** One YouTube Music engine. Every call returns null or throws when the engine cannot answer; none fabricates results. */
interface MusicBackend {
    val id: BackendId
    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL): SearchResults
    suspend fun radio(seedVideoId: String, continuation: String? = null): RadioPage
    suspend fun stream(videoId: String): AudioStream?
    suspend fun stream(videoId: String, quality: AudioQualityPreference): AudioStream? = stream(videoId)
    /** Engines without album pages keep this default, so the router falls back to one that has them. */
    suspend fun album(browseId: String): Album = throw BackendFailure(id, "album", UnsupportedOperationException("${id.label} has no album pages"))
    suspend fun artist(browseId: String): Artist = throw BackendFailure(id, "artist", UnsupportedOperationException("${id.label} has no artist pages"))
    suspend fun artistVideoPage(browseId: String, artistName: String, shelf: ArtistVideoShelf): ArtistVideoShelf =
        throw BackendFailure(id, "artist video continuation", UnsupportedOperationException("${id.label} has no artist video pages"))
}

/** Optional capability: only backends that return real video and companion audio sources implement this. */
interface VideoCapableBackend {
    suspend fun video(videoId: String, maxHeight: Int? = null): VideoStream?
}

class BackendFailure(val backend: BackendId, val operation: String, cause: Throwable?) :
    Exception("${backend.label} $operation failed${cause?.message?.let { ": $it" } ?: ""}", cause)

/** Last observed outcome per backend, shown in Settings so users see real engine status instead of a toggle. */
data class BackendHealth(val ok: Boolean, val operation: String, val detail: String?, val latencyMs: Long)

/**
 * Routes every catalogue and stream call to the selected engine. [fallback] retries a failed or empty call on the
 * other engine; the result's [AudioStream.backend] and [health] say which engine actually answered.
 */
class BackendRouter(
    private val backends: Map<BackendId, MusicBackend>,
    selected: BackendId = BackendId.INNERTUBEX,
    fallback: Boolean = true,
) {
    private val _selected = MutableStateFlow(selected)
    val selected: StateFlow<BackendId> = _selected.asStateFlow()
    private val _fallback = MutableStateFlow(fallback)
    val fallback: StateFlow<Boolean> = _fallback.asStateFlow()
    private val _audioQuality = MutableStateFlow(AudioQualityPreference.HIGH)
    val audioQuality: StateFlow<AudioQualityPreference> = _audioQuality.asStateFlow()
    private val _videoQuality = MutableStateFlow(VideoQualityPreference.AUTO)
    val videoQuality: StateFlow<VideoQualityPreference> = _videoQuality.asStateFlow()
    private val _health = MutableStateFlow<Map<BackendId, BackendHealth>>(emptyMap())
    val health: StateFlow<Map<BackendId, BackendHealth>> = _health.asStateFlow()

    val available: Set<BackendId> get() = backends.keys

    fun select(id: BackendId) {
        require(id in backends) { "${id.label} is not available on this platform" }
        _selected.value = id
    }

    fun setFallback(enabled: Boolean) { _fallback.value = enabled }

    fun chooseAudioQuality(quality: AudioQualityPreference, store: KeyValueStore) {
        _audioQuality.value = quality
        quality.save(store)
    }

    fun chooseVideoQuality(quality: VideoQualityPreference, store: KeyValueStore) {
        _videoQuality.value = quality
        quality.save(store)
    }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL) =
        route("search", { it.tracks.isEmpty() && it.collections.isEmpty() }) { it.search(query, filter) }

    /** Video catalog search always uses the verified primary engine, regardless of the audio-engine selection. */
    suspend fun searchVideos(query: String): SearchResults {
        val backend = backends[BackendId.INNERTUBEX]
            ?: throw BackendFailure(BackendId.INNERTUBEX, "video search", UnsupportedOperationException("Video search is unavailable"))
        return backend.search(query, SearchFilter.VIDEOS)
    }

    suspend fun album(browseId: String) = route("album", { it.tracks.isEmpty() }) { it.album(browseId) }

    suspend fun artist(browseId: String) = route("artist", { it.topSongs.isEmpty() && it.shelves.isEmpty() && it.videoShelves.isEmpty() }) { it.artist(browseId) }

    suspend fun artistVideoPage(browseId: String, artistName: String, shelf: ArtistVideoShelf): ArtistVideoShelf {
        val backend = backends[BackendId.INNERTUBEX]
            ?: throw BackendFailure(BackendId.INNERTUBEX, "artist video continuation", UnsupportedOperationException("Artist video pages are unavailable"))
        return backend.artistVideoPage(browseId, artistName, shelf)
    }

    suspend fun radio(seed: String, continuation: String? = null): RadioPage {
        if (seed.startsWith("local:")) return RadioPage(emptyList(), null)
        return route("radio", { it.tracks.isEmpty() }) { it.radio(seed, continuation) }
    }

    suspend fun stream(videoId: String): AudioStream? = runCatching { route("stream", { it == null }) { it.stream(videoId, _audioQuality.value) } }
        .getOrElse { if (it is CancellationException) throw it else null }

    suspend fun resolveVideo(videoId: String, maxHeight: Int? = videoQuality.value.maxHeight): VideoStream {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "Invalid video identifier" }
        require(maxHeight == null || maxHeight in 144..4320) { "Unsupported video height" }
        val stream = route<VideoStream?>("video", { it == null }) { backend ->
            val capable = backend as? VideoCapableBackend ?: return@route null
            val result = try {
                capable.video(videoId, maxHeight)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                throw IllegalStateException("Video source resolution failed")
            }
            result?.also {
                require(it.videoId == videoId && it.backend == backend.id &&
                    isSupportedMediaAddress(it.audioUrl) && isSupportedMediaAddress(it.videoUrl)) {
                    "The engine returned unsupported video sources"
                }
            }
        } ?: throw BackendFailure(_selected.value, "video", IllegalStateException("No playable video is available"))
        return stream
    }

    private suspend fun <T> route(op: String, empty: (T) -> Boolean, call: suspend (MusicBackend) -> T): T {
        val order = listOf(_selected.value) + if (_fallback.value) backends.keys.filter { it != _selected.value } else emptyList()
        var lastError: Throwable? = null
        var lastEmpty: T? = null
        for (id in order) {
            val backend = backends[id] ?: continue
            val start = TimeSource.Monotonic.markNow()
            try {
                val result = call(backend)
                val isEmpty = empty(result)
                record(id, BackendHealth(!isEmpty, op, if (isEmpty) "no results" else null, start.elapsedNow().inWholeMilliseconds))
                if (!isEmpty) return result
                lastEmpty = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                record(id, BackendHealth(false, op, e.message ?: e::class.simpleName, start.elapsedNow().inWholeMilliseconds))
                lastError = e
            }
        }
        @Suppress("UNCHECKED_CAST")
        return lastEmpty ?: throw BackendFailure(_selected.value, op, lastError)
    }

    private fun record(id: BackendId, h: BackendHealth) = _health.update { it + (id to h) }
}
