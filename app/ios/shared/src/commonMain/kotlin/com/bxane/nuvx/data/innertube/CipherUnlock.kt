package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.DebugLog
import kotlin.concurrent.Volatile






expect object CipherUnlock {
    suspend fun signatureTimestamp(): Int?
    suspend fun unlockCipher(videoId: String, cipher: String): String?
    suspend fun transformUrl(url: String): String?
}



















object SignatureSolver {

    @Volatile
    private var broken = false

    val isBroken: Boolean get() = broken


    fun markBroken(cause: String) {
        if (broken) return
        broken = true
        DebugLog.w(
            "cannot read YouTube's current player JavaScript ($cause); ciphered formats are " +
                "unavailable for this process — signed-in device clients remain the route to an " +
                "age-restricted track",
        )
    }

    fun reset() {
        broken = false
    }
}
