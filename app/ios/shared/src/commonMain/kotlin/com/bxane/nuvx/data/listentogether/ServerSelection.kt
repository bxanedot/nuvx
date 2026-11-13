package com.bxane.nuvx.data.listentogether


enum class ServerHealth { UNKNOWN, CHECKING, ONLINE, OFFLINE }


data class ProbeResult(val isOnline: Boolean, val latencyMs: Long = 0L)











sealed interface ServerConnection {

    data object Unconfigured : ServerConnection

    data object Checking : ServerConnection


    data class DefaultOnline(val latencyMs: Long) : ServerConnection


    data class CustomOnline(val latencyMs: Long) : ServerConnection









    data class CustomFallback(val latencyMs: Long) : ServerConnection


    data object Offline : ServerConnection
}

val ServerConnection.health: ServerHealth
    get() = when (this) {
        ServerConnection.Unconfigured -> ServerHealth.UNKNOWN
        ServerConnection.Checking -> ServerHealth.CHECKING
        is ServerConnection.DefaultOnline, is ServerConnection.CustomOnline, is ServerConnection.CustomFallback ->
            ServerHealth.ONLINE
        ServerConnection.Offline -> ServerHealth.OFFLINE
    }

val ServerConnection.latencyMs: Long?
    get() = when (this) {
        is ServerConnection.DefaultOnline -> latencyMs
        is ServerConnection.CustomOnline -> latencyMs
        is ServerConnection.CustomFallback -> latencyMs
        ServerConnection.Unconfigured, ServerConnection.Checking, ServerConnection.Offline -> null
    }


val ServerConnection.isFallback: Boolean get() = this is ServerConnection.CustomFallback


data class ServerChoice(

    val base: String,
    val connection: ServerConnection,
)

















object ServerSelection {


    const val DEFAULT_SERVER_TIMEOUT_MS = 30_000L
    const val CUSTOM_SERVER_TIMEOUT_MS = 8_000L
    const val HEALTH_TIMEOUT_MS = 5_000L














    suspend fun resolve(
        customServer: String,
        defaultServer: String,
        probe: suspend (base: String, timeoutMs: Long) -> ProbeResult,
    ): ServerChoice {
        val custom = ServerUrl.parseAndNormalize(customServer).normalizedOrNull.orEmpty()
        val builtIn = ServerUrl.parseAndNormalize(defaultServer).normalizedOrNull.orEmpty()




        val hasCustom = custom.isNotEmpty() && custom != builtIn

        if (hasCustom) {
            val customProbe = probe(custom, CUSTOM_SERVER_TIMEOUT_MS)
            if (customProbe.isOnline) {
                return ServerChoice(custom, ServerConnection.CustomOnline(customProbe.latencyMs))
            }
            if (builtIn.isEmpty()) {



                return ServerChoice(custom, ServerConnection.Offline)
            }
            val builtInProbe = probe(builtIn, DEFAULT_SERVER_TIMEOUT_MS)
            return if (builtInProbe.isOnline) {
                ServerChoice(builtIn, ServerConnection.CustomFallback(builtInProbe.latencyMs))
            } else {
                ServerChoice(builtIn, ServerConnection.Offline)
            }
        }

        if (builtIn.isEmpty()) return ServerChoice("", ServerConnection.Unconfigured)
        val builtInProbe = probe(builtIn, DEFAULT_SERVER_TIMEOUT_MS)
        return if (builtInProbe.isOnline) {
            ServerChoice(builtIn, ServerConnection.DefaultOnline(builtInProbe.latencyMs))
        } else {
            ServerChoice(builtIn, ServerConnection.Offline)
        }
    }










    fun switchTarget(
        inviteServer: String?,
        customServer: String,
        idleServer: String,
    ): String {
        val fromInvite = ServerUrl.parseAndNormalize(inviteServer).normalizedOrNull
        if (!fromInvite.isNullOrEmpty()) return fromInvite
        val custom = ServerUrl.parseAndNormalize(customServer).normalizedOrNull.orEmpty()
        return custom.ifEmpty { idleServer }
    }
}










sealed interface PartyFailure {







    data class Transport(val detail: String = "") : PartyFailure


    data class Server(val statusCode: Int) : PartyFailure


    data class Rejected(val statusCode: Int) : PartyFailure


    data class Other(val detail: String = "") : PartyFailure
}

















fun PartyFailure.isEligibleForFallback(): Boolean = when (this) {
    is PartyFailure.Transport -> true
    is PartyFailure.Server -> statusCode in 500..599
    is PartyFailure.Rejected -> false
    is PartyFailure.Other -> false
}
