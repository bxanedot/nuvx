package com.bxane.nuvx.core

import com.excor.Excor
import com.excor.ExcorVideoStreams
import com.excor.ExcorVideoCodec
import com.excor.ExcorYouTubeMusicItem
import com.excor.ExcorYouTubeMusicItemType
import com.excor.excorDefaultHost
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual fun nuvxHttpClient() = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
    install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
}

/** Alternative engine, selectable in Settings. */
class ExcorBackend(private val excor: Excor = Excor(excorDefaultHost())) : MusicBackend, VideoCapableBackend {
    override val id = BackendId.EXCOR

    override suspend fun search(query: String, filter: SearchFilter): SearchResults {
        val page = excor.musicSearch(query, filterParams = filter.params) ?: throw BackendFailure(id, "search", null)
        val tracks = page.items.filter { it.videoId != null && it.type in PLAYABLE }.map { it.toTrack() }
        val collections = page.items.mapNotNull { item ->
            val kind = when (item.type) {
                ExcorYouTubeMusicItemType.ALBUM -> Collection.Kind.ALBUM
                ExcorYouTubeMusicItemType.ARTIST -> Collection.Kind.ARTIST
                ExcorYouTubeMusicItemType.PLAYLIST -> Collection.Kind.PLAYLIST
                else -> return@mapNotNull null
            }
            Collection(kind, item.browseId ?: return@mapNotNull null, item.title, item.subtitle, item.art())
        }
        return SearchResults(tracks.distinctBy { it.videoId }, collections.distinctBy { it.browseId })
    }

    override suspend fun radio(seedVideoId: String, continuation: String?): RadioPage {
        val queue = excor.musicRadio(videoId = seedVideoId, continuation = continuation) ?: throw BackendFailure(id, "radio", null)
        return RadioPage(queue.tracks.filter { it.videoId != null }.map { it.toTrack() }, queue.continuation)
    }

    override suspend fun stream(videoId: String): AudioStream? =
        excor.extract(videoId, maxKbps = 320)?.let {
            AudioStream(it.videoId, it.url, excor.headersFor(it.url) ?: it.headers, it.mimeType, it.kbps, it.loudnessDb, id)
        }

    override suspend fun video(videoId: String, maxHeight: Int?): VideoStream? =
        excor.extractVideo(videoId, maxHeight = maxHeight ?: 4320)?.toNuvxVideoStream()

    private fun ExcorYouTubeMusicItem.toTrack() =
        Track(videoId!!, title, artists.map { it.name }, album, durationSeconds?.times(1000), art())

    private fun ExcorYouTubeMusicItem.art() = thumbnails.lastOrNull()?.url

    private companion object {
        val PLAYABLE = setOf(ExcorYouTubeMusicItemType.SONG, ExcorYouTubeMusicItemType.VIDEO)
    }
}

internal fun ExcorVideoStreams.toNuvxVideoStream(): VideoStream? {
    // shortcut: VideoStream requires separate audio; add muxed-source playback before accepting progressive-only results.
    val companion = audio ?: return null
    require(companion.videoId == videoId) { "Companion audio belongs to a different video" }
    require(video.headers == companion.headers) { "Video and audio require different request headers" }
    require(video.height > 0 && (video.width == null || video.width!! > 0)) { "Invalid video dimensions" }
    val codec = when (video.codec) {
        ExcorVideoCodec.H264 -> "avc1"
        ExcorVideoCodec.VP9 -> "vp09"
        ExcorVideoCodec.AV1 -> "av01"
        ExcorVideoCodec.OTHER -> null
    }
    return VideoStream(videoId, companion.url, video.url, video.headers, video.width, video.height,
        video.mimeType, companion.mimeType, codec, listOf(video.height), BackendId.EXCOR)
}

fun defaultBackends(): Map<BackendId, MusicBackend> = mapOf(
    BackendId.INNERTUBEX to InnerTubeXBackend(),
    BackendId.EXCOR to ExcorBackend(),
)
