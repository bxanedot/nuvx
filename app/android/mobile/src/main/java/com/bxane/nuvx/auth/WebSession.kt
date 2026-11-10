package com.bxane.nuvx.auth

import android.webkit.CookieManager
import com.bxane.nuvx.data.DebugLog as Log

enum class WebSessionMode {
    SIGN_IN,

    SWITCH_CHANNEL,
}

data class CapturedSession(
    val cookie: String,
    val pageId: String?,
    val dataSyncId: String?,
    val authUser: String?,
    val visitorData: String?,
    val clientVersion: String?,
    val loggedIn: Boolean,
)

object BrowserSession {

    fun installGoogleCookies(cookieHeader: String) {
        clearGoogleCookies()
        val manager = runCatching { CookieManager.getInstance() }.getOrElse {
            Log.w("nuvx", "no cookie manager to restore: ${it.message}")
            return
        }
        val entries = cookieHeader.split(';').mapNotNull { entry ->
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else "$name=$value; Path=/; Secure"
        }
        YOUTUBE_ORIGINS.forEach { origin ->
            entries.forEach { cookie -> manager.setCookie(origin, cookie) }
        }
        runCatching { manager.flush() }
        Log.d("nuvx", "restored ${entries.size} browser cookies for the selected account")
    }

    fun clearGoogleCookies() {
        val manager = runCatching { CookieManager.getInstance() }.getOrElse {
            Log.w("nuvx", "no cookie manager to clear: ${it.message}")
            return
        }
        var cleared = 0
        GOOGLE_ORIGINS.forEach { origin ->
            val jar = manager.getCookie(origin) ?: return@forEach
            val host = origin.substringAfter("://")
            jar.split(';').forEach { entry ->
                val name = entry.substringBefore('=').trim()
                if (name.isEmpty()) return@forEach
                val expired = "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
                manager.setCookie(origin, "$expired; Path=/")
                manager.setCookie(origin, "$expired; Path=/; Domain=$host")
                manager.setCookie(origin, "$expired; Path=/; Domain=.$host")
                manager.setCookie(origin, "$expired; Path=/accounts")
                manager.setCookie(origin, "$expired; Path=/ServiceLogin")
                cleared++
            }
        }
        runCatching {
            val storage = android.webkit.WebStorage.getInstance()
            GOOGLE_ORIGINS.forEach { origin -> storage.deleteOrigin(origin) }
            manager.flush()
        }
        Log.d("nuvx", "cleared $cleared browser cookies for Google")
    }

    private val GOOGLE_ORIGINS = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://youtube.com",
        "https://accounts.google.com",
        "https://www.google.com",
        "https://google.com",
    )

    private val YOUTUBE_ORIGINS = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://youtube.com",
    )
}
