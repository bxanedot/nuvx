package com.bxane.nuvx.ui.player

import androidx.compose.runtime.Composable
import com.bxane.nuvx.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun outputLabel(device: AudioOutputDevice, accountName: String?): String {
    val firstName = accountName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }
    return when {
        device.name.isNotBlank() -> device.name
        device.kind == AudioOutputKind.WIRED -> stringResource(Res.string.wired_headphones)
        device.kind == AudioOutputKind.USB -> stringResource(Res.string.usb_audio)
        device.kind == AudioOutputKind.HDMI -> stringResource(Res.string.hdmi_output)
        firstName != null -> stringResource(Res.string.personal_phone, firstName)
        else -> stringResource(Res.string.this_phone)
    }
}

@Composable
internal fun rememberAudioOutputName(accountName: String?): String {
    val outputs = rememberAudioOutputs()
    val active = outputs.firstOrNull { it.isActive }
        ?: return if (accountName.isNullOrBlank()) {
            stringResource(Res.string.this_phone)
        } else {
            stringResource(
                Res.string.personal_phone,
                accountName.trim().split(Regex("\\s+")).first(),
            )
        }
    return outputLabel(active, accountName)
}
