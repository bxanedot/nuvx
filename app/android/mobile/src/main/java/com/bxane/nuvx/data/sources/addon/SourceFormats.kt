package com.bxane.nuvx.data.sources.addon

import com.bxane.nuvx.data.Http
import com.bxane.nuvx.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

sealed interface DetectedFormat {

    data class Addon(val manifest: AddonManifest, val baseUrl: String) : DetectedFormat

    data class ModuleIndex(val moduleCount: Int, val url: String) : DetectedFormat

    data class Unsupported(val reason: String) : DetectedFormat
}

object SourceFormats {

    private const val TAG = "nuvx"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    suspend fun identify(rawUrl: String): Result<DetectedFormat> = withContext(Dispatchers.IO) {
        val url = rawUrl.trim().trimEnd('/')
        if (url.toHttpUrlOrNull() == null) {
            return@withContext Result.success(
                DetectedFormat.Unsupported("That is not a web address nuvx can open"),
            )
        }

        val base = AddonClient.normalizeBase(url)
        val isDocument = url.substringBefore('?').endsWith(".json", ignoreCase = true)
        val attempts = if (isDocument) {
            listOf(url, AddonClient.manifestUrl(base))
        } else {
            listOf(AddonClient.manifestUrl(url), url)
        }

        var lastFailure: Throwable? = null
        var firstUnsupported: DetectedFormat.Unsupported? = null

        for (attempt in attempts) {
            val body = fetch(attempt).getOrElse { failure ->
                lastFailure = failure
                continue
            }
            when (val found = detect(body, attempt)) {
                is DetectedFormat.Unsupported -> firstUnsupported = firstUnsupported ?: found
                else -> return@withContext Result.success(found)
            }
        }

        val probed = AddonClient(base).probeSearch()
        if (probed.isSuccess) {
            TrackLog.d(TAG, "identify(${AddonClient.redact(base)}) — no manifest, but /search answered")
            return@withContext Result.success(
                DetectedFormat.Addon(manifest = synthesised(base), baseUrl = base),
            )
        }

        firstUnsupported?.let { return@withContext Result.success(it) }
        Result.failure(lastFailure ?: AddonUnavailable("Nothing answered at that address"))
    }

    fun detect(body: String, url: String = ""): DetectedFormat {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return DetectedFormat.Unsupported("That address did not return JSON")

        val obj = root as? JsonObject
            ?: return DetectedFormat.Unsupported(
                "That JSON is a list, and every format nuvx reads is an object",
            )

        if (obj.keys.any { it.startsWith("category:") }) {
            val modules = runCatching {
                com.bxane.nuvx.data.sources.module.ModuleIndex.parseModules(json, body)
            }.getOrDefault(emptyList())
            return if (modules.isEmpty()) {
                DetectedFormat.Unsupported("That module index listed no modules")
            } else {
                DetectedFormat.ModuleIndex(modules.size, url)
            }
        }

        val looksLikeManifest = obj["resources"] is JsonArray ||
            (obj.containsKey("id") && (obj.containsKey("name") || obj.containsKey("version")))
        if (looksLikeManifest) {
            val manifest = runCatching { json.decodeFromJsonElement(AddonManifest.serializer(), obj) }
                .getOrNull()
                ?: return DetectedFormat.Unsupported("That looks like an addon manifest, but it could not be read")

            if (manifest.resources.isNotEmpty() && !manifest.declares("search")) {
                val declared = manifest.resources.joinToString(", ")
                return DetectedFormat.Unsupported(
                    "This addon declares $declared — nuvx needs search",
                )
            }
            return DetectedFormat.Addon(manifest, AddonClient.normalizeBase(url))
        }

        val listKey = obj.entries
            .firstOrNull { it.value is JsonArray && (it.value as JsonArray).isNotEmpty() }?.key
        if (listKey != null) {
            return DetectedFormat.Unsupported(
                "That JSON lists \"$listKey\", which is not a format nuvx reads",
            )
        }

        val keys = obj.keys.take(4).joinToString(", ").ifBlank { "nothing" }
        return DetectedFormat.Unsupported("Unrecognised JSON — it holds $keys")
    }

    private fun synthesised(base: String) = AddonManifest(
        id = base,
        name = base.toHttpUrlOrNull()?.host.orEmpty().ifBlank { base },
        resources = listOf("search", "stream"),
    )

    private suspend fun fetch(url: String): Result<String> = runCatching {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "nuvx/v${com.bxane.nuvx.BuildConfig.VERSION_NAME}")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code >= 500) throw AddonUnavailable("HTTP ${response.code}")
                throw AddonException("HTTP ${response.code}")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw AddonException("Empty response")
        }
    }.onFailure {
        TrackLog.d(TAG, "identify(${AddonClient.redact(url)}) failed: ${it.message}")
    }
}
