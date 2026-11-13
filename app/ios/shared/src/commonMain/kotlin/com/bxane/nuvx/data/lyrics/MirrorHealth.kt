package com.bxane.nuvx.data.lyrics

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi




























@OptIn(ExperimentalAtomicApi::class)
internal class MirrorHealth(
    private val now: () -> Long = { kotlin.time.TimeSource.Monotonic.markNow().let { _ -> clockMs() } },
) {
    private data class Host(val host: String, val failures: Int, val retryAfterMs: Long)

    private data class State(val hosts: List<Host> = emptyList(), val lastGood: String? = null)
    private val state = AtomicReference(State())



    private fun update(transform: (State) -> State): State {
        while (true) {
            val current = state.load()
            val next = transform(current)
            if (state.compareAndSet(current, next)) return next
        }
    }
    val lastGood: String? get() = state.load().lastGood
    val skippedCount: Int get() = state.load().hosts.count { it.retryAfterMs > 0L }








    fun order(mirrors: List<String>): List<String> {
        val now = now()
        val snapshot = update { it.copy(hosts = it.hosts.filter { host -> host.host in mirrors }) }
        val hosts = snapshot.hosts
        val due = hosts.filter { it.retryAfterMs <= now }.map { it.host }.toSet()


        val eligible = mirrors.filter { it in due || hosts.none { h -> h.host == it } }
        val live = if (eligible.isEmpty()) mirrors else eligible
        return snapshot.lastGood?.let { good ->
            buildList {
                if (live.contains(good)) add(good)
                addAll(live.filterNot { it == good })
            }
        } ?: live
    }


    fun answered(host: String) {
        update { it.copy(hosts = it.hosts.filterNot { item -> item.host == host }, lastGood = host) }
    }


    fun empty(host: String) {



    }








    fun unreachable(host: String) {
        update { current ->
            val previous = current.hosts.firstOrNull { it.host == host }
            val failures = (previous?.failures ?: 0) + 1
            val delay = (BASE_BACKOFF_MS shl (failures - 1).coerceAtMost(6)).coerceAtMost(MAX_BACKOFF_MS)
            current.copy(
                hosts = current.hosts.filterNot { it.host == host } + Host(host, failures, now() + delay),
                lastGood = current.lastGood.takeUnless { it == host },
            )
        }
    }

    fun reset() { state.store(State()) }

    private companion object {
        const val BASE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 15 * 60_000L








        fun clockMs(): Long = kotlin.time.TimeSource.Monotonic.markNow().let {



            launchMark.elapsedNow().inWholeMilliseconds
        }

        val launchMark = kotlin.time.TimeSource.Monotonic.markNow()
    }
}
