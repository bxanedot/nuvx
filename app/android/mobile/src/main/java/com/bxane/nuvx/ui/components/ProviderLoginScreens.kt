package com.bxane.nuvx.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.R
import com.bxane.nuvx.auth.DiscordLoginScreen
import com.bxane.nuvx.auth.SpotifyLoginScreen
import com.bxane.nuvx.ui.icons.SfIcons
import com.bxane.nuvx.ui.screens.SpotifyCanvasAuthScreen

@Composable
internal fun ProviderLoginScreens(
    showDiscordLogin: Boolean,
    onDiscordLoginDismiss: () -> Unit,
    onDiscordTokenCaptured: (String) -> Unit,
    showSpotifyLogin: Boolean,
    onSpotifyLoginDismiss: () -> Unit,
    onSpotifyConnected: (String) -> Unit,
    showSpotifyCanvasAuth: Boolean,
    onSpotifyCanvasAuthDismiss: () -> Unit,
) {
    if (showDiscordLogin) {
        BackHandler { onDiscordLoginDismiss() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDiscordLoginDismiss) {
                        Icon(
                            SfIcons.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        "Sign in to Discord",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                DiscordLoginScreen(onTokenCaptured = onDiscordTokenCaptured)
            }
        }
    }

    if (showSpotifyLogin) {
        BackHandler { onSpotifyLoginDismiss() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onSpotifyLoginDismiss) {
                        Icon(
                            SfIcons.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        stringResource(R.string.spotify_sign_in),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                SpotifyLoginScreen(
                    modifier = Modifier.navigationBarsPadding(),
                    onConnected = onSpotifyConnected,
                )
            }
        }
    }

    if (showSpotifyCanvasAuth) {
        BackHandler { onSpotifyCanvasAuthDismiss() }
        SpotifyCanvasAuthScreen(onNavigateUp = onSpotifyCanvasAuthDismiss)
    }
}
