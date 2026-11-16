package com.bxane.nuvx.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bxane.nuvx.R

class PlaybackTile : TileService() {

    override fun onStartListening() = render(MediaWidgetSnapshot.load(this))

    override fun onClick() {
        val snapshot = MediaWidgetSnapshot.load(this)
        if (!snapshot.hasTrack) return
        sendBroadcast(
            Intent(this, MediaWidgetActions::class.java).setAction(MediaWidgetActions.ACTION_TOGGLE),
        )
        render(snapshot.copy(isPlaying = !snapshot.isPlaying))
    }

    private fun render(snapshot: MediaWidgetSnapshot) {
        val tile = qsTile ?: return
        tile.state = when {
            !snapshot.hasTrack -> Tile.STATE_UNAVAILABLE
            snapshot.isPlaying -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.icon = Icon.createWithResource(
            this,
            if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        tile.contentDescription = getString(if (snapshot.isPlaying) R.string.pause else R.string.play)
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = snapshot.title.ifBlank { null }
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) = runCatching {
            requestListeningState(context, ComponentName(context, PlaybackTile::class.java))
        }
    }
}
