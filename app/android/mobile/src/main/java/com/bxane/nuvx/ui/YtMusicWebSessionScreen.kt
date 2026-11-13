

package com.bxane.nuvx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.R
import com.bxane.nuvx.auth.CapturedSession
import com.bxane.nuvx.auth.WebSessionMode
import com.bxane.nuvx.auth.YtMusicLoginScreen
import com.bxane.nuvx.ui.icons.SfIcons

@Composable
internal fun YtMusicWebSessionScreen(
    mode: WebSessionMode,
    initialCookie: String?,
    onDismiss: () -> Unit,
    onCaptured: (CapturedSession, (Boolean) -> Unit) -> Unit,
    onSignedIn: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var captureRequest by remember(mode) { mutableIntStateOf(0) }
    var captureFailed by remember(mode) { mutableStateOf(false) }
    var pageReady by remember(mode) { mutableStateOf(false) }
    var confirmingProfile by remember(mode) { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        SfIcons.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (mode) {
                            WebSessionMode.SIGN_IN -> stringResource(R.string.sign_in_youtube_music)
                            WebSessionMode.SWITCH_CHANNEL -> stringResource(R.string.choose_profile)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (pageReady) {
                        Text(
                            text = if (captureFailed) {
                                stringResource(R.string.profile_unavailable)
                            } else {
                                stringResource(R.string.switch_profile_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                if (pageReady) {
                    TextButton(onClick = {
                        captureFailed = false
                        confirmingProfile = true
                        captureRequest++
                    }, enabled = !confirmingProfile) {
                        Text(
                            if (confirmingProfile) stringResource(R.string.checking)
                            else stringResource(R.string.use_this_profile),
                        )
                    }
                }
            }
            YtMusicLoginScreen(
                mode = mode,
                initialCookie = initialCookie,
                captureRequest = captureRequest,
                onPageReady = { pageReady = it },
                onCaptureUnavailable = {
                    confirmingProfile = false
                    captureFailed = true
                },
                onCaptured = { session ->
                    onCaptured(session) { accepted ->
                        confirmingProfile = false
                        if (accepted) {
                            onDismiss()
                            if (mode == WebSessionMode.SIGN_IN) onSignedIn()
                        } else {
                            captureFailed = true
                        }
                    }
                },
            )
        }
    }
}
