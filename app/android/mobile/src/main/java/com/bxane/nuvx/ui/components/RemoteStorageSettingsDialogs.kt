package com.bxane.nuvx.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.R
import com.bxane.nuvx.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState

@Composable
internal fun RemoteStorageSettingsDialogs(
    hazeState: HazeState,
    showWebDavEditor: Boolean,
    onWebDavEditorDismiss: () -> Unit,
    showSmbEditor: Boolean,
    onSmbEditorDismiss: () -> Unit,
) {
    if (showWebDavEditor) {
        BackHandler { onWebDavEditorDismiss() }
        ServerEditorHost(
            hazeState = hazeState,
            title = stringResource(R.string.webdav),
            description = stringResource(R.string.webdav_description),
            fields = listOf(
                FieldConfig(
                    initial = AppSettings.webdavUrl.value,
                    placeholder = stringResource(R.string.webdav_server_url_hint),
                    keyboardType = KeyboardType.Uri,
                ),
                FieldConfig(
                    initial = AppSettings.webdavUsername.value,
                    placeholder = stringResource(R.string.username),
                ),
                FieldConfig(
                    initial = AppSettings.webdavPassword.value,
                    placeholder = stringResource(R.string.password),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                ),
            ),
            canSubmit = { it[0].isNotBlank() },
            testFailedRes = R.string.webdav_test_failed,
            onTest = { (url, username, password) ->
                com.bxane.nuvx.data.webdav.WebDavRepository.testConnection(
                    url.trim(),
                    username.trim(),
                    password,
                )
            },
            onSave = { (url, username, password) ->
                AppSettings.setWebDavUrl(url.trim())
                AppSettings.setWebDavUsername(username.trim())
                AppSettings.setWebDavPassword(password)
                onWebDavEditorDismiss()
            },
            onDismiss = onWebDavEditorDismiss,
        )
    }

    if (showSmbEditor) {
        BackHandler { onSmbEditorDismiss() }
        ServerEditorHost(
            hazeState = hazeState,
            title = stringResource(R.string.smb),
            description = stringResource(R.string.smb_description),
            fields = listOf(
                FieldConfig(
                    initial = AppSettings.smbHost.value,
                    placeholder = stringResource(R.string.smb_server_hint),
                    keyboardType = KeyboardType.Uri,
                ),
                FieldConfig(
                    initial = AppSettings.smbShare.value,
                    placeholder = stringResource(R.string.smb_share_hint),
                ),
                FieldConfig(
                    initial = AppSettings.smbBasePath.value,
                    placeholder = stringResource(R.string.smb_folder_hint),
                ),
                FieldConfig(
                    initial = AppSettings.smbUsername.value,
                    placeholder = stringResource(R.string.username),
                ),
                FieldConfig(
                    initial = AppSettings.smbPassword.value,
                    placeholder = stringResource(R.string.password),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                ),
            ),
            canSubmit = { it[0].isNotBlank() && it[1].isNotBlank() },
            testFailedRes = R.string.smb_test_failed,
            onTest = { (host, share, folder, username, password) ->
                com.bxane.nuvx.data.smb.SmbRepository.testConnection(
                    host.trim(),
                    share.trim(),
                    folder.trim(),
                    username.trim(),
                    password,
                )
            },
            onSave = { (host, share, folder, username, password) ->
                AppSettings.setSmbHost(host.trim())
                AppSettings.setSmbShare(share.trim())
                AppSettings.setSmbBasePath(folder.trim())
                AppSettings.setSmbUsername(username.trim())
                AppSettings.setSmbPassword(password)
                onSmbEditorDismiss()
            },
            onDismiss = onSmbEditorDismiss,
        )
    }

    val uploadConflict by com.bxane.nuvx.data.webdav.WebDavUploads.conflict.collectAsStateWithLifecycle()
    uploadConflict?.let { req ->
        var applyToAll by remember(req) { mutableStateOf(false) }
        val answer: (com.bxane.nuvx.data.webdav.WebDavUploads.Choice) -> Unit = { choice ->
            req.answer.complete(
                com.bxane.nuvx.data.webdav.WebDavUploads.Resolution(choice, applyToAll),
            )
        }
        BackHandler { answer(com.bxane.nuvx.data.webdav.WebDavUploads.Choice.SKIP) }
        WebDavConflictAlert(
            hazeState = hazeState,
            fileName = req.fileName,
            showApplyToAll = req.remaining > 0,
            applyToAll = applyToAll,
            onApplyToAllChange = { applyToAll = it },
            onOverwrite = { answer(com.bxane.nuvx.data.webdav.WebDavUploads.Choice.OVERWRITE) },
            onKeepBoth = { answer(com.bxane.nuvx.data.webdav.WebDavUploads.Choice.KEEP_BOTH) },
            onSkip = { answer(com.bxane.nuvx.data.webdav.WebDavUploads.Choice.SKIP) },
            onDismiss = { answer(com.bxane.nuvx.data.webdav.WebDavUploads.Choice.SKIP) },
        )
    }
}
