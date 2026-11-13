package com.bxane.nuvx.data.sources.addon

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.http.Http
import com.bxane.nuvx.data.sources.percent
import com.bxane.nuvx.data.sources.module.SharedCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource















class AddonClient(rawBaseUrl: String, refreshSource: String? = null) {


    val baseUrl: String = normalizeBase(rawBaseUrl)
    private val refreshUrl = refreshSource?.trim()?.takeIf { it.isNotEmpty() }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val manifests = SharedCalls<AddonManifest>(MANIFEST_TTL, calls) { DebugLog.d(it) }
    private val searches = SharedCalls<AddonSearchResponse>(SEARCH_TTL, calls) { DebugLog.d(it) }
    private val streams = SharedCalls<AddonStream>(STREAM_TTL, calls) { DebugLog.d(it) }











    @Volatile
    private var quietUntil: TimeMark? = null













    suspend fun manifest(): Result<AddonManifest> {
        val identity = cacheIdentity().getOrElse { return Result.failure(it) }
        return manifests.get(
            key = identity,
            describe = { "addon manifest(${redact(baseUrl)})" },
        ) {


            fetch<AddonManifest>(manifestUrl(baseUrl)).mapCatching { manifest ->
                if (manifest.id.isBlank()) {
                    throw AddonException("That URL answered, but not with an addon manifest")
                }
                if (!manifest.isPlayable) {
                    val declared = manifest.resources.joinToString(", ")
                    throw AddonException("This addon declares $declared — nuvx needs search and stream")
                }
                manifest
            }.recoverCatching { failure ->


                if (failure is AddonNotFound) throw AddonException("No manifest at that URL")
                throw failure
            }
        }
    }














    suspend fun probeSearch(): Result<Int> =
        fetch<AddonSearchResponse>(endpoint(listOf("search"), mapOf("q" to PROBE_QUERY)))
            .map { it.tracks.size }










    suspend fun search(query: String, tier: String): Result<List<AddonTrack>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Result.success(emptyList())
        val params = settingsFor(tier)
        val identity = cacheIdentity().getOrElse { return Result.failure(it) }
        return searches.get(




            key = keyOf(trimmed, params.toString(), identity),
            describe = { "addon search(${redact(baseUrl)}) q=\"$trimmed\" tier=$tier" },
        ) {
            fetch<AddonSearchResponse>(endpoint(listOf("search"), params + ("q" to trimmed)))
        }.map { it.tracks }
    }











    suspend fun stream(trackId: String, tier: String): Result<AddonStream> {
        val params = settingsFor(tier)
        val identity = cacheIdentity().getOrElse { return Result.failure(it) }
        return streams.get(
            key = keyOf(trackId, params.toString(), identity),
            describe = { "addon stream(${redact(baseUrl)}) tier=$tier" },
        ) {
            fetch<AddonStream>(endpoint(listOf("stream", trackId), params))
        }
    }


























    private suspend fun settingsFor(tier: String): Map<String, String> {
        val declared = manifest().getOrNull()?.settings.orEmpty()
        val params = LinkedHashMap<String, String>()
        declared.forEach { setting ->
            val key = setting.key.takeIf { it.isNotBlank() } ?: return@forEach
            setting.defaultValue?.let { params[key] = it }
        }
        if (tier.isNotBlank()) {
            val options = declared.firstOrNull { it.key == QUALITY_KEY }
                ?.options.orEmpty()
                .map { it.stringValue }
            params[QUALITY_KEY] = matchTier(tier, options) ?: tier
        }
        return params
    }










    private fun matchTier(tier: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        options.firstOrNull { it.equals(tier, ignoreCase = true) }?.let { return it }
        val wanted = when (tier.uppercase()) {
            TIER_LOSSLESS -> LOSSLESS_WORDS
            TIER_LOW -> LOW_WORDS
            else -> HIGH_WORDS
        }
        return options.firstOrNull { option -> wanted.any { it in option.lowercase() } }
            ?: options.first()
    }





    private suspend fun cacheIdentity(): Result<String> =
        runCatching { AddonRefresh.cacheIdentity(baseUrl, refreshUrl) }.onFailure {
            if (it is CancellationException) throw it
        }


    private fun endpoint(segments: List<String>, params: Map<String, String>): String {
        if (baseUrl.isBlank()) throw AddonException("That is not a usable address")
        val query = params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
        val path = segments.joinToString("/") { encode(it) }
        val base = baseUrl.trimEnd('/')
        return if (query.isEmpty()) "$base/$path" else "$base/$path?$query"
    }

    private suspend inline fun <reified T> fetch(url: String): Result<T> =
        runCatching { json.decodeFromString<T>(body(url)) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure


                if (failure !is AddonNotFound) {
                    DebugLog.w("addon call failed ${redact(url)}: ${failure.message}")
                }
            }
















    private suspend fun body(url: String): String = withContext(Dispatchers.Default) {
        var attempt = 0
        var requestUrl = AddonRefresh.rewrite(url, baseUrl, refreshUrl)
        var renewed = false
        while (true) {
            quietUntil?.let { quiet ->
                val remaining = quiet.elapsedNow()
                if (remaining < MAX_QUIET) delay(remaining)
            }

            val wait = try {
                val response = Http.getRaw(
                    url = requestUrl,
                    headers = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT),
                    timeoutMillis = CALL_TIMEOUT_MS,
                )
                when {
                    response.status in 200..299 ->
                        return@withContext response.body?.takeIf { it.isNotBlank() }
                            ?: throw AddonException("Empty response")
                    !renewed && refreshUrl != null && (response.status in listOf(401, 403, 410) ||
                        (response.status == 404 && url.substringBefore('?').endsWith("/manifest.json"))) -> {
                        requestUrl = AddonRefresh.rewrite(url, baseUrl, refreshUrl, rejectedUrl = requestUrl)
                        renewed = true
                        continue
                    }
                    response.status == 404 -> throw AddonNotFound()







                    response.status >= 500 -> throw AddonUnavailable("HTTP ${response.status}")
                    response.status != 429 -> throw AddonException("HTTP ${response.status}")
                    attempt >= MAX_RETRIES -> throw AddonUnavailable("This addon is rate limiting Nuvx")
                    else -> retryAfter(response.body, attempt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AddonNotFound) {
                throw e
            } catch (e: AddonException) {
                throw e
            } catch (e: AddonUnavailable) {
                throw e
            }
            quietUntil = TimeSource.Monotonic.markNow() + wait
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        throw AddonException("unreachable")
    }










    private fun retryAfter(body: String?, attempt: Int): Duration {
        body?.let { text ->
            Regex("\"retryAfter\"\\s*:\\s*([0-9.]+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.let { return (it.seconds).coerceIn(BACKOFF_BASE, BACKOFF_CAP) }
        }
        return (BACKOFF_BASE * (1 shl attempt)).coerceAtMost(BACKOFF_CAP)
    }











    fun clear() {
        manifests.clear()
        searches.clear()
        streams.clear()
        quietUntil = null
    }










    fun clearCompletedTrackCalls() {
        searches.clearCompleted()
        streams.clearCompleted()
    }


    private fun keyOf(vararg parts: String) = parts.joinToString("|") { "${it.length}:$it" }

    companion object {
        private const val TAG = "nuvx"


        const val TIER_LOSSLESS = "LOSSLESS"
        const val TIER_HIGH = "HIGH"
        const val TIER_LOW = "LOW"

        private const val QUALITY_KEY = "quality"

        private val LOSSLESS_WORDS = listOf("lossless", "flac", "hifi", "hi-res", "hires", "max", "best")
        private val HIGH_WORDS = listOf("high", "320", "normal", "standard")
        private val LOW_WORDS = listOf("low", "96", "128", "min")

        private const val MANIFEST_SUFFIX = "/manifest.json"















        fun normalizeBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')

            val path = trimmed.substringAfter("://", "").substringAfter('/', "")
            val lastSegment = path.substringAfterLast('/')
            return if (lastSegment.endsWith(".json", ignoreCase = true)) {
                trimmed.dropLast(lastSegment.length).trimEnd('/')
            } else {
                trimmed
            }
        }

        fun manifestUrl(base: String): String = "$base$MANIFEST_SUFFIX"









        fun redact(url: String): String {
            val scheme = url.substringBefore("://", missingDelimiterValue = "")
            if (scheme.isEmpty()) return "***"
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
            return "$scheme://$host/***"
        }









        val MANIFEST_TTL: Duration = 10.minutes


        val SEARCH_TTL: Duration = 10.minutes








        val STREAM_TTL: Duration = 5.minutes

        private const val MAX_RETRIES = 2
        private val BACKOFF_BASE: Duration = 500.milliseconds
        private val BACKOFF_CAP: Duration = 8.seconds
        private val MAX_QUIET: Duration = 8.seconds
        private const val CALL_TIMEOUT_MS = 20_000L
        private const val USER_AGENT = "nuvx"







        private const val PROBE_QUERY = "music"
    }
}







class AddonException(message: String) : Exception(message)






class AddonUnavailable(message: String) : Exception(message)


class AddonNotFound : Exception("Not held by this addon")


private fun encode(value: String): String =
    value.encodeToByteArray().joinToString("") { byte ->
        val c = byte.toInt().toChar()
        if ((c.isLetterOrDigit() && c.code < 128) || c in "-_.~") c.toString() else percent(byte.toInt() and 0xFF)
    }
