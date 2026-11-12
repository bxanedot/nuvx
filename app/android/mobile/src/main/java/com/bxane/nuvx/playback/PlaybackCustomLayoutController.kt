package com.bxane.nuvx.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.Song

internal class PlaybackCustomLayoutController(
    private val context: Context,
    private val player: () -> ExoPlayer?,
    private val mediaSession: () -> MediaLibrarySession?,
    private val isLiked: (MediaItem?) -> Boolean,
    private val canLike: (MediaItem?) -> Boolean,
    private val canStartStation: (Song) -> Boolean,
    private val favoriteCommand: SessionCommand,
    private val shuffleCommand: SessionCommand,
    private val startStationCommand: SessionCommand,
    private val revertToOriginalCommand: SessionCommand,
) {
    private val carControllers = mutableSetOf<MediaSession.ControllerInfo>()

    fun notificationButtons(): List<CommandButton> {
        val liked = isLiked(player()?.currentMediaItem)
        val favorite = if (!canLike(player()?.currentMediaItem)) null else CommandButton.Builder(
            if (liked) CommandButton.ICON_STAR_FILLED else CommandButton.ICON_STAR_UNFILLED,
        )
            .setCustomIconResId(if (liked) R.drawable.ic_notification_star_filled else R.drawable.ic_notification_star)
            .setSessionCommand(favoriteCommand)
            .setDisplayName("Favorite")
            .build()
        val shuffleEnabled = QueueShuffle.enabled.value
        val shuffle = CommandButton.Builder(
            if (shuffleEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            },
        )
            .setSessionCommand(shuffleCommand)
            .setDisplayName(if (shuffleEnabled) "Shuffle off" else "Shuffle on")
            .build()
        return listOfNotNull(favorite, shuffle)
    }

    fun carButtons(): List<CommandButton> {
        val current = player()?.currentMediaItem?.toSong()
        val station = current
            ?.takeIf { canStartStation(it) }
            ?.let {
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setSessionCommand(startStationCommand)
                    .setDisplayName(context.getString(R.string.start_radio))
                    .build()
            }
        val revert = current
            ?.takeIf {
                it.hasYouTubeOriginal() &&
                    it.localUri == null &&
                    !OriginalVersion.isPinned(it.videoId)
            }
            ?.let {
                CommandButton.Builder(CommandButton.ICON_SYNC)
                    .setSessionCommand(revertToOriginalCommand)
                    .setDisplayName(context.getString(R.string.revert_to_original))
                    .build()
            }
        return notificationButtons() + listOfNotNull(station, revert)
    }

    fun addCarController(controller: MediaSession.ControllerInfo) {
        carControllers += controller
    }

    fun removeCarController(controller: MediaSession.ControllerInfo): Boolean =
        carControllers.remove(controller)

    fun refreshCustomLayouts() {
        val session = mediaSession() ?: return
        val car = carButtons()
        session.setCustomLayout(car)
        session.getMediaNotificationControllerInfo()?.let {
            session.setCustomLayout(it, if (carControllers.isEmpty()) notificationButtons() else car)
        }
    }
}
