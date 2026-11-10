package com.bxane.nuvx

import com.bxane.nuvx.data.sources.module.SharedCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedCallsTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun <T> shared(
        ttlMs: Long,
        now: () -> Long = System::currentTimeMillis,
        onReuse: () -> Unit = {},
    ) = SharedCalls<T>(ttlMs = ttlMs, scope = scope(), log = { onReuse() }, now = now)

    @Test
    fun `a repeated question inside the window costs one call`() = runBlocking {
        val calls = AtomicInteger()
        val shared = shared<String>(ttlMs = 60_000)

        repeat(5) {
            val answer = shared.get("q", { "" }) {
                calls.incrementAndGet()
                Result.success("flac")
            }
            assertEquals("flac", answer.getOrNull())
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `an empty answer is kept, because it is an answer`() = runBlocking {
        val calls = AtomicInteger()
        val shared = shared<List<String>>(ttlMs = 60_000)

        repeat(3) {
            shared.get("missing", { "" }) {
                calls.incrementAndGet()
                Result.success(emptyList())
            }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `a failure is not kept`() = runBlocking {
        val calls = AtomicInteger()
        val shared = shared<String>(ttlMs = 60_000)

        repeat(3) {
            shared.get("q", { "" }) {
                calls.incrementAndGet()
                Result.failure(IllegalStateException("server having a bad minute"))
            }
        }
        assertEquals(3, calls.get())
    }

    @Test
    fun `an explicit refresh drops a completed answer`() = runBlocking {
        val calls = AtomicInteger()
        val shared = shared<String>(ttlMs = 60_000)

        shared.get("q", { "" }) { Result.success("answer-${calls.incrementAndGet()}") }
        shared.clearCompleted()
        val refreshed = shared.get("q", { "" }) {
            Result.success("answer-${calls.incrementAndGet()}")
        }

        assertEquals("answer-2", refreshed.getOrNull())
        assertEquals(2, calls.get())
    }

    @Test
    fun `an explicit refresh keeps a request already in flight`() = runBlocking {
        val calls = AtomicInteger()
        val joined = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val shared = shared<String>(ttlMs = 60_000, onReuse = { joined.incrementAndGet() })

        val first = async(Dispatchers.Default) {
            shared.get("q", { "" }) {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                Result.success("flac")
            }
        }
        started.await()
        shared.clearCompleted()
        val second = async(Dispatchers.Default) {
            shared.get("q", { "" }) {
                calls.incrementAndGet()
                Result.success("duplicate")
            }
        }
        while (joined.get() < 1) yield()
        release.complete(Unit)

        assertEquals("flac", first.await().getOrNull())
        assertEquals("flac", second.await().getOrNull())
        assertEquals(1, calls.get())
    }

    @Test
    fun `an expired answer is asked again`() = runBlocking {
        val calls = AtomicInteger()
        var clock = 0L
        val shared = shared<String>(ttlMs = 1_000, now = { clock })

        shared.get("q", { "" }) { calls.incrementAndGet(); Result.success("a") }
        clock = 999
        shared.get("q", { "" }) { calls.incrementAndGet(); Result.success("a") }
        assertEquals(1, calls.get())

        clock = 1_001
        shared.get("q", { "" }) { calls.incrementAndGet(); Result.success("a") }
        assertEquals(2, calls.get())
    }

    @Test
    fun `callers arriving mid-flight share the one call`() = runBlocking {
        val calls = AtomicInteger()
        val joined = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val shared = shared<String>(ttlMs = 0, onReuse = { joined.incrementAndGet() })

        val waiters: List<Deferred<Result<String>>> = List(4) {
            async(Dispatchers.Default) {
                shared.get("q", { "" }) {
                    calls.incrementAndGet()
                    release.await()
                    Result.success("flac")
                }
            }
        }
        while (joined.get() < 3) yield()
        release.complete(Unit)

        assertTrue(waiters.map { it.await() }.all { it.getOrNull() == "flac" })
        assertEquals(1, calls.get())
    }

    @Test
    fun `a cancelled caller does not kill the call others still want`() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val shared = shared<String>(ttlMs = 60_000)

        val abandoning = scope().launch {
            shared.get("q", { "" }) {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                Result.success("flac")
            }
        }
        started.await()
        abandoning.cancelAndJoin()
        release.complete(Unit)

        val answer = withTimeout(5_000) {
            shared.get("q", { "" }) { Result.success("never asked") }
        }
        assertEquals("flac", answer.getOrNull())
        assertEquals(1, calls.get())
    }

    @Test
    fun `held answers stay bounded`() = runBlocking {
        val shared = shared<String>(ttlMs = 60_000)
        repeat(400) { at -> shared.get("q$at", { "" }) { Result.success("a") } }
        assertTrue("held ${shared.size()}", shared.size() <= 128)
    }
}
