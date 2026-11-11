package com.bxane.nuvx.ui

import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.playback.AudioCache
import com.bxane.nuvx.ui.screens.CACHE_FOLDER_BROWSE_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal class MainDetailInvalidationController(
    private val scope: CoroutineScope,
    private val detailStack: MutableStateFlow<List<DetailPage>>,
    private val reloadLocalDetail: (String) -> Unit,
    private val reloadRemoteDetail: (String) -> Unit,
) {
    fun start() {
        scope.launch {
            AppSettings.filterNonMusicAudio.drop(1).collect {
                if (detailStack.value.any { page -> page.browseId == "local:all" }) {
                    reloadLocalDetail("local:all")
                }
            }
        }
        scope.launch {
            AudioCache.contentsChanged.drop(1).collect {
                if (detailStack.value.any { page -> page.browseId == CACHE_FOLDER_BROWSE_ID }) {
                    reloadLocalDetail(CACHE_FOLDER_BROWSE_ID)
                }
            }
        }
        scope.launch {
            AppSettings.webdavUrl.drop(1).collect {
                reloadRemoteDetail(com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID)
            }
        }
        scope.launch {
            combine(
                AppSettings.smbHost,
                AppSettings.smbShare,
                AppSettings.smbBasePath,
                AppSettings.smbUsername,
                AppSettings.smbPassword,
            ) { fields -> fields.toList() }
                .drop(1)
                .debounce(300)
                .collect { reloadRemoteDetail(com.bxane.nuvx.data.smb.SmbConfig.BROWSE_ID) }
        }
    }
}
