package com.bxane.nuvx.desktop

import com.bxane.nuvx.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import javax.swing.SwingUtilities
import kotlin.test.*

class DesktopPlaybackSmokeTest {
    @Test fun playsPausesAndSeeksRealAudio() = runBlocking<Unit> {
        assumeTrue("Set NUVX_DESKTOP_LIVE=1 and install VLC 3", System.getenv("NUVX_DESKTOP_LIVE") == "1")
        val router = BackendRouter(defaultBackends())
        val tracks = withTimeout(45_000) { router.search("daft punk around the world").tracks }
        assertTrue(tracks.isNotEmpty())
        lateinit var player: DesktopPlayer
        SwingUtilities.invokeAndWait { player = DesktopPlayer(router); player.playQueue(tracks) }
        try {
            val playing = withTimeout(90_000) { player.state.first { it.isPlaying && it.positionMs > 500 } }
            assertTrue(playing.durationMs > 1000)
            SwingUtilities.invokeAndWait { player.togglePlay() }
            withTimeout(5000) { player.state.first { !it.isPlaying } }
            SwingUtilities.invokeAndWait { player.seekTo(10_000); player.togglePlay() }
            withTimeout(15_000) { player.state.first { it.isPlaying && it.positionMs >= 10_000 } }
        } finally { SwingUtilities.invokeAndWait { player.close() } }
    }
}
