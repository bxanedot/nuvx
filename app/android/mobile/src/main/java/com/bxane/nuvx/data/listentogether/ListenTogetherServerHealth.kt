package com.bxane.nuvx.data.listentogether

import com.bxane.nuvx.data.listentogether.ListenTogether.Health
import com.bxane.nuvx.data.listentogether.ListenTogether.HealthResolution
import com.bxane.nuvx.data.listentogether.ListenTogether.PartyException

internal object ListenTogetherServerHealth {
    fun isEligibleForFallback(error: Throwable): Boolean = when (error) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.PortUnreachableException,
        is java.net.SocketTimeoutException,
        is io.ktor.client.plugins.HttpRequestTimeoutException,
        is io.ktor.client.network.sockets.SocketTimeoutException,
        is io.ktor.client.network.sockets.ConnectTimeoutException -> true
        is PartyException -> (error.statusCode ?: 0) in 500..599
        else -> {
            val cause = error.cause
            if (cause != null && cause !== error && isEligibleForFallback(cause)) true
            else false
        }
    }

    internal fun shouldSkipIdleProbe(
        screenActive: Boolean,
        connectionLive: Boolean,
        inParty: Boolean,
        metered: Boolean?,
    ): Boolean {
        if (!screenActive) return true
        if (connectionLive) return true
        if (!inParty && metered == true) return true
        return false
    }

    suspend fun resolveServerConnection(
        customServer: String,
        defaultServer: String,
        probeCustom: suspend () -> ProbeResult,
        probeDefault: suspend () -> ProbeResult,
    ): Pair<String, ServerConnectionState> {
        val normCustom = when (val res = ListenTogetherServerUrl.parseAndNormalizeServerUrl(customServer)) {
            is ServerUrlValidationResult.Valid -> res.normalizedUrl
            is ServerUrlValidationResult.Invalid -> ""
        }
        val hasCustom = normCustom.isNotBlank() && normCustom != defaultServer

        return if (hasCustom) {
            val customProbe = probeCustom()
            if (customProbe.isOnline) {
                normCustom to ServerConnectionState.CustomOnline(customProbe.latencyMs)
            } else {
                val defaultProbe = probeDefault()
                if (defaultProbe.isOnline) {
                    defaultServer to ServerConnectionState.CustomFallback(defaultProbe.latencyMs)
                } else {
                    defaultServer to ServerConnectionState.Offline
                }
            }
        } else {
            val defaultProbe = probeDefault()
            if (defaultProbe.isOnline) {
                defaultServer to ServerConnectionState.DefaultOnline(defaultProbe.latencyMs)
            } else {
                defaultServer to ServerConnectionState.Offline
            }
        }
    }

    suspend fun computeHealthResolution(
        customServer: String,
        defaultServer: String,
        probeCustom: suspend () -> Boolean,
        probeDefault: suspend () -> Boolean,
    ): HealthResolution {
        val custom = ListenTogether.normalizeServerBase(customServer)
        val hasCustom = custom.isNotBlank() && custom != defaultServer
        return if (hasCustom) {
            if (probeCustom()) {
                HealthResolution(resolvedServer = custom, health = Health.ONLINE, isFallback = false)
            } else if (probeDefault()) {
                HealthResolution(resolvedServer = defaultServer, health = Health.ONLINE, isFallback = true)
            } else {
                HealthResolution(resolvedServer = defaultServer, health = Health.OFFLINE, isFallback = false)
            }
        } else {
            if (probeDefault()) {
                HealthResolution(resolvedServer = defaultServer, health = Health.ONLINE, isFallback = false)
            } else {
                HealthResolution(resolvedServer = defaultServer, health = Health.OFFLINE, isFallback = false)
            }
        }
    }
}
