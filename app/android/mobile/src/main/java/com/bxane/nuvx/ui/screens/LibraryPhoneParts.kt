package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.R
import com.bxane.nuvx.sharedui.resources.Res
import com.bxane.nuvx.sharedui.resources.spotify_logo
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.download.SavedCollection
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.ReplayCardRowSkeleton
import com.bxane.nuvx.ui.replay.ReplayCardRow
import com.bxane.nuvx.ui.replay.ReplayHeroCard
import com.bxane.nuvx.ui.replay.ReplayStoryPage

@Composable
fun libraryLinks(): List<LibraryLink> {
    val webdavConfigured by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val spotifyConnected by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    fun link(title: String, subtitle: String, browseId: String, icon: ImageVector = deviceFolderIcon(browseId) ?: SfIcons.Cloud) = LibraryLink(
        item = ShelfItem(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = null,
            videoId = null,
            browseId = browseId,
        ),
        icon = icon,
    )
    return listOfNotNull(
        link(
            stringResource(R.string.downloads),
            stringResource(R.string.downloaded_songs),
            "local:downloads",
        ),
        link(
            stringResource(R.string.local_music),
            stringResource(R.string.audio_files_on_device),
            "local:all",
        ),
        link(
            stringResource(R.string.cached_songs),
            stringResource(R.string.cached_songs_subtitle),
            CACHE_FOLDER_BROWSE_ID,
        ).takeIf { showCacheFolder },
        link(
            stringResource(R.string.webdav),
            stringResource(R.string.webdav_subtitle),
            com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID,
        ).takeIf { webdavConfigured.isNotBlank() },
        link(
            stringResource(R.string.smb),
            stringResource(R.string.smb_subtitle),
            com.bxane.nuvx.data.smb.SmbConfig.BROWSE_ID,
        ).takeIf { smbHost.isNotBlank() && smbShare.isNotBlank() },
        link(
            stringResource(R.string.spotify),
            stringResource(R.string.spotify_library_subtitle),
            SPOTIFY_BROWSE_ID,
        ).takeIf { spotifyConnected.isNotBlank() }?.copy(logo = Res.drawable.spotify_logo),
    )
}

@Composable
fun libraryDeviceItems(downloadedReleases: List<SavedCollection>): List<ShelfItem> {
    val downloadedPlaylist = stringResource(R.string.downloaded_playlist)
    return downloadedReleases.map { release ->
        ShelfItem(
            title = release.title,
            subtitle = release.subtitle.ifBlank { if (release.playlist) downloadedPlaylist else "" },
            thumbnailUrl = release.thumbnailUrl,
            videoId = null,
            browseId = Downloads.pageIdFor(release.id),
        )
    }
}

fun deviceFolderIcon(browseId: String): ImageVector? = when (browseId) {
    "local:downloads" -> SfIcons.Download
    "local:all" -> SfIcons.Folder
    CACHE_FOLDER_BROWSE_ID -> SfIcons.Storage
    com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID -> SfIcons.Cloud
    com.bxane.nuvx.data.smb.SmbConfig.BROWSE_ID -> SfIcons.Lan
    else -> null
}

const val SPOTIFY_BROWSE_ID = "app:spotify"

const val CACHE_FOLDER_BROWSE_ID = "local:cache"

@Composable
fun LibraryReplayEntry(
    cards: List<ReplayHeroCard>,
    loading: Boolean,
    holder: String,
    memberSince: String?,
    onOpenReplay: (ReplayStoryPage) -> Unit,
) {
    if (loading) {
        ReplayCardRowSkeleton()
    } else if (cards.isNotEmpty()) {
        ReplayCardRow(
            cards = cards,
            holder = holder,
            memberSince = memberSince,
            onCardClick = onOpenReplay,
            modifier = Modifier.padding(vertical = 6.dp),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        )
    }
}
