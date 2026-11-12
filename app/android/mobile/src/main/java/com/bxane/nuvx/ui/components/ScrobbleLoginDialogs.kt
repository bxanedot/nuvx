package com.bxane.nuvx.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bxane.nuvx.R
import com.bxane.nuvx.data.scrobbling.LastFM
import com.bxane.nuvx.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ScrobbleLoginDialogs(
    hazeState: HazeState,
    showListenBrainzLogin: Boolean,
    listenBrainzToken: String,
    onListenBrainzSave: (String) -> Unit,
    onListenBrainzDismiss: () -> Unit,
    showLastfmLogin: Boolean,
    onLastfmSession: (String, String) -> Unit,
    onLastfmDismiss: () -> Unit,
    scope: CoroutineScope,
    context: Context,
) {
    if (showListenBrainzLogin) {
        var tokenInput by remember { mutableStateOf(listenBrainzToken) }
        ListenBrainzTokenAlert(
            hazeState = hazeState,
            tokenInput = tokenInput,
            onTokenInputChange = { tokenInput = it },
            onSave = { onListenBrainzSave(tokenInput.trim()) },
            onDismiss = onListenBrainzDismiss,
        )
    }

    if (showLastfmLogin) {
        var usernameInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var lastfmError by remember { mutableStateOf<String?>(null) }
        var lastfmLoading by remember { mutableStateOf(false) }
        LastfmLoginAlert(
            hazeState = hazeState,
            usernameInput = usernameInput,
            onUsernameInputChange = { usernameInput = it },
            passwordInput = passwordInput,
            onPasswordInputChange = { passwordInput = it },
            error = lastfmError,
            loading = lastfmLoading,
            onSignIn = {
                lastfmLoading = true
                lastfmError = null
                scope.launch {
                    try {
                        LastFM.initialize(
                            apiKey = AppSettings.lastfmApiKey.value,
                            secret = AppSettings.lastfmSecret.value,
                        )
                        LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                            .onSuccess { auth ->
                                onLastfmSession(auth.session.key, auth.session.name)
                            }
                            .onFailure { e ->
                                lastfmError = e.message ?: context.getString(R.string.login_failed)
                            }
                    } catch (e: Exception) {
                        lastfmError = e.message ?: context.getString(R.string.login_failed)
                    } finally {
                        lastfmLoading = false
                    }
                }
            },
            onDismiss = { if (!lastfmLoading) onLastfmDismiss() },
        )
    }
}
