package com.bxane.nuvx.data

import kotlin.concurrent.Volatile
















object DebugLog {

    const val TAG = "nuvx"






    @Volatile
    var enabled: Boolean = true

    internal fun sanitize(message: String): String = message
        .replace(Regex("(?im)^.*(?:authorization\\s*[:=]|cookie\\s*[:=]|set-cookie\\s*[:=]).*$"), "<credential redacted>")
        .replace(Regex("(?i)(?:SAPISID|__Secure-[13]PAPISID|SID|HSID|sp_dc)=[^;\\s]+"), "<credential redacted>")
        .replace(Regex("https?://[^\\s\"<>]+")) { match ->
            runCatching {
                val url = io.ktor.http.Url(match.value)
                "${url.protocol.name}://${url.host}/<path redacted>"
            }.getOrDefault("<URL redacted>")
        }

    fun d(message: String) {
        if (enabled) println("[$TAG] ${sanitize(message)}")
    }

    fun w(message: String) {
        if (enabled) println("[$TAG][warn] ${sanitize(message)}")
    }

    fun w(message: String, error: Throwable) {
        if (enabled) println("[$TAG][warn] ${sanitize(message)}: ${error::class.simpleName}")
    }

    fun e(message: String, error: Throwable) {
        if (enabled) println("[$TAG][error] ${sanitize(message)}: ${error::class.simpleName}")
    }
}
