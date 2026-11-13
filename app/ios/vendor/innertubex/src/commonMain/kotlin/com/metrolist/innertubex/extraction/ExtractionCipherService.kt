package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format








interface ExtractionCipherService {
    suspend fun initialize()

    suspend fun preloadPlayerCode(playerUrl: String)

    suspend fun prewarmEjs()

    suspend fun processFormats(
        playerUrl: String,
        formats: List<Format>,
    ): List<Format>
}
