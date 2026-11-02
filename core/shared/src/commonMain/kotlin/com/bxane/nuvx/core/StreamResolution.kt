package com.bxane.nuvx.core

import io.ktor.http.Url

/** Only engine-resolved YouTube CDN streams are accepted; signed URLs and request headers stay private. */
suspend fun BackendRouter.resolveAudio(videoId: String): AudioStream {
    require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "Invalid track identifier" }
    val result = requireNotNull(stream(videoId)) { "No playable stream is available" }
    val address = runCatching { Url(result.url) }.getOrNull()
    require(address != null && address.protocol.name == "https" && address.port == 443 &&
        address.host.endsWith(".googlevideo.com", ignoreCase = true) &&
        address.user == null && address.password == null) { "The engine returned an unsupported media address" }
    return result
}
