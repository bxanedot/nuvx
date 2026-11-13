package com.bxane.nuvx.data.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import com.bxane.nuvx.data.innertube.PlayerClient
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL
import platform.Foundation.HTTPShouldHandleCookies
import platform.Foundation.valueForHTTPHeaderField


































actual object Http {

    private val cookieJar: NSHTTPCookieStorage =
        NSHTTPCookieStorage.sharedCookieStorageForGroupContainerIdentifier("group.com.bxane.nuvx").also { jar ->


            listOf(jar, NSHTTPCookieStorage.sharedHTTPCookieStorage).forEach { store ->
                store.cookies?.filterIsInstance<NSHTTPCookie>()
                    ?.filterNot { CredentialPolicy.jarServes(it.domain.removePrefix(".")) }
                    ?.forEach { store.deleteCookie(it) }
            }
        }








    internal actual val client: HttpClient = HttpClient(Darwin) {
        engine {
            configureRequest {




                if (valueForHTTPHeaderField("Cookie") != null || !CredentialPolicy.jarServes(URL?.host)) {
                    HTTPShouldHandleCookies = false
                }
            }
            configureSession {
                HTTPShouldSetCookies = true
                HTTPCookieStorage = cookieJar
            }
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
        install(HttpTimeout)
        expectSuccess = false
        followRedirects = false
    }.apply {
        plugin(HttpSend).intercept { initial ->
            if (initial.attributes.getOrNull(com.bxane.nuvx.data.sources.addon.AddonSessionTransport.noRedirects) == true) {
                return@intercept execute(initial)
            }
            var request = initial
            var call = execute(request)
            repeat(5) {
                if (call.response.status.value !in setOf(301, 302, 303, 307, 308) ||
                    request.method !in setOf(HttpMethod.Get, HttpMethod.Head)) return@intercept call
                val location = call.response.headers["Location"] ?: return@intercept call
                val from = request.url.build().toString()
                val destination = URLBuilder(request.url).apply { takeFrom(location) }.build().toString()
                val hasProviderCookie = cookieJar.cookies?.filterIsInstance<NSHTTPCookie>()?.any { cookie ->
                    val host = request.url.host.lowercase()
                    val domain = cookie.domain.removePrefix(".").lowercase()
                    host == domain || host.endsWith(".$domain")
                } == true
                val credentials = hasProviderCookie || request.headers.names().any {
                    it.equals("Authorization", true) || it.equals("Cookie", true) || it.startsWith("X-Goog-", true)
                }
                if (!CredentialPolicy.mayRedirect(from, destination, credentials)) return@intercept call
                val next = HttpRequestBuilder().apply {
                    takeFrom(request)
                    url.takeFrom(destination)
                    if (!CredentialPolicy.sameOrigin(from, destination)) {
                        listOf("Authorization", "Cookie", "Origin", "X-Origin", "Referer", "X-Goog-Visitor-Id", "X-Goog-PageId", "X-Goog-AuthUser")
                            .forEach { headers.remove(it) }
                    }
                }
                call.response.bodyAsChannel().cancel(null)
                request = next
                call = execute(request)
            }
            call
        }
    }


    private fun HttpResponse.requireSuccess(what: String) {
        val code = status.value
        if (code !in 200..299) {
            throw HttpStatusException(code)
        }
    }

    @Throws(Exception::class)
    actual suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
        query: Map<String, String>,
        timeoutMillis: Long,
    ): String = client.post(url) {
        contentType(ContentType.Application.Json)
        setBody(body)
        timeout {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = 20_000
        }
        headers.forEach { (key, value) -> header(key, value) }
        query.forEach { (key, value) -> parameter(key, value) }
    }.also { it.requireSuccess("POST $url") }.bodyAsText()

    @Throws(Exception::class)
    actual suspend fun getText(
        url: String,
        headers: Map<String, String>,
        query: Map<String, String>,
        timeoutMillis: Long,
    ): String = client.get(url) {
        timeout {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = 20_000
        }
        headers.forEach { (key, value) -> header(key, value) }
        query.forEach { (key, value) -> parameter(key, value) }
    }.also { it.requireSuccess("GET $url") }.bodyAsText()

    @Throws(Exception::class)
    actual suspend fun getStatus(
        url: String,
        headers: Map<String, String>,
        query: Map<String, String>,
        timeoutMillis: Long,
    ): Int = client.get(url) {
        timeout {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = 20_000
        }
        headers.forEach { (key, value) -> header(key, value) }
        query.forEach { (key, value) -> parameter(key, value) }
    }.status.value

    @Throws(Exception::class)
    actual suspend fun getBytes(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
    ): ByteArray = client.get(url) {
        timeout {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = 20_000
        }
        headers.forEach { (key, value) -> header(key, value) }
    }.also { it.requireSuccess("GET $url") }.bodyAsBytes()

    @Throws(Exception::class)
    actual suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String>,
        timeoutMillis: Long,
    ): String = client.post(url) {
        timeout {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = 20_000
        }
        headers.forEach { (key, value) -> header(key, value) }
        setBody(FormDataContent(Parameters.build {
            fields.forEach { (k, v) -> append(k, v) }
        }))
    }.bodyAsText()

    @Throws(Exception::class)
    actual suspend fun getRaw(
        url: String,
        headers: Map<String, String>,
        query: Map<String, String>,
        timeoutMillis: Long,
    ): RawHttpText {
        val response = client.get(url) {
            timeout {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = 8_000
            }
            headers.forEach { (key, value) -> header(key, value) }
            query.forEach { (key, value) -> parameter(key, value) }
        }
        val body = runCatching { response.bodyAsText() }.getOrNull()
        return RawHttpText(status = response.status.value, body = body)
    }

    @Throws(Exception::class)
    actual suspend fun postBytes(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
    ): RawHttpBytes {
        val response = client.post(url) {
            timeout {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = 8_000
            }
            header("Content-Type", contentType)
            headers.forEach { (key, value) ->
                if (!key.equals("Content-Type", ignoreCase = true)) header(key, value)
            }
            setBody(body)
        }
        val bytes = runCatching { response.bodyAsBytes() }.getOrNull()
        return RawHttpBytes(
            status = response.status.value,
            body = bytes,
            contentType = response.headers[io.ktor.http.HttpHeaders.ContentType],
            contentRange = response.headers[io.ktor.http.HttpHeaders.ContentRange],
            contentLength = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull(),
        )
    }

    @Throws(Exception::class)
    actual suspend fun requestRaw(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMillis: Long,
    ): RawHttpText {
        val response = client.request(url) {






            this.method = HttpMethod(method)
            timeout {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = 20_000
            }
            headers.forEach { (key, value) -> header(key, value) }
            if (body != null) {
                header("Content-Type", "application/xml; charset=utf-8")
                setBody(body)
            }
        }
        val text = runCatching { response.bodyAsText() }.getOrNull()
        return RawHttpText(status = response.status.value, body = text)
    }

    @Throws(Exception::class)
    actual suspend fun requestBytes(
        url: String,
        method: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
    ): RawHttpBytes {
        val response = client.request(url) {
            this.method = HttpMethod(method)
            timeout {



                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = 20_000
            }
            headers.forEach { (key, value) ->
                if (!key.equals("Content-Type", ignoreCase = true)) header(key, value)
            }
            header("Content-Type", contentType)
            setBody(body)
        }
        val bytes = runCatching { response.bodyAsBytes() }.getOrNull()
        return RawHttpBytes(
            status = response.status.value,
            body = bytes,
            contentType = response.headers[io.ktor.http.HttpHeaders.ContentType],
        )
    }

    @Throws(Exception::class)
    actual suspend fun getBytesRaw(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        timeoutMillis: Long,
    ): RawHttpBytes {
        return client.prepareGet(url) {
            timeout {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = 20_000
            }
            headers.forEach { (key, value) -> header(key, value) }
        }.execute { response ->
        val bytes = if (maxBytes >= Int.MAX_VALUE) {
            runCatching { response.bodyAsBytes() }.getOrNull()
        } else {




            try {
                val channel = response.bodyAsChannel()
                val taken = channel.readRemaining(maxBytes.toLong()).readByteArray()
                channel.cancel(null)
                taken
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        RawHttpBytes(
            status = response.status.value,
            body = bytes,
            contentType = response.headers[io.ktor.http.HttpHeaders.ContentType],
            contentRange = response.headers[io.ktor.http.HttpHeaders.ContentRange],
            contentLength = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull(),
        )
        }
    }

    actual fun setHostCookies(originUrl: String, cookies: Map<String, String>) {
        val host = NSURL(string = originUrl)?.host ?: return
        val domain = when {
            host.endsWith("spotify.com") -> ".spotify.com"
            host.startsWith(".") -> host
            else -> ".$host"
        }
        cookies.forEach { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@forEach
            val props: Map<Any?, Any> = mapOf(
                NSHTTPCookieName to name,
                NSHTTPCookieValue to value,
                NSHTTPCookieDomain to domain,
                NSHTTPCookiePath to "/",
                NSHTTPCookieSecure to "TRUE",
            )
            val cookie = NSHTTPCookie.cookieWithProperties(props) ?: return@forEach




            cookieJar.cookies?.let { existing ->
                (existing as List<*>).filterIsInstance<NSHTTPCookie>()
                    .filter { it.name == cookie.name && it.domain == cookie.domain }
                    .forEach { cookieJar.deleteCookie(it) }
            }
            cookieJar.setCookie(cookie)
        }
    }





























    @Throws(Exception::class)
    actual suspend fun probe(
        url: String,
        headers: Map<String, String>,
    ): ProbeResult {
        return try {
            val length = PlayerClient.lengthFromUrl(url)
            val range = PlayerClient.rangeBytesFor(url)
            val start = if (length != null && length > AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) {
                AUTH_BOUNDARY_BYTES
            } else {
                0L
            }
            val response = rangedGet(
                url, headers,
                from = start,
                length = if (length != null) minOf(range, length - start) else range,
            )
            val ct = response.contentType()?.toString()
            val status = response.status.value
            if (status in REFUSAL_CODES || status !in 200..299 && status != 416) {
                response.discardBody()
                return ProbeResult(status = status, contentType = ct, bodyArrived = false)
            }






            val bodyArrived = response.readProbeBytes()
            if (!bodyArrived) {
                return ProbeResult(status = status, contentType = ct, bodyArrived = false)
            }
            ProbeResult(status = status, contentType = ct, bodyArrived = true)
        } catch (_: Exception) {
            ProbeResult(status = -1, contentType = null, bodyArrived = false)
        }
    }

    private suspend fun rangedGet(
        url: String,
        headers: Map<String, String>,
        from: Long,
        length: Long,
    ): HttpResponse = client.get(url) {
        header("Range", "bytes=$from-${from + length - 1}")
        timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS }
        headers.forEach { (key, value) -> header(key, value) }
    }


    private suspend fun HttpResponse.readProbeBytes(): Boolean = try {
        val channel = bodyAsChannel()
        val bytes = channel.readRemaining(PROBE_READ_BYTES).readByteArray()
        channel.cancel(null)
        bytes.size >= PROBE_READ_BYTES
    } catch (_: Exception) {
        false
    }

    private suspend fun HttpResponse.discardBody() {
        try {
            bodyAsChannel().cancel(null)
        } catch (_: Exception) {

        }
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)
    private const val PROBE_TIMEOUT_MS = 6_000L








    private const val AUTH_BOUNDARY_BYTES = 1024L * 1024


    private const val PROBE_READ_BYTES = 16L * 1024
}
