package com.bxane.nuvx.data.sources.module

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal class SharedCalls<T>(
    private val ttlMs: Long,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    private class Pending<T>(val work: Deferred<Result<T>>, val startedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Pending<T>>()

    private val lock = Mutex()

    suspend fun get(key: String, describe: () -> String, produce: suspend () -> Result<T>): Result<T> {
        val entry = lock.withLock {
            val live = entries[key]?.takeIf { it.work.isActive || now() - it.startedAtMs < ttlMs }
            if (live != null) {
                log(describe() + if (live.work.isActive) " — ALREADY RUNNING" else " — CACHE HIT")
                live
            } else {
                if (entries.size >= MAX_ENTRIES) prune()
                Pending(scope.async { produce() }, now()).also { entries[key] = it }
            }
        }
        return entry.work.await().also { result ->
            if (result.isFailure) entries.remove(key, entry)
        }
    }

    fun clear() = entries.clear()

    fun clearCompleted() {
        entries.entries.removeIf { !it.value.work.isActive }
    }

    internal fun size() = entries.size

    private fun prune() {
        val at = now()
        entries.entries.removeIf { !it.value.work.isActive && at - it.value.startedAtMs >= ttlMs }
        if (entries.size >= MAX_ENTRIES) {
            entries.entries
                .filterNot { it.value.work.isActive }
                .minByOrNull { it.value.startedAtMs }
                ?.let { entries.remove(it.key, it.value) }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 128
    }
}
