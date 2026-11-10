package com.bxane.nuvx.playback.smart

 object AutomixAnalysisSource {
    const val OPUS_QUERY_PARAMETER = "automix_opus"

    fun isCanonicalYouTubeRendition(videoId: String?, cacheKey: String): Boolean =
        videoId == null || cacheKey == videoId

    fun opusUri(videoId: String): String = "nuvx://watch?v=$videoId&$OPUS_QUERY_PARAMETER=1"

    fun requestsYouTubeOpus(marker: String?): Boolean = marker == "1"
}
