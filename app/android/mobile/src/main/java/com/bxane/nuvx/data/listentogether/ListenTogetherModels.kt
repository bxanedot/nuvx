package com.bxane.nuvx.data.listentogether

sealed interface ServerUrlError {
    data object Whitespace : ServerUrlError
    data object InvalidScheme : ServerUrlError
    data object InvalidHost : ServerUrlError
    data object InvalidPort : ServerUrlError
    data object InvalidPath : ServerUrlError
    data object HasPath : ServerUrlError
    data object HasQuery : ServerUrlError
    data object HasFragment : ServerUrlError
    data object Malformed : ServerUrlError
}

sealed interface ServerUrlValidationResult {
    data class Valid(val normalizedUrl: String) : ServerUrlValidationResult
    data class Invalid(val error: ServerUrlError) : ServerUrlValidationResult
}

sealed interface ServerConnectionState {
    data object Checking : ServerConnectionState
    data class DefaultOnline(val latencyMs: Long) : ServerConnectionState
    data class CustomOnline(val latencyMs: Long) : ServerConnectionState
    data class CustomFallback(val latencyMs: Long) : ServerConnectionState
    data object Offline : ServerConnectionState
}

data class ProbeResult(val isOnline: Boolean, val latencyMs: Long = 0L)

val ServerConnectionState.latencyMs: Long?
    get() = when (this) {
        is ServerConnectionState.DefaultOnline -> latencyMs
        is ServerConnectionState.CustomOnline -> latencyMs
        is ServerConnectionState.CustomFallback -> latencyMs
        ServerConnectionState.Checking, ServerConnectionState.Offline -> null
    }

val ServerConnectionState.isFallback: Boolean
    get() = this is ServerConnectionState.CustomFallback

val ServerConnectionState.health: ListenTogether.Health
    get() = when (this) {
        ServerConnectionState.Checking -> ListenTogether.Health.CHECKING
        is ServerConnectionState.DefaultOnline,
        is ServerConnectionState.CustomOnline,
        is ServerConnectionState.CustomFallback -> ListenTogether.Health.ONLINE
        ServerConnectionState.Offline -> ListenTogether.Health.OFFLINE
    }
