package com.bxane.nuvx.desktop

import com.bxane.nuvx.core.BackendRouter
import com.bxane.nuvx.core.Track
import com.bxane.nuvx.core.defaultBackends
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopCrossfadeRecoveryTest {
    @Test
    fun stoppedIncomingDeckRestoresTheStillPlayingOutgoingTrack() = runBlocking<Unit> {
        assumeTrue("Set NUVX_DESKTOP_CROSSFADE_LIVE=1 to run the silent VLC test", System.getenv("NUVX_DESKTOP_CROSSFADE_LIVE") == "1")
        val outgoing = silentTrack("local:outgoing", "Outgoing")
        val incoming = silentTrack("local:incoming", "Incoming")
        val playerRef = arrayOfNulls<DesktopPlayer>(1)
        try {
            SwingUtilities.invokeAndWait {
                playerRef[0] = DesktopPlayer(BackendRouter(defaultBackends())).also { player ->
                    player.setCrossfadeSeconds(3)
                    player.playQueue(listOf(outgoing.first, incoming.first))
                    player.setVolume(0f)
                }
            }
            val player = requireNotNull(playerRef[0])
            withTimeout(8_000) { player.state.first { it.index == 1 && it.isPlaying } }
            SwingUtilities.invokeAndWait {
                val active = DesktopPlayer::class.java.getDeclaredField("native").apply { isAccessible = true }
                    .get(player) as EmbeddedMediaPlayer
                active.controls().stop()
            }
            val restored = withTimeout(2_000) { player.state.first { it.index == 0 && it.isPlaying } }
            assertEquals("local:outgoing", restored.current?.videoId)
            assertTrue(restored.positionMs > 0)
        } finally {
            playerRef[0]?.let { player -> SwingUtilities.invokeAndWait { player.close() } }
            Files.deleteIfExists(outgoing.second)
            Files.deleteIfExists(incoming.second)
        }
    }

    private fun silentTrack(id: String, title: String): Pair<Track, java.nio.file.Path> {
        val path = Files.createTempFile("nuvx-crossfade-", ".wav")
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val frames = format.sampleRate.toLong() * 4
        val silence = ByteArray(frames.toInt() * format.frameSize)
        AudioInputStream(ByteArrayInputStream(silence), format, frames).use { input ->
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, path.toFile())
        }
        return Track(id, title, localUri = path.toUri().toString()) to path
    }
}
