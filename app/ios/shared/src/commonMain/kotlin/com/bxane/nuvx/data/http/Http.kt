package com.bxane.nuvx.data.http




































expect object Http {

    internal val client: io.ktor.client.HttpClient

    @Throws(Exception::class)
    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 30_000,
    ): String


    @Throws(Exception::class)
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 30_000,
    ): String






    @Throws(Exception::class)
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 60_000,
    ): ByteArray







    @Throws(Exception::class)
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): ProbeResult





    @Throws(Exception::class)
    suspend fun getStatus(
        url: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 15_000,
    ): Int




    @Throws(Exception::class)
    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 30_000,
    ): String





    @Throws(Exception::class)
    suspend fun getRaw(
        url: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 8_000,
    ): RawHttpText


    @Throws(Exception::class)
    suspend fun postBytes(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 15_000,
    ): RawHttpBytes













    @Throws(Exception::class)
    suspend fun requestRaw(
        url: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 30_000,
    ): RawHttpText









    @Throws(Exception::class)
    suspend fun requestBytes(
        url: String,
        method: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 120_000,
    ): RawHttpBytes
















    @Throws(Exception::class)
    suspend fun getBytesRaw(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = Int.MAX_VALUE,
        timeoutMillis: Long = 30_000,
    ): RawHttpBytes










    fun setHostCookies(originUrl: String, cookies: Map<String, String>)
}

data class RawHttpText(val status: Int, val body: String?)

data class RawHttpBytes(
    val status: Int, val body: ByteArray?, val contentType: String? = null,
    val contentRange: String? = null, val contentLength: Long? = null,
)


data class ProbeResult(
    val status: Int,
    val contentType: String?,

    val bodyArrived: Boolean,
)

class HttpStatusException(val status: Int) : IllegalStateException("Request failed (HTTP $status)")
