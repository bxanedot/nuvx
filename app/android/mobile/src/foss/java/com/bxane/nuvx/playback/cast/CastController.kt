package com.bxane.nuvx.playback.cast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CastController {
    data class Device(
        val id: String,
        val name: String,
        val connecting: Boolean,
        val connected: Boolean,
    )

    data class State(
        val supported: Boolean = false,
        val devices: List<Device> = emptyList(),
        val connectedName: String? = null,
        val connecting: Boolean = false,
    ) {
        val casting: Boolean get() = connectedName != null
    }

    val state: StateFlow<State> = MutableStateFlow(State())
    val volume: StateFlow<Float> = MutableStateFlow(1f)

    fun ensureStarted(context: Context) = Unit
    fun attach(target: CastPlayback) = Unit
    fun detach(target: CastPlayback) = Unit
    fun discover(active: Boolean): () -> Unit = {}
    fun connect(deviceId: String) = Unit
    fun disconnect(resumeHere: Boolean) = Unit
    fun setVolume(level: Float) = Unit
}
