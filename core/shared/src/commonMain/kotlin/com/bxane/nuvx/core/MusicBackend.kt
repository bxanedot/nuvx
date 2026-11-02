package com.bxane.nuvx.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** YouTube Music's own search filter tokens (the `params` its web client sends for each chip). */
enum class SearchFilter(val label: String, val params: String?) {
    ALL("All", null),
    SONGS("Songs", "EgWKAQIIAWoMEA4QChADEAQQCRAF"),
    VIDEOS("Videos", "EgWKAQIQAWoMEA4QChADEAQQCRAF"),
}

enum class BackendId(val label: String) { INNERTUBEX("InnerTubeX"), EXCOR("eXcor") }

/** One YouTube Music engine. Every call returns null or throws when the engine cannot answer; none fabricates results. */
interface MusicBackend {
    val id: BackendId
    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL): SearchResults
    suspend fun radio(seedVideoId: String, continuation: String? = null): RadioPage
    suspend fun stream(videoId: String): AudioStream?
}

class BackendFailure(val backend: BackendId, val operation: String, cause: Throwable?) :
    Exception("${backend.label} $operation failed${cause?.message?.let { ": $it" } ?: ""}", cause)

/** Last observed outcome per backend, shown in Settings so users see real engine status instead of a toggle. */
data class BackendHealth(val ok: Boolean, val operation: String, val detail: String?, val latencyMs: Long)

/**
 * Routes every catalogue and stream call to the selected engine. [fallback] retries a failed or empty call on the
 * other engine; the result's [AudioStream.backend] and [health] say which engine actually answered.
 */
class BackendRouter(
    private val backends: Map<BackendId, MusicBackend>,
    selected: BackendId = BackendId.INNERTUBEX,
    fallback: Boolean = true,
) {
    private val _selected = MutableStateFlow(selected)
    val selected: StateFlow<BackendId> = _selected.asStateFlow()
    private val _fallback = MutableStateFlow(fallback)
    val fallback: StateFlow<Boolean> = _fallback.asStateFlow()
    private val _health = MutableStateFlow<Map<BackendId, BackendHealth>>(emptyMap())
    val health: StateFlow<Map<BackendId, BackendHealth>> = _health.asStateFlow()

    val available: Set<BackendId> get() = backends.keys

    fun select(id: BackendId) {
        require(id in backends) { "${id.label} is not available on this platform" }
        _selected.value = id
    }

    fun setFallback(enabled: Boolean) { _fallback.value = enabled }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL) =
        route("search", { it.tracks.isEmpty() && it.collections.isEmpty() }) { it.search(query, filter) }

    suspend fun radio(seed: String, continuation: String? = null) = route("radio", { it.tracks.isEmpty() }) { it.radio(seed, continuation) }

    suspend fun stream(videoId: String): AudioStream? = runCatching { route("stream", { it == null }) { it.stream(videoId) } }
        .getOrElse { if (it is CancellationException) throw it else null }

    private suspend fun <T> route(op: String, empty: (T) -> Boolean, call: suspend (MusicBackend) -> T): T {
        val order = listOf(_selected.value) + if (_fallback.value) backends.keys.filter { it != _selected.value } else emptyList()
        var lastError: Throwable? = null
        var lastEmpty: T? = null
        for (id in order) {
            val backend = backends[id] ?: continue
            val start = TimeSource.Monotonic.markNow()
            try {
                val result = call(backend)
                val isEmpty = empty(result)
                record(id, BackendHealth(!isEmpty, op, if (isEmpty) "no results" else null, start.elapsedNow().inWholeMilliseconds))
                if (!isEmpty) return result
                lastEmpty = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                record(id, BackendHealth(false, op, e.message ?: e::class.simpleName, start.elapsedNow().inWholeMilliseconds))
                lastError = e
            }
        }
        @Suppress("UNCHECKED_CAST")
        return lastEmpty ?: throw BackendFailure(_selected.value, op, lastError)
    }

    private fun record(id: BackendId, h: BackendHealth) = _health.update { it + (id to h) }
}
