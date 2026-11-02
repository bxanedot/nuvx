package com.bxane.nuvx.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/** Two clients against the deployed worker. Opt-in: NUVX_LIVE=1. */
class LiveListenTogetherTest {
    private class FakePlayer : NuvxPlayer {
        override val state = MutableStateFlow(PlayerState())
        override fun playQueue(tracks: List<Track>, startIndex: Int) { state.value = PlayerState(tracks, startIndex, isPlaying = true, durationMs = 300_000) }
        override fun enqueue(tracks: List<Track>) = Unit
        override fun togglePlay() { state.value = state.value.copy(isPlaying = !state.value.isPlaying) }
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
        override fun next() = Unit
        override fun previous() = Unit
        override fun skipTo(index: Int) = Unit
    }

    @Test fun guestFollowsHost() = runBlocking {
        assumeTrue("Set NUVX_LIVE=1 to contact the Listen Together worker", System.getenv("NUVX_LIVE") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hostPlayer = FakePlayer().apply { playQueue(listOf(Track("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"))), 0); seekTo(42_000) }
            val host = ListenTogether(hostPlayer, scope).apply { create("Host") }
            val code = kotlinx.coroutines.withTimeoutOrNull(15_000) { host.ui.first { it.inRoom }.code!! } ?: error("host never joined: ${host.ui.value}")
            val guestPlayer = FakePlayer()
            val guest = ListenTogether(guestPlayer, scope).apply { join(code, "Guest") }
            withTimeout(15_000) { guest.ui.first { it.members.size == 2 } }
            val synced = withTimeout(15_000) { guestPlayer.state.first { it.current?.videoId == "dQw4w9WgXcQ" && it.positionMs >= 42_000 } }
            assertEquals("Never Gonna Give You Up", synced.current?.title)
            println("guest synced to ${synced.current?.title} @ ${synced.positionMs} ms in room $code")
            hostPlayer.togglePlay()
            withTimeout(15_000) { guestPlayer.state.first { !it.isPlaying } }
            println("guest paused with host")
            host.leave()
            withTimeout(15_000) { guest.ui.first { it.isHost } }
            println("guest became host after host left")
            guest.leave()
        } finally {
            scope.cancel()
        }
    }
}
