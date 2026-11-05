package com.bxane.nuvx.desktop

import com.bxane.nuvx.core.AudioQualityPreference
import com.bxane.nuvx.core.AudioStream
import com.bxane.nuvx.core.BackendId
import com.bxane.nuvx.core.BackendRouter
import com.bxane.nuvx.core.DesktopAudioOutputSelection
import com.bxane.nuvx.core.MusicBackend
import com.bxane.nuvx.core.PlayerState
import com.bxane.nuvx.core.Track
import com.bxane.nuvx.core.defaultBackends
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopQueueSyncTest {
    @Test fun earlyNativeEndSurfacesPlaybackFailure() {
        val player = PlayerState(
            queue = listOf(Track("aaaaaaaaaaa", "Early end")),
            index = 0,
            positionMs = 10_000,
            durationMs = 156_000,
        )

        assertEquals("Playback failed. Retry or choose another engine in Settings.", player.playbackErrorFor(State.ENDED))
    }

    @Test fun nativeEndAtReportedDurationIsNotFailure() {
        val player = PlayerState(
            queue = listOf(Track("aaaaaaaaaaa", "Finished")),
            index = 0,
            positionMs = 155_500,
            durationMs = 156_000,
        )

        assertEquals(null, player.playbackErrorFor(State.ENDED))
    }

    @Test fun earlyNativeStopWithKnownRemainingDurationSurfacesFailure() {
        val player = PlayerState(
            queue = listOf(Track("aaaaaaaaaaa", "Early stop")),
            index = 0,
            isPlaying = true,
            positionMs = 10_000,
            durationMs = 156_000,
        )

        assertEquals("Playback failed. Retry or choose another engine in Settings.", player.playbackErrorFor(State.STOPPED))
        assertEquals(null, player.copy(positionMs = 155_000).playbackErrorFor(State.STOPPED))
    }

    @Test fun outputSwitchRetainsLatestSeekAfterResolverCancellation() = runBlocking<Unit> {
        assumeTrue("Set NUVX_DESKTOP_QUEUE_LIVE=1 for the native output-switch test", System.getenv("NUVX_DESKTOP_QUEUE_LIVE") == "1")
        val initialResolution = CompletableDeferred<Unit>()
        val cancellationWaiting = CompletableDeferred<Unit>()
        val finishCancellation = CompletableDeferred<Unit>()
        val restartedResolution = CompletableDeferred<Unit>()
        val backend = object : MusicBackend by defaultBackends().getValue(BackendId.INNERTUBEX) {
            override suspend fun stream(videoId: String, quality: AudioQualityPreference): AudioStream? {
                if (initialResolution.complete(Unit)) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cancellationWaiting.complete(Unit)
                            finishCancellation.await()
                        }
                    }
                }
                restartedResolution.complete(Unit)
                awaitCancellation()
            }
        }
        var player: DesktopPlayer? = null
        var selection: DesktopAudioOutputSelection? = null
        try {
            SwingUtilities.invokeAndWait {
                player = DesktopPlayer(BackendRouter(mapOf(BackendId.INNERTUBEX to backend), fallback = false)).also {
                    selection = it.availableAudioOutputs().firstOrNull()?.selection
                }
            }
            assumeTrue("A selectable native audio output is required", selection != null)
            val ready = requireNotNull(player)
            val output = requireNotNull(selection)
            SwingUtilities.invokeAndWait {
                ready.playQueue(listOf(Track("aaaaaaaaaaa", "Pending")), initialPositionMs = 12_000)
                ready.setVolume(0f)
            }
            withTimeout(8_000) { initialResolution.await() }
            SwingUtilities.invokeAndWait { ready.setPreferredAudioOutput(output) }
            withTimeout(8_000) { cancellationWaiting.await() }
            SwingUtilities.invokeAndWait {
                ready.seekTo(18_000)
                ready.togglePlay()
            }
            finishCancellation.complete(Unit)
            withTimeout(8_000) { restartedResolution.await() }
            SwingUtilities.invokeAndWait {
                assertEquals(18_000, ready.state.value.positionMs, "output replacement must retain the seek made during cancellation")
            }
        } finally {
            finishCancellation.complete(Unit)
            player?.let { SwingUtilities.invokeAndWait { it.close() } }
        }
    }

    @Test fun supersededOutputSwitchDoesNotCancelNewerTrackPreparation() = runBlocking<Unit> {
        assumeTrue("Set NUVX_DESKTOP_QUEUE_LIVE=1 for the native output-switch test", System.getenv("NUVX_DESKTOP_QUEUE_LIVE") == "1")
        val initialResolution = CompletableDeferred<Unit>()
        val oldCancellationWaiting = CompletableDeferred<Unit>()
        val finishOldCancellation = CompletableDeferred<Unit>()
        val newerTrackResolution = CompletableDeferred<Unit>()
        val latestRestartResolution = CompletableDeferred<Unit>()
        val latestRestartCancelled = CompletableDeferred<Unit>()
        val resolutions = AtomicInteger()
        val backend = object : MusicBackend by defaultBackends().getValue(BackendId.INNERTUBEX) {
            override suspend fun stream(videoId: String, quality: AudioQualityPreference): AudioStream? {
                when (resolutions.incrementAndGet()) {
                    1 -> try {
                        initialResolution.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            oldCancellationWaiting.complete(Unit)
                            finishOldCancellation.await()
                        }
                    }
                    2 -> { newerTrackResolution.complete(Unit); awaitCancellation() }
                    else -> try {
                        latestRestartResolution.complete(Unit)
                        awaitCancellation()
                    } finally {
                        latestRestartCancelled.complete(Unit)
                    }
                }
            }
        }
        var player: DesktopPlayer? = null
        var selection: DesktopAudioOutputSelection? = null
        try {
            SwingUtilities.invokeAndWait {
                player = DesktopPlayer(BackendRouter(mapOf(BackendId.INNERTUBEX to backend), fallback = false)).also {
                    selection = it.availableAudioOutputs().firstOrNull()?.selection
                }
            }
            assumeTrue("A selectable native audio output is required", selection != null)
            val ready = requireNotNull(player)
            SwingUtilities.invokeAndWait {
                ready.playQueue(listOf(Track("aaaaaaaaaaa", "Old")))
                ready.setVolume(0f)
            }
            withTimeout(8_000) { initialResolution.await() }
            SwingUtilities.invokeAndWait { ready.setPreferredAudioOutput(requireNotNull(selection)) }
            withTimeout(8_000) { oldCancellationWaiting.await() }
            SwingUtilities.invokeAndWait {
                ready.playQueue(listOf(Track("bbbbbbbbbbb", "New")), initialPositionMs = 18_000, autoplay = false)
                ready.setVolume(0f)
            }
            withTimeout(8_000) { newerTrackResolution.await() }
            SwingUtilities.invokeAndWait { ready.setPreferredAudioOutput(DesktopAudioOutputSelection.DEFAULT) }
            withTimeout(8_000) { latestRestartResolution.await() }
            finishOldCancellation.complete(Unit)
            delay(1_200)
            SwingUtilities.invokeAndWait {
                assertFalse(latestRestartCancelled.isCompleted, "a superseded output change must not cancel the newer track's preparation")
                assertEquals(3, resolutions.get(), "a stale output change must not restart resolution again")
                assertEquals("bbbbbbbbbbb", ready.state.value.current?.videoId)
                assertEquals(18_000, ready.state.value.positionMs)
            }
        } finally {
            finishOldCancellation.complete(Unit)
            player?.let { SwingUtilities.invokeAndWait { it.close() } }
        }
    }

    @Test fun initialPausedQueueRetainsPositionUntilExplicitResume() = runBlocking<Unit> {
        assumeTrue("Set NUVX_DESKTOP_QUEUE_LIVE=1 for the silent VLC test", System.getenv("NUVX_DESKTOP_QUEUE_LIVE") == "1")
        val path = Files.createTempFile("nuvx-paused-queue-", ".wav")
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val frames = 44_100L * 10
        AudioInputStream(ByteArrayInputStream(ByteArray(frames.toInt() * format.frameSize)), format, frames).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, path.toFile())
        }
        var player: DesktopPlayer? = null
        try {
            SwingUtilities.invokeAndWait {
                player = DesktopPlayer(BackendRouter(defaultBackends())).also {
                    it.playQueue(listOf(Track("local:silent", "Paused", localUri = path.toUri().toString())),
                        initialPositionMs = 3_000, autoplay = false)
                    it.setVolume(0f)
                }
            }
            val ready = requireNotNull(player)
            val paused = withTimeout(8_000) { ready.state.first { !it.buffering && it.durationMs > 0 } }
            assertFalse(paused.isPlaying)
            assertTrue(paused.positionMs in 2_800..3_200, "the initial seek must survive native preparation")
            delay(600)
            assertFalse(ready.state.value.isPlaying)
            assertTrue(ready.state.value.positionMs in 2_800..3_200, "joining a paused room must remain paused")
            SwingUtilities.invokeAndWait { ready.togglePlay() }
            val playing = withTimeout(3_000) { ready.state.first { it.isPlaying && it.positionMs > 3_200 } }
            assertTrue(playing.positionMs < 5_000, "resume must continue from the initial position")
        } finally {
            player?.let { SwingUtilities.invokeAndWait { it.close() } }
            Files.deleteIfExists(path)
        }
    }

    @Test fun unexpectedNativeStopAfterStartedPlaybackShowsFailure() = runBlocking<Unit> {
        assumeTrue("Set NUVX_DESKTOP_QUEUE_LIVE=1 for the silent VLC test", System.getenv("NUVX_DESKTOP_QUEUE_LIVE") == "1")
        val path = Files.createTempFile("nuvx-stopped-playback-", ".wav")
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val frames = 44_100L * 10
        AudioInputStream(ByteArrayInputStream(ByteArray(frames.toInt() * format.frameSize)), format, frames).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, path.toFile())
        }
        var player: DesktopPlayer? = null
        try {
            SwingUtilities.invokeAndWait {
                player = DesktopPlayer(BackendRouter(defaultBackends())).also {
                    it.setVolume(0f)
                    it.playQueue(listOf(Track("local:silent", "Stopped", localUri = path.toUri().toString())))
                }
            }
            val ready = requireNotNull(player)
            withTimeout(8_000) { ready.state.first { it.isPlaying && it.positionMs >= 400 } }
            val nativeField = DesktopPlayer::class.java.getDeclaredField("native").apply { isAccessible = true }
            SwingUtilities.invokeAndWait {
                (nativeField.get(ready) as EmbeddedMediaPlayer).controls().stop()
            }

            val stopped = withTimeout(2_000) { ready.state.first { it.error != null } }
            assertFalse(stopped.isPlaying)
            assertEquals("Playback failed. Retry or choose another engine in Settings.", stopped.error)
        } finally {
            player?.let { SwingUtilities.invokeAndWait { it.close() } }
            Files.deleteIfExists(path)
        }
    }

    @Test fun updatingUpcomingQueueDoesNotRestartOrResumePausedPlayback() = runBlocking<Unit> {
        assumeTrue("Set NUVX_DESKTOP_QUEUE_LIVE=1 for the silent VLC test", System.getenv("NUVX_DESKTOP_QUEUE_LIVE") == "1")
        val path = Files.createTempFile("nuvx-queue-sync-", ".wav")
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val frames = 44_100L * 4
        AudioInputStream(ByteArrayInputStream(ByteArray(frames.toInt() * format.frameSize)), format, frames).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, path.toFile())
        }
        var player: DesktopPlayer? = null
        try {
            SwingUtilities.invokeAndWait {
                player = DesktopPlayer(BackendRouter(defaultBackends())).also {
                    it.playQueue(listOf(Track("local:silent", "Before", localUri = path.toUri().toString())))
                    it.setVolume(0f)
                }
            }
            val ready = requireNotNull(player)
            withTimeout(8_000) { ready.state.first { it.isPlaying && it.positionMs >= 400 } }
            SwingUtilities.invokeAndWait { ready.togglePlay() }
            val paused = withTimeout(2_000) { ready.state.first { !it.isPlaying && !it.buffering } }
            SwingUtilities.invokeAndWait {
                ready.syncQueue(paused.current!!.copy(title = "Updated"), listOf(Track("aaaaaaaaaaa", "Upcoming")))
            }
            delay(400)
            val updated = ready.state.value
            assertFalse(updated.isPlaying)
            assertFalse(updated.buffering)
            assertEquals("Updated", updated.current?.title)
            assertEquals(listOf("local:silent", "aaaaaaaaaaa"), updated.queue.map(Track::videoId))
            assertTrue(updated.positionMs >= paused.positionMs, "the native clip must not restart at zero")
            assertTrue(updated.positionMs < paused.positionMs + 200, "the native clip must stay paused")
        } finally {
            player?.let { SwingUtilities.invokeAndWait { it.close() } }
            Files.deleteIfExists(path)
        }
    }
}
