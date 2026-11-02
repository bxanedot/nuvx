package com.bxane.nuvx

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPlayerTest {
    @Test fun playAfterQueueCompletionRewindsBeforePlaying() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val commands = mutableListOf<String>()
        val controller = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> Player.STATE_ENDED
                "getPlayWhenReady" -> true
                "getPlayerError" -> null
                "seekToDefaultPosition" -> { commands += "rewind"; null }
                "play" -> { commands += "play"; null }
                "pause" -> { commands += "pause"; null }
                else -> null
            }
        } as Player
        val player = AndroidPlayer(controller)
        try {
            player.togglePlay()
            assertEquals(listOf("rewind", "play"), commands)
        } finally { player.close(); Dispatchers.resetMain() }
    }
}
