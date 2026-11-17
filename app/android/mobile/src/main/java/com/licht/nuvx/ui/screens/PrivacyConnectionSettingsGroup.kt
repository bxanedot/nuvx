package com.licht.nuvx.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licht.nuvx.R
import com.licht.nuvx.net.privacy.BackendId
import com.licht.nuvx.net.privacy.ConnectionState
import com.licht.nuvx.net.privacy.OrbotBackend
import com.licht.nuvx.net.privacy.PrivacyMode
import com.licht.nuvx.net.privacy.PrivacyNetwork
import com.licht.nuvx.ui.icons.SfIcons
import kotlinx.coroutines.launch

@Composable
private fun ConnectionState.label(): String = when (this) {
    ConnectionState.Disabled -> stringResource(R.string.privacy_status_disabled)
    is ConnectionState.Connecting -> stringResource(R.string.privacy_status_connecting)
    is ConnectionState.Bootstrapping -> stringResource(R.string.privacy_status_bootstrapping, percent)
    is ConnectionState.Connected -> stringResource(
        if (backend == BackendId.ORBOT) R.string.privacy_status_connected_orbot else R.string.privacy_status_connected_embedded,
    )
    is ConnectionState.Switching -> stringResource(R.string.privacy_status_switching)
    is ConnectionState.Unavailable -> stringResource(R.string.privacy_status_unavailable)
}

@Composable
internal fun PrivacyConnectionSettingsGroup(search: SettingsSearch) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by PrivacyNetwork.savedMode.collectAsStateWithLifecycle()
    val state by PrivacyNetwork.state.collectAsStateWithLifecycle()
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }
    val modes = if (PrivacyNetwork.EMBEDDED_AVAILABLE) PrivacyMode.entries else listOf(PrivacyMode.OFF, PrivacyMode.ORBOT_ONLY)
    val labels = mapOf(
        PrivacyMode.OFF to R.string.off,
        PrivacyMode.AUTOMATIC to R.string.privacy_mode_automatic,
        PrivacyMode.ORBOT_ONLY to R.string.privacy_mode_orbot,
        PrivacyMode.EMBEDDED_ONLY to R.string.privacy_mode_embedded,
    )
    val footer = buildString {
        append(stringResource(R.string.privacy_connection_footer))
        if (PrivacyNetwork.activeMode != PrivacyMode.OFF && !PrivacyNetwork.webViewCovered) {
            append("\n\n").append(stringResource(R.string.privacy_webview_uncovered))
        }
    }

    SearchableSettingsGroup(search, header = stringResource(R.string.privacy_connection), footer = footer) {
        val title = stringResource(R.string.privacy_connection)
        row(title, "tor", "orbot", "snowflake", "proxy", "privacy", "censorship", "vpn") {
            SettingsRow(icon = SfIcons.Lock, title = title, subtitle = stringResource(R.string.privacy_connection_subtitle))
            SegmentedControl(
                options = modes.map { stringResource(labels.getValue(it)) },
                selectedIndex = modes.indexOf(saved).coerceAtLeast(0),
                onSelect = { index -> PrivacyNetwork.setMode(context, modes[index]) },
                modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        if (saved != PrivacyNetwork.activeMode) {
            val restartTitle = stringResource(R.string.restart_now)
            row(restartTitle, "restart", "apply", "tor", "orbot") {
                SettingsRow(
                    icon = SfIcons.Refresh,
                    title = restartTitle,
                    subtitle = stringResource(R.string.privacy_restart_needed),
                    onClick = {
                        // The mode is read once at start-up (see PrivacyNetwork), so applying it means a fresh process.
                        context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.let {
                            context.startActivity(android.content.Intent.makeRestartActivityTask(it))
                            Runtime.getRuntime().exit(0)
                        }
                    },
                )
            }
        }
        if (PrivacyNetwork.activeMode != PrivacyMode.OFF) {
            val statusTitle = stringResource(R.string.privacy_status)
            row(statusTitle, "tor", "status", "connected") {
                SettingsRow(icon = SfIcons.Wifi, title = statusTitle, subtitle = state.label())
            }
            val testTitle = stringResource(R.string.privacy_test)
            row(testTitle, "tor", "test", "check") {
                SettingsRow(
                    icon = SfIcons.CheckCircle,
                    title = testTitle,
                    subtitle = when {
                        testing -> stringResource(R.string.privacy_test_running)
                        testResult == true -> stringResource(R.string.privacy_test_passed)
                        testResult == false -> stringResource(R.string.privacy_test_failed)
                        else -> null
                    },
                    enabled = !testing,
                    onClick = {
                        testing = true
                        scope.launch {
                            testResult = PrivacyNetwork.test()
                            testing = false
                        }
                    },
                )
            }
            if (state is ConnectionState.Unavailable) {
                val retryTitle = stringResource(R.string.privacy_retry)
                row(retryTitle, "tor", "retry", "reconnect") {
                    SettingsRow(icon = SfIcons.Refresh, title = retryTitle, onClick = PrivacyNetwork::retry)
                }
            }
        }
        if (saved != PrivacyMode.OFF && saved != PrivacyMode.EMBEDDED_ONLY) {
            val installed = OrbotBackend.isInstalled(context)
            val orbotTitle = stringResource(if (installed) R.string.privacy_open_orbot else R.string.privacy_install_orbot)
            row(orbotTitle, "orbot", "tor", "install") {
                SettingsRow(
                    icon = SfIcons.Link,
                    title = orbotTitle,
                    subtitle = stringResource(R.string.privacy_orbot_subtitle),
                    onClick = { OrbotBackend.open(context) },
                )
            }
        }
    }
}
