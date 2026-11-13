package com.bxane.nuvx.data

import com.bxane.nuvx.data.http.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive






















object AppUpdateChecker {









    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val notes: String?,
    )

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/bxanedot/nuvx/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }













    suspend fun latest(): UpdateInfo? {
        val response = runCatching {
            Http.getRaw(
                url = LATEST_RELEASE_URL,
                headers = mapOf("Accept" to "application/vnd.github+json"),
                timeoutMillis = 10_000,
            )
        }.getOrNull() ?: return null
        if (response.status !in 200..299) return null
        val body = response.body ?: return null
        val release = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null
        return release.toUpdateInfo()
    }









    internal fun JsonElement.releaseInfo(): UpdateInfo? = (this as? JsonObject)?.toUpdateInfo()

    internal fun JsonObject.toUpdateInfo(): UpdateInfo? {
        val tag = string("tag_name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = string("html_url")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return UpdateInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            releaseUrl = url,
            notes = string("body")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()




















    fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) {


                if (l.isPreRelease && !c.isPreRelease) return false
                return a > b
            }
        }
        return c.isPreRelease && !l.isPreRelease
    }


    internal data class Version(val parts: List<Int>, val isPreRelease: Boolean)









    internal fun parseVersion(raw: String): Version {
        val text = raw.trim().removePrefix("v").removePrefix("V")
        val prereleaseAt = text.indexOfFirst { it == '-' || it == '+' }
        val numbers = if (prereleaseAt < 0) text else text.substring(0, prereleaseAt)



        val parts = numbers.split('.').map { it.toIntOrNull() ?: 0 }
        return Version(
            parts = parts.ifEmpty { listOf(0) },
            isPreRelease = prereleaseAt >= 0 && text.getOrNull(prereleaseAt) == '-',
        )
    }
}
