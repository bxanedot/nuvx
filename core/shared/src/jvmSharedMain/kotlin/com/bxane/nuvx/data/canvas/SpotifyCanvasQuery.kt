package com.bxane.nuvx.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object SpotifyCanvasQuery {

    const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"
    const val OPERATION = "canvas"

    const val KNOWN_CANVAS_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun requestBody(trackUri: String, hash: String): String = buildJsonObject {
        put("operationName", OPERATION)
        putJsonObject("variables") { put("trackUri", trackUri) }
        putJsonObject("extensions") {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", hash)
            }
        }
    }.toString()

    sealed interface Answer {
        data class Found(val url: String, val type: String?) : Answer

        data class NoCanvas(val detail: String) : Answer

        data class Failed(val reason: String, val staleHash: Boolean = false) : Answer
    }

    fun parse(body: String): Answer {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return Answer.Failed("not JSON")
        val errors = (root["errors"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("message") }.orEmpty()
        val data = root["data"] as? JsonObject
        if (data == null) {
            val reason = errors.joinToString("; ").ifBlank { "no data" }
            return Answer.Failed(reason, staleHash = errors.any { it.contains("PersistedQueryNotFound", ignoreCase = true) })
        }
        val canvas = (data["trackUnion"] as? JsonObject)?.get("canvas") as? JsonObject
            ?: return Answer.NoCanvas("canvas null")
        val detail = "type=${canvas.text("type")} fileId=${canvas.text("fileId")} url=${canvas.text("url")}"
        val url = canvas.text("url")?.takeIf { it.startsWith("https://") } ?: return Answer.NoCanvas(detail)
        if (!VIDEO_URL.containsMatchIn(url)) return Answer.NoCanvas(detail)
        return Answer.Found(url, canvas.text("type"))
    }

    private val VIDEO_URL = Regex("""\.mp4(?:[?#]|$)""", RegexOption.IGNORE_CASE)

    private val OPERATION_DECLARATION =
        Regex("""["'](\w+)["']\s*,\s*["']query["']\s*,\s*["']([0-9a-f]{64})["']""")

    fun findQueryHash(script: String, operation: String = OPERATION): String? =
        OPERATION_DECLARATION.findAll(script).firstOrNull { it.groupValues[1] == operation }?.groupValues?.get(2)

    fun webPlayerScripts(html: String): List<String> =
        WEB_PLAYER_SCRIPT.findAll(html).map { it.value }.distinct().toList()

    private val WEB_PLAYER_SCRIPT =
        Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/[A-Za-z0-9._~-]+\.js""")

    class QueryHashes(
        private val fetch: (String) -> String?,
        private val now: () -> Long = System::currentTimeMillis,
        private val maxScripts: Int = 8,
    ) {
        @Volatile private var hash: String? = null
        @Volatile private var checkedAt = 0L

        @Synchronized
        fun canvasHash(forceRefresh: Boolean = false): String {
            val current = hash
            val age = now() - checkedAt
            if (!forceRefresh && checkedAt != 0L && age < (if (current != null) FOUND_TTL_MS else MISSED_TTL_MS)) {
                return current ?: KNOWN_CANVAS_HASH
            }
            checkedAt = now()
            val found = lookUp()
            if (found != null) hash = found
            return hash ?: KNOWN_CANVAS_HASH
        }

        private fun lookUp(): String? {
            val html = fetch("https://open.spotify.com/") ?: return null
            for (script in webPlayerScripts(html).take(maxScripts)) {
                val body = fetch(script) ?: continue
                findQueryHash(body)?.let { return it }
            }
            return null
        }

        private companion object {
            const val FOUND_TTL_MS = 12L * 60 * 60 * 1000
            const val MISSED_TTL_MS = 30L * 60 * 1000
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}
