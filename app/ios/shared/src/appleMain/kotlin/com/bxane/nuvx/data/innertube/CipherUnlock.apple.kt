package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.DebugLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume





actual object CipherUnlock {
    actual suspend fun signatureTimestamp(): Int? =
        CipherUnlockBridge.signatureTimestamp()

    actual suspend fun unlockCipher(videoId: String, cipher: String): String? =
        CipherUnlockBridge.unlockCipher(videoId, cipher)

    actual suspend fun transformUrl(url: String): String? = CipherUnlockBridge.transformUrl(url)
}




object CipherUnlockBridge {

    interface Impl {
        fun signatureTimestamp(callback: ResultCallback)
        fun unlockCipher(videoId: String, cipher: String, callback: ResultCallback)
        fun transformUrl(url: String, callback: ResultCallback)
    }

    fun interface ResultCallback {
        fun onResult(value: String?, error: String?)
    }

    @Volatile
    private var impl: Impl? = null

    fun setImpl(value: Impl?) {
        impl = value
    }

    suspend fun signatureTimestamp(): Int? {
        val bridge = impl ?: return null
        val raw = suspendCancellableCoroutine { cont ->
            bridge.signatureTimestamp(ResultCallback { value, _ ->
                if (cont.isActive) cont.resume(value)
            })
        }
        return raw?.toIntOrNull()
    }

    suspend fun unlockCipher(videoId: String, cipher: String): String? {
        val bridge = impl ?: return null
        return suspendCancellableCoroutine { cont ->
            bridge.unlockCipher(videoId, cipher, ResultCallback { value, error ->
                if (error != null) {




                    if (error.isUnparseablePlayer()) SignatureSolver.markBroken(error)
                    DebugLog.d("cipher unlock failed: $error")
                }
                if (cont.isActive) cont.resume(value)
            })
        }
    }

    suspend fun transformUrl(url: String): String? {
        val bridge = impl ?: return url
        return suspendCancellableCoroutine { cont ->
            bridge.transformUrl(url, ResultCallback { value, error ->
                if (error != null) DebugLog.d("media URL transform failed: $error")
                if (cont.isActive) cont.resume(value)
            })
        }
    }










    private fun String.isUnparseablePlayer(): Boolean {
        val text = lowercase()
        return "deobfuscation function" in text ||
            "player js" in text ||
            "player javascript" in text ||
            "javascript base url" in text ||
            "not found in base.js" in text ||
            "function" in text && "not found" in text
    }
}
