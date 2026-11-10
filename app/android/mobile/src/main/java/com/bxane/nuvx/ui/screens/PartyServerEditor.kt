package com.bxane.nuvx.ui.screens

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.R
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.listentogether.ServerConnectionState
import com.bxane.nuvx.data.listentogether.ServerUrlValidationResult
import com.bxane.nuvx.ui.components.AddonEditorAlert
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

@Composable
fun PartyServerEditor(hazeState: HazeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val customServer by ListenTogether.customServerUrl.collectAsStateWithLifecycle()
    val party by ListenTogether.state.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf(customServer) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val save: () -> Unit = {
        when (val validation = ListenTogether.parseAndNormalizeServerUrl(input)) {
            is ServerUrlValidationResult.Invalid ->
                status = context.getString(validation.error.toMessageRes()) to false

            is ServerUrlValidationResult.Valid -> {
                busy = true
                status = null
                scope.launch {
                    val resolved = ListenTogether.setCustomServerUrl(validation.normalizedUrl)
                    busy = false
                    val messageRes = when (resolved) {
                        is ServerConnectionState.CustomOnline ->
                            R.string.listen_together_custom_server_connected
                        is ServerConnectionState.CustomFallback ->
                            R.string.listen_together_custom_server_unreachable_fallback
                        is ServerConnectionState.DefaultOnline ->
                            R.string.listen_together_switched_to_default
                        is ServerConnectionState.Offline ->
                            if (ListenTogether.customServerUrl.value.isNotBlank()) {
                                R.string.listen_together_status_all_offline
                            } else {
                                R.string.listen_together_server_offline
                            }
                        ServerConnectionState.Checking -> null
                    }
                    if (messageRes != null) {
                        Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            }
        }
    }

    val remove: () -> Unit = {
        busy = true
        status = null
        scope.launch {
            ListenTogether.setCustomServerUrl("")
            busy = false
            Toast.makeText(
                context,
                context.getString(R.string.listen_together_switched_to_default),
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
    }

    AddonEditorAlert(
        hazeState = hazeState,
        title = stringResource(R.string.listen_together_custom_server),
        description = stringResource(R.string.listen_together_custom_server_footer),
        urlValue = input,
        onUrlChange = {
            input = it
            status = null
        },
        urlPlaceholder = "https://party.example.com",
        status = status?.first,
        statusIsGood = status?.second == true,
        testing = busy,
        canSubmit = input.isNotBlank(),
        onTest = {
            when (val validation = ListenTogether.parseAndNormalizeServerUrl(input)) {
                is ServerUrlValidationResult.Invalid ->
                    status = context.getString(validation.error.toMessageRes()) to false

                is ServerUrlValidationResult.Valid -> {
                    busy = true
                    status = null
                    scope.launch {
                        val probe = ListenTogether.probeHealthWithLatency(
                            validation.normalizedUrl,
                            SERVER_PROBE_TIMEOUT_MS,
                        )
                        status = if (probe.isOnline) {
                            context.getString(R.string.listen_together_server_online, probe.latencyMs) to true
                        } else {
                            context.getString(R.string.listen_together_server_offline) to false
                        }
                        busy = false
                    }
                }
            }
        },
        onSave = save,
        onRemove = remove.takeIf { customServer.isNotBlank() && !party.inParty },
        removeLabel = stringResource(R.string.listen_together_remove_server),
        onDismiss = onDismiss,
    )
}

private const val SERVER_PROBE_TIMEOUT_MS = 8_000L
