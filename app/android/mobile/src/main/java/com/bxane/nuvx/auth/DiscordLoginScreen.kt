package com.bxane.nuvx.auth

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bxane.nuvx.data.TrackLog

private const val TOKEN_SNIPPET = """
    (function () {
      try {
        var frame = document.createElement('iframe');
        document.body.appendChild(frame);
        var token = frame.contentWindow.localStorage.token;
        document.body.removeChild(frame);
        alert(token ? token.slice(1, -1) : '');
      } catch (e) {
        alert('');
      }
    })()
"""

private const val MAX_TOKEN_ATTEMPTS = 12
private const val TOKEN_RETRY_MS = 500L

private val WEBVIEW_VERSION_TOKEN = Regex("""Version/\d+(\.\d+)*\s*""")

private const val FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

internal fun browserUserAgent(platformUserAgent: String?): String {
    val stripped = platformUserAgent
        ?.replace("; wv", "")
        ?.replace(WEBVIEW_VERSION_TOKEN, "")
        ?.replace("  ", " ")
        ?.trim()
    return stripped?.takeIf { it.contains("Chrome/") } ?: FALLBACK_USER_AGENT
}

private fun isSignedIn(url: String?): Boolean =
    url != null &&
        url.startsWith("https://discord.com/") &&
        !url.contains("/login") &&
        !url.contains("/register")

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordLoginScreen(
    onTokenCaptured: (token: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = browserUserAgent(settings.userAgentString)

                var captured = false
                var attempts = 0

                fun harvestToken() {
                    if (captured) return
                    if (attempts >= MAX_TOKEN_ATTEMPTS) {
                        TrackLog.w(
                            "Discord",
                            "no token in localStorage after $attempts tries",
                            about = null,
                        )
                        return
                    }
                    attempts++
                    evaluateJavascript(TOKEN_SNIPPET, null)
                }

                webViewClient = object : WebViewClient() {
                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        if (isSignedIn(url)) harvestToken()
                        return false
                    }

                    override fun doUpdateVisitedHistory(
                        view: WebView?,
                        url: String?,
                        isReload: Boolean,
                    ) {
                        if (isSignedIn(url)) harvestToken()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        canGoBack = view?.canGoBack() == true
                        if (isSignedIn(url)) harvestToken()
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onJsAlert(
                        view: WebView,
                        url: String,
                        message: String,
                        result: JsResult,
                    ): Boolean {
                        result.confirm()
                        if (captured) return true

                        val token = message.takeUnless {
                            it.isBlank() || it == "null" || it == "undefined"
                        }
                        if (token == null) {
                            view.postDelayed({ harvestToken() }, TOKEN_RETRY_MS)
                            return true
                        }

                        captured = true
                        TrackLog.d("Discord", "token captured after $attempts read(s)", about = null)
                        view.visibility = View.GONE
                        onTokenCaptured(token)
                        return true
                    }
                }

                webView = this
                loadUrl("https://discord.com/login")
            }
        },
    )

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }
}
