package com.bxane.nuvx

import android.os.Bundle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPlayerTest {
    @Test fun initialPauseAndPositionAreSetBeforePreparation() {
        val commands = mutableListOf<String>()
        val controller = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "setPlayWhenReady" -> commands += "ready:${args!![0]}"
                "setMediaItems" -> commands += "queue:${args!![1]}:${args[2]}"
                "prepare", "play", "pause", "seekTo" -> commands += method.name
            }
            null
        } as Player
        controller.prepareQueue(emptyList(), 2, 12_000, false)
        assertEquals(listOf("ready:false", "queue:2:12000", "ready:false", "prepare"), commands)
    }

    @Test fun defaultPlayingIntentAndNegativePositionAreHandledBeforePreparation() {
        val commands = mutableListOf<String>()
        val controller = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "setPlayWhenReady" -> commands += "ready:${args!![0]}"
                "setMediaItems" -> commands += "queue:${args!![1]}:${args[2]}"
                "prepare", "play", "pause", "seekTo" -> commands += method.name
            }
            null
        } as Player
        controller.prepareQueue(emptyList(), 0, -40, true)
        assertEquals(listOf("ready:false", "queue:0:0", "ready:true", "prepare"), commands)
    }

    @Test fun playAfterQueueCompletionRewindsBeforePlaying() = runTest {
        assertEquals(listOf("rewind", "play"), toggleCommands(Player.STATE_ENDED, true))
    }

    @Test fun playAfterSessionStopPreparesOnFirstToggle() = runTest {
        assertEquals(listOf("prepare", "play"), toggleCommands(Player.STATE_IDLE, true))
    }

    @Test fun playAfterPausedRestorePreparesBeforePlaying() = runTest {
        assertEquals(listOf("prepare", "play"), toggleCommands(Player.STATE_IDLE, false))
    }

    @Test fun bufferingToggleUsesRequestedPlayRatherThanIsPlaying() = runTest {
        assertEquals(listOf("pause"), toggleCommands(Player.STATE_BUFFERING, true))
        assertEquals(listOf("play"), toggleCommands(Player.STATE_BUFFERING, false))
    }

    @Test fun playAfterErrorPreparesBeforePlaying() = runTest {
        // An explicit timestamp avoids calling Android's clock in this host test.
        val error = object : PlaybackException("Test error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, Bundle.EMPTY, 0L) {}
        for (requestedPlay in listOf(true, false)) {
            assertEquals(listOf("prepare", "play"), toggleCommands(Player.STATE_IDLE, requestedPlay, error))
        }
    }

    private fun TestScope.toggleCommands(playbackState: Int, playWhenReady: Boolean, playerError: PlaybackException? = null): List<String> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val commands = mutableListOf<String>()
        val controller = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> playbackState
                "getPlayWhenReady" -> playWhenReady
                "getPlayerError" -> playerError
                "isPlaying" -> false
                "seekToDefaultPosition" -> { commands += "rewind"; null }
                "prepare" -> { commands += "prepare"; null }
                "play" -> { commands += "play"; null }
                "pause" -> { commands += "pause"; null }
                else -> null
            }
        } as Player
        val player = AndroidPlayer(controller)
        try {
            player.togglePlay()
            return commands
        } finally { player.close(); Dispatchers.resetMain() }
    }
}
