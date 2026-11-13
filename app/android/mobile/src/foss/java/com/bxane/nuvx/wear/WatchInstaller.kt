package com.bxane.nuvx.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object WatchInstaller {
    val watches: StateFlow<List<WatchEntry>?> = MutableStateFlow(emptyList())
    val update: StateFlow<WatchUpdateState> = MutableStateFlow(WatchUpdateState.Idle)
    val pairing: StateFlow<WatchPairingState> = MutableStateFlow(WatchPairingState.Idle)

    fun available(context: Context): Boolean = false
    fun refresh(context: Context) = Unit
    fun sendUpdate(context: Context, nodeId: String) = Unit
    fun startPairing(context: Context) = Unit
    fun stopPairing() = Unit
    fun pairAndInstall(context: Context, nodeId: String, code: String) = Unit
}
