package com.bxane.nuvx.data.innertube.potoken

import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.bxane.nuvx.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface PoTokenMinter {
    val isExpired: Boolean

    val isDead: Boolean

    suspend fun generatePoToken(identifier: String): String

    fun close()
}

class PoTokenGenerator(
    private val createMinter: suspend () -> PoTokenMinter,
    private val available: () -> Boolean = { true },
) {
    private val TAG = "PoTokenGenerator"

    private var webViewBadImpl = false

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenMinter? = null

    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        val supported = available()
        TrackLog.d(TAG, "WebView state: supported=$supported, badImpl=$webViewBadImpl")
        if (!supported || webViewBadImpl) {
            TrackLog.d(TAG, "WebView not available: supported=$supported, badImpl=$webViewBadImpl")
            return null
        }
        return try {
            withTimeout(POTOKEN_TIMEOUT_MS) {
                getWebClientPoToken(videoId, sessionId, forceRecreate = false)
            }
        } catch (e: TimeoutCancellationException) {
            TrackLog.w(TAG, "poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms; proceeding without PoToken")
            clearGenerator()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadWebViewException) {
            TrackLog.e(TAG, "Could not obtain PO token because WebView is unavailable")
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            TrackLog.e(TAG, "PO token generation failed type=${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }

    suspend fun close() {
        clearGenerator()
    }

    private suspend fun clearGenerator() {
        webPoTokenGenLock.withLock {
            try {
                webPoTokenGenerator?.close()
            } catch (error: Exception) {
                TrackLog.e(TAG, "PO token WebView cleanup failed type=${error::class.simpleName ?: "unknown"}")
            }
            webPoTokenGenerator = null
            webPoTokenStreamingPot = null
            webPoTokenSessionId = null
        }
    }

    private companion object {
        const val POTOKEN_TIMEOUT_MS = 8_000L
    }

    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val shouldRecreate =
                    forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired ||
                        webPoTokenGenerator!!.isDead ||
                        webPoTokenSessionId != sessionId
                if (shouldRecreate) {
                    TrackLog.d(TAG, "Creating new PoTokenWebView (forceRecreate=$forceRecreate)")
                    webPoTokenGenerator?.close()
                    webPoTokenGenerator = null
                    webPoTokenStreamingPot = null
                    webPoTokenSessionId = null
                    val newGenerator = createMinter()
                    val newStreamingPot = try {
                        newGenerator.generatePoToken(sessionId)
                    } catch (t: Throwable) {
                        runCatching { newGenerator.close() }
                        throw t
                    }
                    webPoTokenGenerator = newGenerator
                    webPoTokenStreamingPot = newStreamingPot
                    webPoTokenSessionId = sessionId
                    TrackLog.d(TAG, "Streaming PO token generated")
                }
                Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
            }
        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                throw throwable
            } else {
                TrackLog.e(TAG, "PO-token generation failed; recreating WebView")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        }
        TrackLog.d(TAG, "PO token generated successfully")
        return PoTokenResult(
            playerRequestPoToken = streamingPot,
            streamingDataPoToken = playerPot,
        )
    }

    fun asTokenProvider(): TokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): com.metrolist.innertubex.extraction.PoTokenResult? =
            getWebClientPoToken(videoId, visitorData)?.let { token ->
                com.metrolist.innertubex.extraction.PoTokenResult(
                    playerRequestToken = token.playerRequestPoToken,
                    streamingDataToken = token.streamingDataPoToken,
                    visitorData = visitorData,
                )
            }

        override suspend fun close() {
            this@PoTokenGenerator.close()
        }
    }
}
