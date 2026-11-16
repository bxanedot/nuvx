package com.licht.nuvx.wear

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import com.licht.nuvx.playback.cast.NoSpeaker
import com.licht.nuvx.playback.cast.RemoteSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.licht.nuvx.widget.MediaWidgetSnapshot

object WearBridge {
    @Suppress("UNUSED_PARAMETER")
    internal fun publish(context: Context, snapshot: MediaWidgetSnapshot, player: Player, playing: Boolean?) = Unit

    @Suppress("UNUSED_PARAMETER")
    internal fun republish(context: Context, player: Player) = Unit

    val watchSession: StateFlow<Boolean> = MutableStateFlow(false)

    @Suppress("UNUSED_PARAMETER")
    fun attachStreams(factory: DataSource.Factory?) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun createSpeaker(
        context: Context,
        scope: CoroutineScope,
        localPlayer: () -> ExoPlayer?,
        onChanged: () -> Unit,
    ): RemoteSpeaker = NoSpeaker

    fun releaseSpeaker() = Unit

    val watchOutput: StateFlow<WatchOutputState> = MutableStateFlow(WatchOutputState())

    @Suppress("UNUSED_PARAMETER")
    fun refreshWatchOutput(context: Context) = Unit

    fun selectWatchOutput() = Unit

    fun leaveWatchOutput() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun setWatchVolume(fraction: Float) = Unit
}
