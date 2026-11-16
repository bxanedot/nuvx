package com.licht.nuvx.data.sources

import com.licht.nuvx.data.YtMusicRepository
import com.licht.nuvx.data.innertube.StreamResolver
import com.licht.nuvx.data.model.SearchFilter
import com.licht.nuvx.data.model.SearchResult
import com.licht.nuvx.data.model.Song

class YouTubeSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.YOUTUBE
    override val displayName: String get() = config.label.ifBlank { SourceKind.YOUTUBE.label }

    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> =
        YtMusicRepository.search(query, SearchFilter.SONGS)
            .getOrDefault(emptyList())
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }
            .take(limit)

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val url = StreamResolver.resolve(trackId)
        return SourceStream(url = url, format = StreamFormat(codec = "opus"))
    }
}
