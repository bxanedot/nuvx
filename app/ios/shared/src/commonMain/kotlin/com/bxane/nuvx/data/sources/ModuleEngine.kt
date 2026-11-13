package com.bxane.nuvx.data.sources

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume


































object ModuleEngine {

    interface Impl {

        fun search(moduleId: String, scriptUrl: String, query: String, tier: String?, callback: RowsCallback)


        fun stream(moduleId: String, scriptUrl: String, trackId: String, tier: String?, callback: StreamCallback)
    }

    fun interface RowsCallback {
        fun onResult(json: String?, error: String?)
    }

    fun interface StreamCallback {
        fun onResult(json: String?, error: String?)
    }

    @Volatile
    private var impl: Impl? = null

    fun setImpl(value: Impl?) {
        impl = value
    }


    private val loaded = mutableSetOf<String>()

    internal suspend fun search(entry: ModuleEntry, query: String, tier: String?): List<ModuleRow> {
        val bridge = impl ?: return emptyList()
        loaded += entry.id
        val raw: String? = suspendCancellableCoroutine { cont ->
            val done = RowsCallback { json, _ -> if (cont.isActive) cont.resume(json) }
            bridge.search(entry.id, entry.download, query, tier, done)
        }
        return raw?.let { ModuleEngineJson.decodeRows(it) }.orEmpty()
    }

    internal suspend fun stream(
        entry: ModuleEntry,
        trackId: String,
        tier: String?,
    ): ModuleStream? {
        val bridge = impl ?: return null
        val raw: String? = suspendCancellableCoroutine { cont ->
            val done = StreamCallback { json, _ -> if (cont.isActive) cont.resume(json) }
            bridge.stream(entry.id, entry.download, trackId, tier, done)
        }
        return raw?.let { ModuleEngineJson.decodeStream(it) }
    }


    internal fun forget(configId: String) {
        loaded -= configId
    }
}
