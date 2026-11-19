package com.licht.nuvx.playback.cast

import androidx.media3.common.C
import androidx.media3.common.Player
import com.licht.nuvx.data.sync.NuvxConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Nuvx Connect the other way: while this phone plays, it tells the paired computer what, and carries out the
 * play/pause/skip the computer sends back in the reply. Runs on the main thread; the call itself on IO.
 */
internal class PhoneReporter(private val scope: CoroutineScope, private val player: () -> Player?, private val elsewhere: () -> Boolean) {
    fun start() = scope.launch {
        var reported = false
        while (isActive) {
            val p = player()
            val item = p?.currentMediaItem
            if (!NuvxConnect.available || elsewhere() || p == null || (item == null && !reported)) {
                delay(IDLE_MS)
                continue
            }
            val state = if (item == null) idle() else buildJsonObject {
                put("track", buildJsonObject {
                    put("title", item.mediaMetadata.title?.toString().orEmpty())
                    put("artist", item.mediaMetadata.artist?.toString().orEmpty())
                    p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { put("durationMs", it) }
                })
                put("playing", p.isPlaying)
                put("positionMs", p.currentPosition.coerceAtLeast(0))
            }
            val commands = withContext(Dispatchers.IO) { NuvxConnect.report(state) }.getOrNull()
            reported = item != null
            commands?.forEach { apply(p, it) }
            delay(if (commands == null) IDLE_MS else if (p.isPlaying) PLAYING_MS else PAUSED_MS)
        }
    }

    private fun apply(p: Player, action: String) = when (action) {
        "toggle" -> if (p.isPlaying) p.pause() else p.play()
        "play" -> p.play()
        "pause" -> p.pause()
        "next" -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() else Unit
        "previous" -> p.seekToPrevious()
        else -> Unit
    }

    private fun idle(): JsonObject = buildJsonObject { put("track", JsonNull); put("playing", false) }

    private companion object {
        const val PLAYING_MS = 1_500L
        const val PAUSED_MS = 4_000L
        // Not paired, playing elsewhere, or the computer did not answer.
        const val IDLE_MS = 10_000L
    }
}
