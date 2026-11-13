package com.metrolist.innertubex.extraction

public interface YtConfigParser {

    public suspend fun fetchConfig(
        videoId: String,
        useLoginCookies: Boolean = false,
    ): PlayerConfig

    public suspend fun fetchEmbeddedConfig(
        videoId: String,
        useLoginCookies: Boolean = false,
    ): PlayerConfig = fetchConfig(videoId, useLoginCookies)
}
