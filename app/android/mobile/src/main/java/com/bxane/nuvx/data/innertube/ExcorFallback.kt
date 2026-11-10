package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.Http
import com.bxane.nuvx.data.TrackLog
import com.excor.Excor
import com.excor.excorDefaultHost
import java.util.Locale

object ExcorFallback : StreamResolver.FallbackEngine {
    private val excor by lazy {
        val locale = Locale.getDefault()
        Excor(
            excorDefaultHost(
                httpClient = Http.client,
                region = locale.country.takeIf { it.length == 2 } ?: "US",
                language = locale.language.takeIf { it.length in 2..12 } ?: "en",
                log = { level, message -> TrackLog.d("eXcor", "$level $message") },
            ),
        )
    }

    override suspend fun stream(videoId: String, maxKbps: Int): StreamResolver.Stream? =
        excor.extract(videoId, maxKbps)?.let { StreamResolver.Stream(it.url, it.kbps, it.mimeType, it.loudnessDb) }

    override fun headersFor(url: String): Map<String, String>? = excor.headersFor(url)
}
