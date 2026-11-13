package com.bxane.nuvx.wear

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.playback.cast.NoSpeaker
import com.bxane.nuvx.playback.cast.RemoteSpeaker
import com.bxane.nuvx.widget.MediaWidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object WearBridge {
    internal fun publish(context: Context, snapshot: MediaWidgetSnapshot, player: Player, playing: Boolean?) = Unit
    internal fun republish(context: Context, player: Player) = Unit

    val watchSession: StateFlow<Boolean> = MutableStateFlow(false)
    val watchOutput: StateFlow<WatchOutputState> = MutableStateFlow(WatchOutputState())

    fun attachStreams(factory: DataSource.Factory?) = Unit
    fun refreshWatchOutput(context: Context) = Unit
    fun selectWatchOutput() = Unit
    fun leaveWatchOutput() = Unit
    fun setWatchVolume(fraction: Float) = Unit
    fun createSpeaker(
        context: Context,
        scope: CoroutineScope,
        localPlayer: () -> ExoPlayer?,
        onChanged: () -> Unit,
    ): RemoteSpeaker = NoSpeaker
    fun releaseSpeaker() = Unit
}
