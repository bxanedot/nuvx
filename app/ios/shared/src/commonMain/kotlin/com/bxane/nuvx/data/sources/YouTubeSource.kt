package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt














class YouTubeSource(override val config: SourceConfig) : MusicSource, ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.displayName


    override suspend fun health(): SourceHealth = SourceHealth.Ok(config.kind.label)









    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = runCatching {
        com.bxane.nuvx.data.innertube.InnertubeParser
            .parseSearchSongs(com.bxane.nuvx.data.innertube.Innertube.search(query))
            .take(limit)
    }.getOrElse { emptyList() }











    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
}
