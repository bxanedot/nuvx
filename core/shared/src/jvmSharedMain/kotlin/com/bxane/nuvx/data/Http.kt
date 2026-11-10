package com.bxane.nuvx.data

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object Http {

    private const val USAGE_LOGGING_ENABLED = false
    private const val USAGE_TAG = "BCDataUsage"
    private val usageTotals = ConcurrentHashMap<String, AtomicLong>()

    private class CountingSource(source: Source, private val onClose: (Long) -> Unit) :
        ForwardingSource(source) {
        private var bytes = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) bytes += read
            return read
        }
        override fun close() {
            super.close()
            onClose(bytes)
        }
    }

    private class CountedBody(
        private val delegate: ResponseBody,
        private val counted: BufferedSource,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = counted
    }

    private val usageInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) {
            response
        } else {
            val host = request.url.host
            val range = request.header("Range")
            val counting = CountingSource(body.source()) { bytes ->
                val total = usageTotals.computeIfAbsent(host) { AtomicLong() }.addAndGet(bytes)
                DebugLog.d(
                    USAGE_TAG,
                    "$host ${request.method} ${request.url.encodedPath} " +
                        "range=$range status=${response.code} bytes=$bytes total[$host]=$total",
                )
            }.buffer()
            response.newBuilder().body(CountedBody(body, counting)).build()
        }
    }

    private val webDavInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (request.header("Authorization") == null &&
            com.bxane.nuvx.data.webdav.WebDavAuth.shouldAuthorize(request.url.host)
        ) {
            val header = com.bxane.nuvx.data.webdav.WebDavAuth.authHeader
            if (!header.isNullOrBlank()) {
                return@Interceptor chain.proceed(
                    request.newBuilder().header("Authorization", header).build(),
                )
            }
        }
        chain.proceed(request)
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .addInterceptor(webDavInterceptor)
        .apply { if (USAGE_LOGGING_ENABLED) addNetworkInterceptor(usageInterceptor) }
        .build()
}
