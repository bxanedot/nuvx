package com.bxane.nuvx.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import com.bxane.nuvx.auth.CapturedSession
import com.bxane.nuvx.auth.WebSessionMode

fun MainViewModel.onSignedIn(cookie: String, onComplete: (Boolean) -> Unit = {}) {
    val session = CapturedSession(
        cookie = cookie.trim(),
        pageId = null,
        dataSyncId = null,
        authUser = null,
        visitorData = null,
        clientVersion = null,
        loggedIn = true,
    )
    onWebSession(session, WebSessionMode.SIGN_IN, onComplete)
}

@SuppressLint("SetJavaScriptEnabled")
fun configureTvSignInWebView(webView: WebView) {
    webView.apply {
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = false

        val cleanUa = settings.userAgentString
            ?.replace("; wv", "")
            ?.replace(Regex("Version/[0-9.]+"), "")
            ?.replace("  ", " ")
            ?.trim()
        settings.userAgentString = cleanUa
            ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        isFocusable = true
        isFocusableInTouchMode = true
    }
}
