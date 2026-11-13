package com.bxane.nuvx.data.remote

import com.bxane.nuvx.data.http.Http
import com.bxane.nuvx.data.http.RawHttpText


































object WebDavClient {








    data class Entry(
        val url: String,
        val displayName: String,
        val isCollection: Boolean,
        val contentType: String? = null,
    )








    data class Listing(val audio: List<Entry>, val images: List<Entry>)


    sealed interface PutResult {

        data object Uploaded : PutResult


        data object AlreadyExists : PutResult
    }







    suspend fun testConnection(
        url: String,
        username: String,
        password: String,
    ): WebDavFailure? {
        val root = WebDavConfig.normalizeUrl(url)
        if (!WebDavConfig.isConfigured(root)) {
            return WebDavFailure.Address("That is not a server address.")
        }
        val response = Http.requestRaw(
            url = root,
            method = "PROPFIND",
            body = PROP_FIND_BODY,
            headers = headersFor(username, password, depth = "0"),
        )



        if (response.status == 207 || response.status in 200..299) return null
        return WebDavFailure.describe(response, "Server answered")
    }














    suspend fun listLibrary(
        baseUrl: String,
        username: String,
        password: String,
        maxFiles: Int = 10_000,
    ): Result<Listing> {
        val root = WebDavConfig.normalizeUrl(baseUrl)
        if (!WebDavConfig.isConfigured(root)) {
            return Result.failure(WebDavException("WebDAV is not configured"))
        }
        return try {
            val audio = LinkedHashMap<String, Entry>()
            val images = LinkedHashMap<String, Entry>()
            val visited = HashSet<String>()
            val queue = ArrayDeque<String>()
            queue.addLast(root)

            while (queue.isNotEmpty() && audio.size < maxFiles) {
                val dir = queue.removeFirst()
                if (!visited.add(dir.lowercase())) continue
                val response = Http.requestRaw(
                    url = dir,
                    method = "PROPFIND",
                    body = PROP_FIND_BODY,
                    headers = headersFor(username, password, depth = "1"),
                )
                if (response.status != 207 && response.status !in 200..299) {









                    return Result.failure(
                        WebDavException(WebDavFailure.describe(response, COULD_NOT_READ).message()),
                    )
                }
                for (entry in parseMultistatus(response.body.orEmpty(), dir)) {
                    val name = entry.displayName.ifBlank { entry.url }





                    when {
                        WebDavConfig.isAudioFile(name) -> { if (entry.url !in audio) audio[entry.url] = entry }
                        WebDavConfig.isImageFile(name) -> { if (entry.url !in images) images[entry.url] = entry }
                    }
                    if (entry.isCollection && !visited.contains(entry.url.lowercase())) {
                        queue.addLast(entry.url)
                    }
                }
            }
            Result.success(Listing(audio.values.toList(), images.values.toList()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {




            Result.failure(WebDavException(WebDavFailure.Unreachable(COULD_NOT_READ).message()))
        }
    }


    suspend fun listAudioFiles(
        baseUrl: String,
        username: String,
        password: String,
        maxFiles: Int = 10_000,
    ): Result<List<Entry>> = listLibrary(baseUrl, username, password, maxFiles).map { it.audio }








    suspend fun exists(
        fileUrl: String,
        username: String,
        password: String,
    ): Result<Boolean> = try {
        val response = Http.requestRaw(
            url = fileUrl,
            method = "HEAD",
            headers = authHeaders(username, password),
        )
        when (response.status) {
            200 -> Result.success(true)
            404 -> Result.success(false)
            else -> Result.failure(WebDavException("Exists check failed with ${response.status}"))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(WebDavException(e.message ?: "Exists check failed"))
    }











    suspend fun putFile(
        fileUrl: String,
        body: ByteArray,
        mimeType: String,
        username: String,
        password: String,
        overwrite: Boolean,
    ): Result<PutResult> = try {
        val headers = authHeaders(username, password).toMutableMap()
        if (!overwrite) headers["If-None-Match"] = "*"
        val response = Http.requestBytes(
            url = fileUrl,
            method = "PUT",
            body = body,
            contentType = mimeType,
            headers = headers,
        )
        when {
            response.status in 200..299 -> Result.success(PutResult.Uploaded)
            response.status == 412 -> Result.success(PutResult.AlreadyExists)
            else -> Result.failure(WebDavException("Upload failed with ${response.status}"))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(WebDavException(e.message ?: "Upload failed"))
    }







    suspend fun ensureCollection(
        dirUrl: String,
        username: String,
        password: String,
    ): Result<Unit> = try {
        val response = Http.requestRaw(
            url = dirUrl,
            method = "MKCOL",
            headers = authHeaders(username, password),
        )
        if (response.status in 200..299 || response.status == 405) {
            Result.success(Unit)
        } else {
            Result.failure(WebDavException("Could not create folder (${response.status})"))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(WebDavException(e.message ?: "Could not create folder"))
    }



    private fun authHeaders(username: String, password: String): Map<String, String> {
        val header = WebDavConfig.basicAuthHeader(username, password) ?: return emptyMap()
        return mapOf("Authorization" to header)
    }

    private fun headersFor(username: String, password: String, depth: String): Map<String, String> =
        authHeaders(username, password) + mapOf("Depth" to depth)









    private const val COULD_NOT_READ = "Could not read that share"








    const val PROP_FIND_BODY: String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
<d:displayname/>
<d:resourcetype/>
<d:getcontenttype/>
  </d:prop>
</d:propfind>"""










    fun parseMultistatus(xml: String, dirUrl: String): List<Entry> {
        if (xml.isBlank()) return emptyList()
        val entries = mutableListOf<Entry>()
        for (response in responsesOf(xml)) {
            val href = response.textOf(HREF)?.trim().orEmpty()
            if (href.isEmpty()) continue
            val url = resolveHref(dirUrl, href)

            if (isSameUrl(url, dirUrl)) continue
            val name = response.textOf(DISPLAY_NAME)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: WebDavConfig.fileNameOf(url)
            entries += Entry(
                url = url,
                displayName = name,
                isCollection = response.hasChild(RESOURCE_TYPE, COLLECTION),
                contentType = response.textOf(GET_CONTENT_TYPE)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        return entries
    }















    fun resolveHref(baseUrl: String, href: String): String {
        val trimmed = href.trim()
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
        val base = WebDavConfig.normalizeUrl(baseUrl)
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return trimmed
        val afterScheme = base.substring(schemeEnd + 3)
        val schemeHost = if (afterScheme.contains('/')) {
            base.substring(0, schemeEnd + 3) + afterScheme.substringBefore('/')
        } else {
            base
        }
        return if (trimmed.startsWith("/")) "$schemeHost$trimmed" else "${base.trimEnd('/')}/$trimmed"
    }


    private fun isSameUrl(a: String, b: String): Boolean =
        a.trim().trimEnd('/').lowercase() == b.trim().trimEnd('/').lowercase()









    private const val RESPONSE = "response"
    private const val HREF = "href"
    private const val DISPLAY_NAME = "displayname"
    private const val RESOURCE_TYPE = "resourcetype"
    private const val COLLECTION = "collection"
    private const val GET_CONTENT_TYPE = "getcontenttype"














    private fun responsesOf(xml: String): List<String> {
        val bodies = mutableListOf<String>()
        var index = 0
        while (index < xml.length) {
            val open = xml.indexOfOpeningTag(RESPONSE, index) ?: break
            val close = xml.indexOf('>', open)
            if (close < 0) break

            if (xml[close - 1] == '/') {
                index = close + 1
                continue
            }
            val bodyEnd = xml.indexOfClosingTag(RESPONSE, close + 1) ?: break
            bodies += xml.substring(close + 1, bodyEnd)
            index = bodyEnd
        }
        return bodies
    }








    private fun String.indexOfTag(name: String, from: Int, closing: Boolean): Int? {
        var index = from
        while (index < length) {
            val open = indexOf('<', index)
            if (open < 0) return null
            val close = indexOf('>', open)
            if (close < 0) return null
            val tag = substring(open + 1, close).trim()

            if (!tag.startsWith("?") && !tag.startsWith("!")) {
                val isClosingTag = tag.startsWith("/")
                val local = tag.removePrefix("/")
                    .substringBefore(' ')
                    .substringBefore('/')
                    .substringAfterLast(':')


                if (isClosingTag == closing && local.equals(name, ignoreCase = true)) return open
            }
            index = close + 1
        }
        return null
    }

    private fun String.indexOfOpeningTag(name: String, from: Int): Int? = indexOfTag(name, from, closing = false)

    private fun String.indexOfClosingTag(name: String, from: Int): Int? = indexOfTag(name, from, closing = true)


    private fun String.textOf(name: String): String? {
        val open = indexOfOpeningTag(name, 0) ?: return null
        val close = indexOf('>', open)
        if (close < 0 || this[close - 1] == '/') return null
        val bodyStart = close + 1
        val bodyEnd = indexOfClosingTag(name, bodyStart) ?: return null
        return substring(bodyStart, bodyEnd).trim()
    }


    private fun String.hasChild(parent: String, child: String): Boolean {
        val open = indexOfOpeningTag(parent, 0) ?: return false
        val close = indexOf('>', open)
        if (close < 0 || this[close - 1] == '/') return false
        val inner = indexOfClosingTag(parent, close + 1) ?: return false
        return substring(close + 1, inner).indexOfOpeningTag(child, 0) != null
    }
}


class WebDavException(message: String) : Exception(message)








sealed interface WebDavFailure {
    fun message(): String

    data class Address(val problem: String) : WebDavFailure {
        override fun message(): String = problem
    }

    data class Refused(val status: Int, val detail: String) : WebDavFailure {
        override fun message(): String = when (status) {
            401 -> "That server did not accept the username and password."
            403 -> "That account is not allowed to read this folder."
            404 -> "There is nothing at that address."
            405 -> "That address is not a WebDAV share."
            in 500..599 -> "The server had a problem ($status). It is the server's, not yours."
            else -> "The server answered $status."
        }
    }

    data class Unreachable(val detail: String) : WebDavFailure {
        override fun message(): String = "Couldn’t reach that server."
    }

    companion object {

        fun describe(response: RawHttpText, prefix: String): WebDavFailure = when (val status = response.status) {
            401, 403, 404, 405 -> Refused(status, prefix)
            in 500..599 -> Refused(status, prefix)
            else -> Unreachable(prefix)
        }
    }
}
