package com.bxane.nuvx.data.sources.module

import android.util.Log
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request

internal fun redactModuleUrl(url: String): String =
    runCatching {
        val uri = java.net.URI(url)
        "${uri.scheme}://***${uri.rawPath.orEmpty()}"
    }.getOrDefault("***")

class ModuleManager {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val loadedModules = java.util.concurrent.ConcurrentHashMap<String, LoadedModule>()

    data class LoadedModule(
        val module: SpineModule,
        val jsCode: String,
        val baseUrl: String,
    )

    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun keyOf(vararg parts: String) = parts.joinToString("|") { "${it.length}:$it" }

    private val loads = SharedCalls<LoadedModule>(ttlMs = 0, scope = calls, log = ::logReuse)
    private val searches = SharedCalls<ModuleSearchResponse>(SEARCH_TTL_MS, calls, ::logReuse)
    private val streams = SharedCalls<ModuleStreamResponse>(STREAM_TTL_MS, calls, ::logReuse)

    private fun logReuse(line: String) = TrackLog.d(TAG, line)

    private class CachedIndex(val modules: List<SpineModule>, val fetchedAtMs: Long)

    private val indexCache = mutableMapOf<String, CachedIndex>()
    private val indexLock = Mutex()

    suspend fun fetchIndex(sourceUrl: String): Result<List<SpineModule>> =
        withContext(Dispatchers.IO) {
            indexLock.withLock {
                val cached = indexCache[sourceUrl]
                if (cached != null &&
                    System.currentTimeMillis() - cached.fetchedAtMs < INDEX_TTL_MS
                ) {
                    TrackLog.d(TAG, "▶ fetchIndex(${redactModuleUrl(sourceUrl)}) — CACHE HIT (${cached.modules.size} modules)")
                    return@withContext Result.success(cached.modules)
                }

                TrackLog.d(TAG, "▶ fetchIndex(${redactModuleUrl(sourceUrl)})")
                runCatching {
                    val request = Request.Builder().url(sourceUrl).build()
                    Http.client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw Exception("HTTP ${resp.code} from ${redactModuleUrl(sourceUrl)}")
                        }
                        val body = resp.body?.string()
                            ?: throw Exception("Empty body from ${redactModuleUrl(sourceUrl)}")
                        val modules = ModuleIndex.parseModules(json, body)
                        TrackLog.d(TAG, "  Parsed ${modules.size} modules")
                        modules
                    }
                }.onSuccess {
                    indexCache[sourceUrl] = CachedIndex(it, System.currentTimeMillis())
                }.onFailure {
                    TrackLog.e(TAG, "  ✗ fetchIndex FAILED for ${redactModuleUrl(sourceUrl)}: ${it.message}", it)
                }
            }
        }

    suspend fun loadModule(
        module: SpineModule,
        resolveBaseUrl: suspend (String) -> String = { it },
    ): Result<LoadedModule> = loads.get(
        key = module.id,
        describe = { "▶ loadModule(${module.id})" },
    ) {
        loadModuleNow(module, resolveBaseUrl)
    }

    private suspend fun loadModuleNow(
        module: SpineModule,
        resolveBaseUrl: suspend (String) -> String,
    ): Result<LoadedModule> = withContext(Dispatchers.IO) {
        val cached = loadedModules[module.id]
        if (cached != null) {
            if (QuickJsExecutor.isLoaded(module.id)) {
                TrackLog.d(TAG, "▶ loadModule(${module.id}) — CACHE HIT")
                return@withContext Result.success(cached)
            }
            val revived = QuickJsExecutor
                .loadModule(module.id, cached.jsCode, cached.baseUrl)
                .map { cached }
            if (revived.isSuccess) return@withContext revived
            loadedModules.remove(module.id)
        }

        TrackLog.d(TAG, "▶ loadModule(${module.id}) download=${module.download}")
        runCatching {
            val downloadUrl = if (module.download.startsWith("http")) {
                module.download
            } else {
                val base = resolveBaseUrl(module.download)
                "$base/${module.download}"
            }

            TrackLog.d(TAG, "  Resolved download URL: ${redactModuleUrl(downloadUrl)}")
            val request = Request.Builder().url(downloadUrl).build()
            val jsCode = Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("HTTP ${resp.code} downloading module ${module.id}")
                }
                resp.body?.string() ?: throw Exception("Empty body for module ${module.id}")
            }
            val baseUrl = downloadUrl.substringBeforeLast("/")

            QuickJsExecutor.loadModule(module.id, jsCode, baseUrl).getOrThrow()

            val loaded = LoadedModule(module = module, jsCode = jsCode, baseUrl = baseUrl)
            loadedModules[module.id] = loaded
            TrackLog.d(TAG, "  ✓ Loaded module ${module.id}: ${jsCode.length} chars, baseUrl=${redactModuleUrl(baseUrl)}")
            loaded
        }.onFailure {
            TrackLog.e(TAG, "  ✗ loadModule FAILED for ${module.id}: ${it.message}", it)
        }
    }

    suspend fun searchTracks(
        loaded: LoadedModule,
        query: String,
        limit: Int = 50,
        settings: Map<String, String> = emptyMap(),
    ): Result<ModuleSearchResponse> = searches.get(
        key = keyOf(loaded.module.id, query, limit.toString(), contextArg(settings)),
        describe = { "▶ searchTracks() module=${loaded.module.id} query=\"$query\" limit=$limit" },
    ) {
        searchTracksNow(loaded, query, limit, settings)
    }

    private suspend fun searchTracksNow(
        loaded: LoadedModule,
        query: String,
        limit: Int,
        settings: Map<String, String>,
    ): Result<ModuleSearchResponse> = withContext(Dispatchers.IO) {
        val contextArg = contextArg(settings)
        TrackLog.d(TAG, "▶ searchTracks() module=${loaded.module.id} query=\"$query\" limit=$limit")
        runCatching {
            val result = QuickJsExecutor.callExport(
                moduleId = loaded.module.id,
                functionName = "searchTracks",
                args = listOf("\"$query\"", limit.toString(), contextArg),
            ).getOrThrow()
            json.decodeFromString<ModuleSearchResponse>(result).also {
                TrackLog.d(TAG, "  ✓ Parsed ${it.tracks.size} tracks (total=${it.total})")
            }
        }.onCancellation().onFailure {
            TrackLog.e(TAG, "  ✗ searchTracks FAILED for ${loaded.module.id} query='$query': ${it.message}", it)
        }
    }

    suspend fun getStreamUrl(
        loaded: LoadedModule,
        trackId: String,
        quality: String = "",
        settings: Map<String, String> = emptyMap(),
    ): Result<ModuleStreamResponse> = streams.get(
        key = keyOf(loaded.module.id, trackId, quality, contextArg(settings)),
        describe = { "▶ getStreamUrl() module=${loaded.module.id} trackId=$trackId quality=$quality" },
    ) {
        getStreamUrlNow(loaded, trackId, quality, settings)
    }

    private suspend fun getStreamUrlNow(
        loaded: LoadedModule,
        trackId: String,
        quality: String,
        settings: Map<String, String>,
    ): Result<ModuleStreamResponse> = withContext(Dispatchers.IO) {
        val contextArg = contextArg(settings)
        TrackLog.d(TAG, "▶ getStreamUrl() module=${loaded.module.id} trackId=$trackId quality=$quality")
        runCatching {
            val result = QuickJsExecutor.callExport(
                moduleId = loaded.module.id,
                functionName = "getTrackStreamUrl",
                args = listOf("\"$trackId\"", "\"$quality\"", contextArg),
            ).getOrThrow()
            json.decodeFromString<ModuleStreamResponse>(result).also {
                TrackLog.d(TAG, "  ✓ streamUrl=${it.streamUrl?.take(100) ?: "<none>"} quality=${it.track?.audioQuality}")
            }
        }.onCancellation().onFailure {
            TrackLog.e(TAG, "  ✗ getStreamUrl FAILED for ${loaded.module.id} trackId=$trackId: ${it.message}", it)
        }
    }

    private fun <T> Result<T>.onCancellation(): Result<T> = also {
        (exceptionOrNull() as? CancellationException)?.let { throw it }
    }

    private fun contextArg(settings: Map<String, String>): String =
        settings.entries.joinToString(
            separator = ",",
            prefix = "{settings:{",
            postfix = "}}",
        ) { (key, value) -> "\"$key\":{\"value\":\"$value\"}" }

    fun unloadModule(moduleId: String) {
        loadedModules.remove(moduleId)
        QuickJsExecutor.unload(moduleId)
    }

    fun unloadAll() {
        loadedModules.clear()
        loads.clear()
        searches.clear()
        streams.clear()
        QuickJsExecutor.unloadAll()
    }

    private companion object {
        const val TAG = "nuvx"

        const val INDEX_TTL_MS = 10 * 60 * 1000L

        const val SEARCH_TTL_MS = 10 * 60 * 1000L

        const val STREAM_TTL_MS = 5 * 60 * 1000L

        const val MAX_SHARED = 128
    }
}
