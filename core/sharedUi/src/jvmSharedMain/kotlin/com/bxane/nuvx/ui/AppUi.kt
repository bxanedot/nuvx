package com.bxane.nuvx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.data.settings.LibrarySort
import kotlinx.coroutines.flow.StateFlow

interface AppUiHost {
    val swipeToPlayNext: StateFlow<Boolean>

    val pinnedPlaylists: StateFlow<List<String>>

    val librarySort: StateFlow<LibrarySort>

    @Composable
    fun downloadedIds(): Set<String>

    suspend fun releaseMotion(album: String, artist: String): CanvasArtwork? = null

    suspend fun artistMotion(browseId: String, artistName: String): CanvasArtwork? = null
}

object AppUi {
    @Volatile
    private var installed: AppUiHost? = null

    fun install(host: AppUiHost) {
        installed = host
    }

    val host: AppUiHost
        get() = checkNotNull(installed) { "AppUi.install was not called" }
}

val LocalPullToRefreshEnabled = staticCompositionLocalOf { true }
