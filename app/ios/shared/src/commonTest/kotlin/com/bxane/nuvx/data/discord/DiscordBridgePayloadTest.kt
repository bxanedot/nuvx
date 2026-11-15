package com.bxane.nuvx.data.discord

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordBridgePayloadTest {
    @Test
    fun firstDiscordButtonOpensNuvxAndUsesNuvxCopy() {
        val payload = DiscordBridge.presencePayload(
            title = "Song",
            artist = "Artist",
            album = null,
            positionMs = 0,
            durationMs = 180_000,
            speed = 1f,
        )

        assertTrue(payload.contains("\"details_url\":\"https://nuvx.lol/open\""))
        assertTrue(payload.contains("\"label\":\"Listen on nuvx\",\"url\":\"https://nuvx.lol/open\""))
        assertFalse(payload.contains("music.youtube.com"))
    }
}
