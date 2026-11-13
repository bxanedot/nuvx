package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch























object AuthBridge {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun interface DoneCallback {
        fun onResult(ok: Boolean, message: String?)
    }

    fun interface IdentityCallback {
        fun onResult(pageId: String?, dataSyncId: String?, authUser: String?)
    }











    fun currentIdentity(callback: IdentityCallback) {
        val generation = Innertube.sessionGeneration
        bridgeScope.launch {
            val identity = runCatching {
                Innertube.checkSession(generation)
                Innertube.ensureSessionScope()
                Innertube.checkSession(generation)
                Innertube.currentIdentity()
            }.getOrNull()
            callback.onResult(identity?.pageId, identity?.dataSyncId, identity?.authUser)
        }
    }


    fun hasApiSid(cookieHeader: String): Boolean = Innertube.hasApiSid(cookieHeader)









    fun isSessionExpired(message: String?): Boolean =
        message?.contains(Innertube.SESSION_EXPIRED_MESSAGE, ignoreCase = true) == true








    fun applyCookie(cookieHeader: String?): Boolean {
        if (cookieHeader == null) {
            Innertube.cookie = null
            StreamResolver.onSessionChanged()
            return true
        }
        if (!Innertube.hasApiSid(cookieHeader)) {
            DebugLog.w("refusing a session cookie with no SAPISID signing secret")
            return false
        }
        Innertube.cookie = cookieHeader
        StreamResolver.onSessionChanged()
        return true
    }

    fun sessionGeneration(): Long = Innertube.sessionGeneration

    fun isSignedIn(): Boolean = Innertube.cookie?.let { Innertube.hasApiSid(it) } == true







    fun adoptSessionScope(
        pageId: String?,
        dataSyncId: String?,
        authUser: String?,
        visitorData: String?,
        clientVersion: String?,
        loggedIn: Boolean,
    ) {
        Innertube.adoptSessionScope(pageId, dataSyncId, authUser, visitorData, clientVersion, loggedIn)
        Innertube.adoptPageScope(pageId, dataSyncId, authUser)
    }


    fun clearChannelOverride() {
        Innertube.adoptPageScope(null, null, null)
    }











    fun ensureSession(callback: DoneCallback) {
        val generation = Innertube.sessionGeneration
        bridgeScope.launch {
            try {
                Innertube.checkSession(generation)
                Innertube.ensureSessionScope()
                Innertube.checkSession(generation)
                callback.onResult(true, null)
            } catch (e: Throwable) {
                DebugLog.e("could not read the session scope", e)
                callback.onResult(false, e.message ?: e.toString())
            }
        }
    }
}
