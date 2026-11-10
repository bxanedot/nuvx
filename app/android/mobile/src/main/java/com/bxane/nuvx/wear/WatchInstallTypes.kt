package com.bxane.nuvx.wear

data class WatchEntry(
    val nodeId: String,
    val name: String,
    val status: WatchAppStatus,
    val versionName: String? = null,
)

enum class WatchAppStatus {
    CHECKING,

    NOT_INSTALLED,

    UP_TO_DATE,

    UPDATE_AVAILABLE,

    UNKNOWN,
}

sealed interface WatchUpdateState {
    data object Idle : WatchUpdateState
    data class Sending(val nodeId: String) : WatchUpdateState

    data class Sent(val nodeId: String) : WatchUpdateState
    data class Failed(val nodeId: String, val reason: String) : WatchUpdateState
}

sealed interface WatchPairingState {
    data object Idle : WatchPairingState

    data object Searching : WatchPairingState

    data class Found(val host: String) : WatchPairingState
    data object Pairing : WatchPairingState

    data object Installing : WatchPairingState
    data object Installed : WatchPairingState
    data class Failed(val reason: String) : WatchPairingState
}
