package com.licht.nuvx.playback.cast

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.sync.LanHost
import com.licht.nuvx.playback.toMediaItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * This phone as a speaker for another paired phone (Nuvx Connect): what it plays, and the other phone's commands.
 * The host answers on its own threads; the player lives on the main thread, so state is a snapshot refreshed there
 * and commands are posted there. Same commands and limits as the desktop app (`lan_sync/connect.rs`).
 */
internal class HostSpeaker(private val player: () -> ExoPlayer?) : LanHost.Speaker {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var snapshot: JsonObject = idle()

    private val refresh = object : Runnable {
        override fun run() {
            snapshot = player()?.let(::describe) ?: idle()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    fun start() {
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    fun stop() = main.removeCallbacks(refresh)

    override fun state(): JsonObject = snapshot

    override fun apply(command: JsonObject): Boolean {
        val action = (command["action"] as? JsonPrimitive)?.contentOrNull ?: return false
        val position = (command["positionMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..MAX_POSITION_MS }
        val run: (ExoPlayer) -> Unit = when (action) {
            "toggle" -> { p -> if (p.isPlaying) p.pause() else p.play() }
            "play" -> { p -> p.play() }
            "pause" -> { p -> p.pause() }
            "next" -> { p -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() }
            "previous" -> { p -> p.seekToPrevious() }
            "seek" -> {
                val at = position ?: return false
                ({ p -> p.seekTo(at) })
            }
            "load", "playTrack" -> {
                val songs = tracks(command, action) ?: return false
                val paused = (command["paused"] as? JsonPrimitive)?.booleanOrNull == true
                ({ p ->
                    p.setMediaItems(songs.map(Song::toMediaItem), 0, position ?: 0L)
                    p.prepare()
                    p.playWhenReady = !paused
                })
            }
            else -> return false
        }
        main.post { player()?.let(run) }
        return true
    }

    private fun tracks(command: JsonObject, action: String): List<Song>? {
        val list = if (action == "playTrack") listOf(command) else (command["tracks"] as? JsonArray)?.map { it.jsonObject } ?: return null
        if (list.isEmpty() || list.size > MAX_TRACKS) return null
        return list.map { o ->
            fun text(k: String): String? = when (val v = o[k]) {
                null, is JsonNull -> ""
                is JsonPrimitive -> v.contentOrNull?.trim()?.takeIf { it.length <= MAX_TEXT }
                else -> null
            }
            val id = (o["videoId"] as? JsonPrimitive)?.contentOrNull?.takeIf(VIDEO_ID::matches) ?: return null
            val art = text("art")?.takeIf(::artOk) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            Song(videoId = id, title = text("title")?.ifEmpty { id } ?: return null, artist = text("artist") ?: return null, thumbnailUrl = art)
        }
    }

    private fun describe(p: ExoPlayer): JsonObject {
        val item = p.currentMediaItem ?: return idle()
        return buildJsonObject {
            put("track", buildJsonObject {
                put("id", item.mediaId)
                put("title", item.mediaMetadata.title?.toString().orEmpty())
                put("artist", item.mediaMetadata.artist?.toString().orEmpty())
                p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { put("durationMs", it) }
            })
            put("playing", p.isPlaying)
            put("positionMs", p.currentPosition.coerceAtLeast(0))
            put("ended", p.playbackState == Player.STATE_ENDED)
        }
    }

    private fun idle() = buildJsonObject { put("track", JsonNull); put("playing", false) }

    private companion object {
        const val REFRESH_MS = 500L
        const val MAX_TRACKS = 20
        const val MAX_TEXT = 300
        const val MAX_POSITION_MS = 24L * 60 * 60 * 1000
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** Only YouTube's own image hosts over https, like the desktop: never a local or arbitrary address. */
        fun artOk(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val host = uri.host.orEmpty()
            return uri.scheme == "https" &&
                listOf("ytimg.com", "googleusercontent.com", "ggpht.com").any { host == it || host.endsWith(".$it") }
        }
    }
}
