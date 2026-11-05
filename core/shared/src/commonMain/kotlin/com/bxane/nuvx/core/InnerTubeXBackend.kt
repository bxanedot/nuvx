package com.bxane.nuvx.core

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** Default engine. Catalogue calls go through InnerTubeX's WEB_REMIX client; streams through its extractor. */
class InnerTubeXBackend(http: HttpClient = nuvxHttpClient()) : MusicBackend, VideoCapableBackend {
    override val id = BackendId.INNERTUBEX
    private val innerTube = InnerTube(http)
    private val extractor by lazy {
        val config = RemotePlayerConfigStore(httpClient = http, repository = PlayerConfigRepository.disabled())
        val cipher = YouTubeCipherService(http, config)
        InnerTubeExtractor(
            configParser = YtConfigParserImpl(http, innerTube, config, cipherService = cipher),
            cipherService = cipher,
            innerTube = innerTube,
        )
    }

    override suspend fun search(query: String, filter: SearchFilter) =
        YtmJson.parseSearch(innerTube.search(YouTubeClient.WEB_REMIX, query = query, params = filter.params).text("search"), videoResults = filter == SearchFilter.VIDEOS)

    override suspend fun album(browseId: String) =
        YtmJson.parseAlbum(browseId, innerTube.browse(YouTubeClient.WEB_REMIX, browseId = browseId).text("album"))

    override suspend fun artist(browseId: String) =
        YtmJson.parseArtist(browseId, innerTube.browse(YouTubeClient.WEB_REMIX, browseId = browseId).text("artist"))

    override suspend fun artistVideoPage(browseId: String, artistName: String, shelf: ArtistVideoShelf): ArtistVideoShelf {
        val pageBrowseId = shelf.continuationBrowseId ?: browseId
        val response = if (shelf.continuation != null) {
            innerTube.browse(YouTubeClient.WEB_REMIX, browseId = pageBrowseId, continuation = shelf.continuation)
        } else {
            innerTube.browse(
                YouTubeClient.WEB_REMIX,
                browseId = requireNotNull(shelf.moreBrowseId) { "Artist video shelf has no more-page browse target" },
                params = shelf.moreBrowseParams,
            )
        }
        return YtmJson.parseArtistVideoPage(browseId, artistName, shelf.title, response.text("artist video page"), pageBrowseId)
    }

    override suspend fun radio(seedVideoId: String, continuation: String?) = YtmJson.parseRadio(
        innerTube.next(YouTubeClient.WEB_REMIX, videoId = seedVideoId, playlistId = "RDAMVM$seedVideoId", continuation = continuation)
            .text("radio"),
    )

    override suspend fun stream(videoId: String): AudioStream? =
        stream(videoId, AudioQualityPreference.HIGH)

    override suspend fun stream(videoId: String, quality: AudioQualityPreference): AudioStream? =
        extractor.extract(videoId, ContentHints(wantVideo = false), audioQuality = quality.toExtractorQuality())?.let {
            AudioStream(it.videoId, it.audioUrl, it.headers, it.mimeType, it.bitrate?.div(1000), it.loudnessDb, id, it.codecs, it.sampleRate)
        }

    override suspend fun video(videoId: String, maxHeight: Int?): VideoStream? =
        extractor.extract(
            videoId,
            ContentHints(wantVideo = true, maxVideoHeight = maxHeight)
                .withStreamCapabilities(allowHls = true, allowSabr = false, allowBoundedRange = false),
            audioQuality = AudioQuality.HIGH,
        )
            ?.takeIf { !it.audioUrl.isNullOrBlank() && !it.videoUrl.isNullOrBlank() }
            ?.let {
                VideoStream(it.videoId, requireNotNull(it.audioUrl), requireNotNull(it.videoUrl), it.headers,
                    it.videoWidth, it.videoHeight, it.videoMimeType, it.mimeType, it.videoCodecs, it.availableVideoHeights, id)
            }

    private suspend fun HttpResponse.text(op: String): String {
        if (!status.isSuccess()) throw BackendFailure(id, op, IllegalStateException("HTTP ${status.value}"))
        return bodyAsText()
    }
}

internal fun AudioQualityPreference.toExtractorQuality() = when (this) {
    AudioQualityPreference.AUTO -> AudioQuality.AUTO
    AudioQualityPreference.LOW -> AudioQuality.LOW
    AudioQualityPreference.HIGH -> AudioQuality.HIGH
}
