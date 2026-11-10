package com.bxane.nuvx.data.discord

import android.content.Context
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import java.util.Locale

class DiscordRPC(
    val context: Context,
    token: String,
) : KizzyRPC(
    token = token,
    os = "Android",
    browser = "Discord Android",
    device = android.os.Build.DEVICE,
    userAgent = SuperProperties.userAgent,
    superPropertiesBase64 = SuperProperties.superPropertiesBase64,
) {
    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1.0f,
        useDetails: Boolean = false,
        status: String = "online",
        button1Text: String = "",
        button1Visible: Boolean = true,
        button2Text: String = "",
        button2Visible: Boolean = true,
        activityType: String = "listening",
        activityName: String = "",
        audioQuality: String? = null,
    ) = runCatching {
        val currentTime = System.currentTimeMillis()

        val adjustedPlaybackTime = (currentPlaybackTimeMillis / playbackSpeed).toLong()
        val calculatedStartTime = currentTime - adjustedPlaybackTime

        val songTitleWithRate = if (playbackSpeed != 1.0f) {
            "${song.title} [${String.format(Locale.ROOT, "%.2fx", playbackSpeed)}]"
        } else {
            song.title
        }

        val remainingDuration = durationMillis - currentPlaybackTimeMillis
        val adjustedRemainingDuration = (remainingDuration / playbackSpeed).toLong()

        val buttonsList = mutableListOf<Pair<String, String>>()
        if (button1Visible) {
            val resolvedText = resolveVariables(
                button1Text.ifEmpty { DEFAULT_BUTTON_1 },
                song,
            )
            buttonsList.add(resolvedText to watchUrl(song))
        }
        if (button2Visible) {
            val resolvedText = resolveVariables(
                button2Text.ifEmpty { DEFAULT_BUTTON_2 },
                song,
            )
            buttonsList.add(resolvedText to PROJECT_URL)
        }

        val type = when (activityType) {
            "playing" -> Type.PLAYING
            "watching" -> Type.WATCHING
            "competing" -> Type.COMPETING
            else -> Type.LISTENING
        }

        val name = activityName.ifEmpty { appName() }

        setActivity(
            name = name,
            details = songTitleWithRate,
            state = song.artist,
            detailsUrl = watchUrl(song),
            largeImage = song.artworkAt(ART_PX)
                ?.takeIf { it.startsWith("http") }
                ?.let(RpcImage::ExternalImage),
            smallImage = null,
            largeText = audioQuality,
            smallText = null,
            buttons = if (buttonsList.isNotEmpty()) buttonsList else null,
            type = type,
            statusDisplayType = if (useDetails) StatusDisplayType.DETAILS else StatusDisplayType.STATE,
            since = currentTime,
            startTime = calculatedStartTime,
            endTime = currentTime + adjustedRemainingDuration,
            applicationId = DiscordApplication.ID,
            status = status,
        )
    }

    private fun appName(): String =
        context.getString(R.string.app_name).removeSuffix(" Dev")

    companion object {
        const val PROJECT_URL = "https://github.com/bxanedot/nuvx"

        const val DEFAULT_BUTTON_1 = "Listen on YouTube Music"
        const val DEFAULT_BUTTON_2 = "Visit nuvx"

        private const val ART_PX = 480

        fun watchUrl(song: Song): String =
            "https://music.youtube.com/watch?v=${song.videoId}"

        fun resolveVariables(text: String, song: Song): String {
            return text
                .replace("{song_name}", song.title)
                .replace("{artist_name}", song.artist)
                .replace("{album_name}", song.albumName ?: "")
        }
    }
}
