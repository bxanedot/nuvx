package com.bxane.nuvx.playback.cast

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

object CastController {
    private const val TAG = "NuvxCast"

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

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var started = false
    private var playback: CastPlayback? = null

    private var watched: CastSession? = null

    private val volumeListener = object : Cast.Listener() {
        override fun onVolumeChanged() {
            watched?.let { _volume.value = it.volume.toFloat().coerceIn(0f, 1f) }
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = update { it.copy(connecting = true) }

        override fun onSessionStarted(session: CastSession, sessionId: String) =
            began(session, resumed = false)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "session start failed: $error")
            update { it.copy(connecting = false) }
            playback?.onConnectFailed()
        }

        override fun onSessionEnding(session: CastSession) {
            playback?.onSessionEnding(session)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            stopWatching()
            update { it.copy(connecting = false, connectedName = null) }
            playback?.onSessionEnded(error)
            publishDevices()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) =
            update { it.copy(connecting = true) }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) =
            began(session, resumed = true)

        override fun onSessionResumeFailed(session: CastSession, error: Int) =
            update { it.copy(connecting = false) }

        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
            Log.i(TAG, "Play Services unavailable; casting disabled")
            return
        }
        try {
            CastContext.getSharedInstance(app, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx -> onReady(app, ctx) }
                .addOnFailureListener {
                    Log.w(TAG, "Cast framework unavailable", it)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Cast framework unavailable", e)
        }
    }

    private fun onReady(app: Context, ctx: CastContext) {
        castContext = ctx
        router = MediaRouter.getInstance(app)
        selector = ctx.mergedSelector
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        update { it.copy(supported = true) }
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { began(it, resumed = true) }
    }

    fun attach(target: CastPlayback) {
        playback = target
        castContext?.sessionManager?.currentCastSession
            ?.takeIf { it.isConnected }
            ?.let { target.onSessionBegan(it, resumed = true) }
    }

    fun detach(target: CastPlayback) {
        if (playback === target) playback = null
    }

    fun discover(active: Boolean): () -> Unit {
        val router = router ?: return {}
        val selector = selector ?: return {}
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteSelected(
                router: MediaRouter,
                selected: MediaRouter.RouteInfo,
                reason: Int,
            ) = publishDevices()

            override fun onRouteUnselected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) = publishDevices()
        }
        val flags = MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
            if (active) MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN else 0
        router.addCallback(selector, callback, flags)
        publishDevices()
        return { router.removeCallback(callback) }
    }

    fun connect(deviceId: String) {
        val router = router ?: return
        val route = router.routes.firstOrNull { it.id == deviceId } ?: return
        update { it.copy(connecting = true) }
        router.selectRoute(route)
    }

    fun disconnect(resumeHere: Boolean) {
        playback?.resumeHereOnEnd = resumeHere
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun setVolume(level: Float) {
        val value = level.coerceIn(0f, 1f)
        _volume.value = value
        try {
            castContext?.sessionManager?.currentCastSession?.volume = value.toDouble()
        } catch (e: Exception) {
            Log.w(TAG, "could not set receiver volume", e)
        }
    }

    private fun began(session: CastSession, resumed: Boolean) {
        watch(session)
        update {
            it.copy(
                connecting = false,
                connectedName = session.castDevice?.friendlyName ?: it.connectedName ?: "Cast",
            )
        }
        publishDevices()
        playback?.onSessionBegan(session, resumed)
    }

    private fun watch(session: CastSession) {
        stopWatching()
        watched = session
        session.addCastListener(volumeListener)
        _volume.value = session.volume.toFloat().coerceIn(0f, 1f)
    }

    private fun stopWatching() {
        watched?.removeCastListener(volumeListener)
        watched = null
    }

    private fun publishDevices() {
        val router = router
        val selector = selector
        val devices = if (router == null || selector == null) {
            emptyList()
        } else {
            router.routes
                .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
                .map {
                    Device(
                        id = it.id,
                        name = it.name,
                        connecting = it.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                        connected = it.isSelected,
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
        update { it.copy(devices = devices) }
    }

    private inline fun update(change: (State) -> State) {
        _state.value = change(_state.value)
    }
}
