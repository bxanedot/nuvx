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
class InnerTubeXBackend(http: HttpClient = nuvxHttpClient()) : MusicBackend {
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
        YtmJson.parseSearch(innerTube.search(YouTubeClient.WEB_REMIX, query = query, params = filter.params).text("search"))

    override suspend fun radio(seedVideoId: String, continuation: String?) = YtmJson.parseRadio(
        innerTube.next(YouTubeClient.WEB_REMIX, videoId = seedVideoId, playlistId = "RDAMVM$seedVideoId", continuation = continuation)
            .text("radio"),
    )

    override suspend fun stream(videoId: String): AudioStream? =
        extractor.extract(videoId, ContentHints(wantVideo = false), audioQuality = AudioQuality.HIGH)?.let {
            AudioStream(it.videoId, it.audioUrl, it.headers, it.mimeType, it.bitrate?.div(1000), it.loudnessDb, id)
        }

    private suspend fun HttpResponse.text(op: String): String {
        if (!status.isSuccess()) throw BackendFailure(id, op, IllegalStateException("HTTP ${status.value}"))
        return bodyAsText()
    }
}
