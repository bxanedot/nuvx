package com.bxane.nuvx.data.sources.module

import com.bxane.nuvx.data.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource








































internal class SharedCalls<T>(
    private val ttl: Duration,
    private val scope: CoroutineScope,

    private val log: (String) -> Unit = {},
) {







    private class Pending<T>(val work: Deferred<Result<T>>, val startedAt: TimeMark)

    private val entries = HashMap<String, Pending<T>>()






    private val lock = Mutex()

    suspend fun get(
        key: String,
        describe: () -> String,
        produce: suspend () -> Result<T>,
    ): Result<T> {
        val entry = lock.withLock {
            val live = entries[key]?.takeIf {
                it.work.isActive || it.startedAt.elapsedNow() < ttl
            }
            if (live != null) {
                log(describe() + if (live.work.isActive) " — ALREADY RUNNING" else " — CACHE HIT")
                live
            } else {
                if (entries.size >= MAX_ENTRIES) prune()
                Pending(scope.async { produce() }, TimeSource.Monotonic.markNow()).also {
                    entries[key] = it
                }
            }
        }
        return entry.work.await().also { result ->





            if (result.isFailure && entries[key] === entry) entries.remove(key)
        }
    }


    fun clear() = entries.clear()










    fun clearCompleted() {
        entries.entries.removeAll { !it.value.work.isActive }
    }


    internal fun size(): Int = entries.size








    private fun prune() {
        entries.entries.sortedBy { it.value.startedAt.elapsedNow() }
            .take(entries.size / 4)
            .forEach { entries.remove(it.key) }
        if (entries.size >= MAX_ENTRIES) entries.clear()
    }

    private companion object {

        const val MAX_ENTRIES = 128
    }
}
