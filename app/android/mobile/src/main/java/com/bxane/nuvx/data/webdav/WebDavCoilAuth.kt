package com.bxane.nuvx.data.webdav

import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageResult

class WebDavCoilAuth : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val header = authHeaderFor(request.data, request.httpHeaders) ?: return chain.proceed()
        val headers = NetworkHeaders.Builder(request.httpHeaders)
            .set("Authorization", header)
            .build()
        return chain.withRequest(request.newBuilder().httpHeaders(headers).build()).proceed()
    }

    companion object {
        fun authHeaderFor(data: Any?, headers: NetworkHeaders = NetworkHeaders.EMPTY): String? {
            if (headers["Authorization"] != null) return null
            val url = (data as? String)?.takeIf { it.startsWith("http") } ?: return null
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
            if (!WebDavAuth.shouldAuthorize(host)) return null
            return WebDavAuth.authHeader
        }
    }
}
