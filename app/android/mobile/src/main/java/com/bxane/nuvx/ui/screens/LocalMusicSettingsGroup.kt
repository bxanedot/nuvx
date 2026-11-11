package com.bxane.nuvx.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.components.IosSwitch
import com.bxane.nuvx.ui.icons.SfIcons

@Composable
internal fun LocalMusicSettingsGroup(
    search: SettingsSearch,
    localMusicFolderUri: String,
    filterNonMusicAudio: Boolean,
    onSelectFolder: () -> Unit,
) {
    SearchableSettingsGroup(search, header = stringResource(R.string.local_music)) {
        val localMusicFolderTitle = stringResource(R.string.local_music_folder)
        row(localMusicFolderTitle, "folder", "offline", "files") {
            SettingsRow(
                icon = SfIcons.Folder,
                title = localMusicFolderTitle,
                subtitle = LocalMediaRepository.selectedFolderLabel(localMusicFolderUri)
                    ?: stringResource(R.string.all_audio_folders),
                onClick = onSelectFolder,
            )
        }
        if (localMusicFolderUri.isNotBlank()) {
            val useAllAudioFoldersTitle = stringResource(R.string.use_all_audio_folders)
            row(useAllAudioFoldersTitle, "folder") {
                SettingsRow(
                    icon = SfIcons.LibraryMusic,
                    title = useAllAudioFoldersTitle,
                    subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                    onClick = { AppSettings.setLocalMusicFolderUri("") },
                )
            }
        }
        val filterNonMusicAudioTitle = stringResource(R.string.filter_non_music_audio)
        row(filterNonMusicAudioTitle, "podcast", "recording") {
            SettingsRow(
                icon = SfIcons.FilterAlt,
                title = filterNonMusicAudioTitle,
                subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
                trailing = {
                    IosSwitch(
                        checked = filterNonMusicAudio,
                        onCheckedChange = AppSettings::setFilterNonMusicAudio,
                    )
                },
                onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
            )
        }
    }
}
