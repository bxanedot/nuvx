package com.bxane.nuvx.ui

import com.bxane.nuvx.core.AudioQualityPreference
import com.bxane.nuvx.core.AudioFormatInfo
import com.bxane.nuvx.core.BackendHealth
import com.bxane.nuvx.core.BackendId
import com.bxane.nuvx.core.MediaKind
import com.bxane.nuvx.core.PlayerState
import com.bxane.nuvx.core.RepeatMode
import com.bxane.nuvx.core.Track
import com.bxane.nuvx.core.VideoResolution
import com.bxane.nuvx.core.VideoQualityPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerDiagnosticsTest {
    @Test
    fun showsOnlyReportedAudioFormatDetails() {
        val reported = playbackDiagnostics(
            selected = BackendId.INNERTUBEX,
            available = emptySet(),
            fallback = false,
            audioQuality = AudioQualityPreference.HIGH,
            videoQuality = VideoQualityPreference.AUTO,
            health = emptyMap(),
            playerState = PlayerState(
                queue = listOf(Track("video-id", "Music video", mediaKind = MediaKind.VIDEO)),
                index = 0,
                audioFormat = AudioFormatInfo("audio/webm", "opus", 160, 48_000),
                videoResolution = VideoResolution(1920, 1080),
            ),
        ).associate { it.label to it.value }

        assertEquals("opus", reported["Audio codec"])
        assertEquals("audio/webm", reported["Audio format"])
        assertEquals("160 kbps", reported["Audio bitrate"])
        assertEquals("48000 Hz", reported["Sample rate"])
        assertEquals("1920 × 1080", reported["Current video resolution"])

        val unavailable = playbackDiagnostics(
            selected = BackendId.INNERTUBEX,
            available = emptySet(),
            fallback = false,
            audioQuality = AudioQualityPreference.HIGH,
            videoQuality = VideoQualityPreference.AUTO,
            health = emptyMap(),
            playerState = PlayerState(queue = listOf(Track("local:1", "Local")), index = 0),
        ).associate { it.label to it.value }
        assertEquals("Not reported", unavailable["Audio codec"])
        assertEquals("Not reported", unavailable["Audio format"])
        assertEquals("Not reported", unavailable["Current video resolution"])

        val invalidFormat = playbackDiagnostics(
            selected = BackendId.INNERTUBEX,
            available = emptySet(),
            fallback = false,
            audioQuality = AudioQualityPreference.HIGH,
            videoQuality = VideoQualityPreference.AUTO,
            health = emptyMap(),
            playerState = PlayerState(
                queue = listOf(Track("video-id", "Song title")),
                index = 0,
                audioFormat = AudioFormatInfo("https://media.example/private?token=secret", "https://media.example", 160, 48_000),
            ),
        ).associate { it.label to it.value }
        assertEquals("Not reported", invalidFormat["Audio codec"])
        assertEquals("Not reported", invalidFormat["Audio format"])
        assertFalse(invalidFormat.values.any { "media.example" in it || "secret" in it })
    }

    @Test
    fun showsUsefulStateWithoutRawErrorsOrSignedAddresses() {
        val diagnostics = playbackDiagnostics(
            selected = BackendId.INNERTUBEX,
            available = setOf(BackendId.INNERTUBEX, BackendId.EXCOR),
            fallback = true,
            audioQuality = AudioQualityPreference.HIGH,
            videoQuality = VideoQualityPreference.DATA_SAVER,
            health = mapOf(BackendId.INNERTUBEX to BackendHealth(
                ok = false,
                operation = "stream",
                detail = "https://media.example/stream?po_token=private-value",
                latencyMs = 37,
            )),
            playerState = PlayerState(
                queue = listOf(Track("video-id", "Song title", listOf("Artist name"))),
                index = 0,
                isPlaying = false,
                positionMs = 65_000,
                durationMs = 180_000,
                error = "https://media.example/stream?po_token=private-value",
                repeat = RepeatMode.ONE,
                volume = 0.5f,
                playbackSpeed = 1.25f,
                nativePlaybackState = "ENDED",
            ),
        ).associate { it.label to it.value }

        assertEquals("Playback issue", diagnostics["Playback"])
        assertEquals("Song title · Artist name", diagnostics["Current track"])
        assertEquals("1:05 / 3:00", diagnostics["Position"])
        assertEquals("Unavailable · Stream · 37 ms", diagnostics["InnerTubeX"])
        assertEquals("Not used yet this session", diagnostics["eXcor"])
        assertEquals("ENDED", diagnostics["Native player state"])
        assertFalse(diagnostics.values.any { "private-value" in it || "media.example" in it })
    }
}
