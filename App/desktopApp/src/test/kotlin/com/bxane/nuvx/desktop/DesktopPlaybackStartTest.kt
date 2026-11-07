package com.bxane.nuvx.desktop

import com.bxane.nuvx.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import uk.co.caprica.vlcj.player.base.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopPlaybackStartTest {
    @Test fun waitsForPreparedPausedInputBeforeSeekingOrResuming() {
        val start = DesktopPlaybackStart(12_000, true)
        val commands = mutableListOf<String>()
        for (state in listOf(State.NOTHING_SPECIAL, State.STOPPED, State.OPENING, State.BUFFERING, State.PLAYING, State.ERROR)) {
            assertFalse(start.apply(state, { commands += "seek:$it" }, { commands += "play" }, { commands += "pause" }))
        }
        assertTrue(commands.isEmpty(), "a seek sent before the new input is ready would be lost")
        assertFalse(start.apply(State.PAUSED, { commands += "seek:$it" }, { commands += "play" }, { commands += "pause" }))
        assertFalse(start.apply(State.PAUSED, { commands += "seek:$it" }, { commands += "play" }, { commands += "pause" }))
        assertTrue(start.apply(State.PLAYING, { commands += "seek:$it" }, { commands += "play" }, { commands += "pause" }))
        assertEquals(listOf("seek:12000", "play"), commands)
    }

    @Test fun pausedRoomSeeksWithoutStartingPlayback() {
        val start = DesktopPlaybackStart(12_000, false)
        val commands = mutableListOf<String>()
        assertTrue(start.apply(State.PAUSED, { commands += "seek:$it" }, { commands += "play" }, { commands += "pause" }))
        assertEquals(listOf("seek:12000"), commands)
    }

    @Test fun latestSeekAndPlayIntentDuringPreparationWin() {
        val start = DesktopPlaybackStart(12_000, true)
        start.positionMs = 18_000
        start.autoplay = false
        val commands = mutableListOf<String>()
        start.apply(State.PAUSED, { commands += "seek:$it" }, { commands += "play" }, { commands += "pause" })
        assertEquals(listOf("seek:18000"), commands)
        start.positionMs = -40
        assertEquals(0, start.positionMs)
        assertEquals(0, DesktopPlaybackStart(-40, false).positionMs)
    }

    @Test fun autoplayRemainsCancellableUntilNativePlaybackStarts() {
        val start = DesktopPlaybackStart(12_000, true)
        val playback = PreparingPlayback()
        assertFalse(playback.apply(start), "a pending native resume must remain cancellable by the next play/pause toggle")
        assertEquals(12_000, playback.positionMs)

        start.positionMs = 18_000
        start.autoplay = false
        assertFalse(playback.apply(start), "being paused now must not hide an earlier play request still in flight")
        playback.completeNextCommand()
        assertEquals(State.PLAYING, playback.state, "ordinary autoplay must still start when its native request completes")
        assertFalse(playback.apply(start))
        playback.completeNextCommand()
        assertTrue(playback.apply(start))

        assertEquals(State.PAUSED, playback.state, "the later pause intent must win over the asynchronous autoplay request")
        assertEquals(18_000, playback.positionMs, "the later seek must survive the play/pause handoff")
    }

    @Test fun resolverTimeoutClearsPendingStartAndPublishesPlaybackFailure() = runBlocking<Unit> {
        withTimeout(5_000) {
            for (kind in listOf(MediaKind.SONG, MediaKind.VIDEO)) {
                val track = Track("aaaaaaaaaaa", "Waiting", mediaKind = kind)
                val state = MutableStateFlow(PlayerState(listOf(track), 0, buffering = true,
                    audioFormat = AudioFormatInfo("audio/mock"), videoResolution = VideoResolution(1280, 720)))
                var pendingStart: DesktopPlaybackStart? = DesktopPlaybackStart(0, true)

                val completed = runCatching {
                    loadDesktopPlayback(state, { pendingStart = null }) {
                        withContext(Dispatchers.IO) { withTimeout(10) { awaitCancellation() } }
                    }
                }

                assertNull(pendingStart, "a resolver deadline must release the pending start")
                assertFalse(state.value.buffering, "a timed-out current load must stop the spinner")
                assertEquals(track.playbackFailureMessage(), state.value.error)
                assertNull(state.value.audioFormat)
                assertNull(state.value.videoResolution)
                assertTrue(completed.isSuccess, "a resolver deadline is a playback failure, not caller cancellation")
            }
        }
    }

    @Test fun cancelledLoadCannotClearOrFailItsReplacementEvenAfterResolverTimeout() = runBlocking<Unit> {
        withTimeout(5_000) {
            for (expiresFirst in listOf(false, true)) {
                val state = MutableStateFlow(PlayerState(listOf(Track("aaaaaaaaaaa", "Old")), 0, buffering = true))
                var pendingStart: DesktopPlaybackStart? = DesktopPlaybackStart(0, true)
                val started = CompletableDeferred<Unit>()
                val finishing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var cancellationPropagated = false
                val oldLoad = launch {
                    try {
                        loadDesktopPlayback(state, { pendingStart = null }) {
                            started.complete(Unit)
                            try {
                                if (expiresFirst) withTimeout(10) { awaitCancellation() } else awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    finishing.complete(Unit)
                                    release.await()
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        cancellationPropagated = true
                        throw e
                    }
                }
                try {
                    started.await()
                    if (expiresFirst) finishing.await()
                    oldLoad.cancel()
                    finishing.await()
                    val replacement = PlayerState(listOf(Track("bbbbbbbbbbb", "New")), 0, buffering = true, positionMs = 18_000)
                    val replacementStart = DesktopPlaybackStart(18_000, false)
                    state.value = replacement
                    pendingStart = replacementStart
                    release.complete(Unit)
                    oldLoad.join()

                    assertTrue(cancellationPropagated, "caller cancellation must escape the load boundary")
                    assertTrue(oldLoad.isCancelled)
                    assertSame(replacementStart, pendingStart)
                    assertEquals(replacement, state.value, "a stale load must not publish a failure for the new track")
                } finally {
                    release.complete(Unit)
                    oldLoad.cancelAndJoin()
                }
            }
        }
    }

    private class PreparingPlayback {
        var state = State.PAUSED
            private set
        var positionMs = 0L
            private set
        private val commands = ArrayDeque<State>()

        fun apply(start: DesktopPlaybackStart) = start.apply(state,
            { positionMs = it }, { commands.addLast(State.PLAYING) }, { commands.addLast(State.PAUSED) })

        fun completeNextCommand() { state = commands.removeFirst() }
    }
}
