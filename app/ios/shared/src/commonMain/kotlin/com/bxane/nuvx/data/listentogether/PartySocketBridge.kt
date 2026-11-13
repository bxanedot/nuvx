package com.bxane.nuvx.data.listentogether

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume


























object PartySocketBridge {

    interface Impl {






        fun connect(base: String, code: String, token: String, onFrame: FrameCallback)

        fun stop()










        fun sendRaw(json: String)
    }

    fun interface FrameCallback {

        fun onFrame(json: String?)
    }

    @Volatile
    private var impl: Impl? = null

    fun setImpl(value: Impl?) {
        impl = value
    }

    val isWired: Boolean get() = impl != null








    suspend fun connect(base: String, code: String, token: String, onFrame: (String) -> Unit) {
        val bridge = impl ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            val callback = FrameCallback { json ->
                if (json != null) onFrame(json)
            }
            bridge.connect(base, code, token, callback)
            cont.invokeOnCancellation { bridge.stop() }



        }
    }

    fun stop() {
        impl?.stop()
    }








    fun sendRaw(json: String) {
        impl?.sendRaw(json)
    }
}
