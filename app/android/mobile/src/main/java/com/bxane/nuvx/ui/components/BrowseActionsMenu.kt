package com.bxane.nuvx.ui.components

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UserPlaylist
import com.bxane.nuvx.download.DownloadState
import com.bxane.nuvx.download.Downloads

data class BrowseTarget(
    val browseId: String?,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String? = null,
    val type: BrowseType = BrowseType.OTHER,
    val songs: List<Song> = emptyList(),
    val playlist: UserPlaylist? = null,
    val fromCard: Boolean = true,
    val downloadId: String? = null,
)

@Composable
fun BrowseActionsMenu(
    target: BrowseTarget,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
    onReorder: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
    onRenameInSheet: (() -> Unit)? = null,
    startRenaming: Boolean = false,
) {
    var renaming by remember { mutableStateOf(startRenaming) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingDeleteDownload by remember { mutableStateOf(false) }

    val playlist = target.playlist
    if (renaming && playlist != null && onRename != null) {
        RenamePlaylistForm(
            playlist = playlist,
            onBack = { renaming = false },
            onRename = onRename,
            modifier = modifier,
        )
        return
    }

    CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            onPlay?.let { ActionRow(SfIcons.Play, stringResource(R.string.play), onClick = it) }
            onShuffle?.let { ActionRow(SfIcons.Shuffle, stringResource(R.string.shuffle), onClick = it) }
            ActionRow(
                SfIcons.PlayNext,
                stringResource(R.string.play_next),
                onClick = onPlayNext,
            )
            ActionRow(
                SfIcons.PlayLast,
                stringResource(R.string.add_to_queue),
                onClick = onAddToQueue,
            )
            onDownloadAll?.let { download ->
                val active by Downloads.active.collectAsStateWithLifecycle()
                val requested by Downloads.requested.collectAsStateWithLifecycle()
                val saved by Downloads.saved.collectAsStateWithLifecycle()
                val waiting = target.browseId?.let { requested[it] }.orEmpty().any { id ->
                    when (active[id]) {
                        is DownloadState.Queued, is DownloadState.Running -> true
                        else -> false
                    }
                }
                val ids = remember(target.songs) { target.songs.mapTo(HashSet()) { it.videoId } }
                val downloaded = !waiting && ids.isNotEmpty() && ids.all { it in saved }
                ActionRow(
                    icon = when {
                        waiting -> SfIcons.Clock
                        downloaded -> SfIcons.Downloaded
                        else -> SfIcons.Download
                    },
                    label = stringResource(R.string.download_all),
                    value = when {
                        waiting -> stringResource(R.string.downloading)
                        downloaded -> stringResource(R.string.downloaded)
                        else -> null
                    },
                    onClick = download,
                )
            }
            onOpen?.let {
                ActionRow(
                    SfIcons.Open,
                    target.type.localizedOpenLabel(),
                    onClick = it,
                )
            }
            onShare?.let {
                ActionRow(SfIcons.Share, stringResource(R.string.share), onClick = it)
            }
            onTogglePin?.let {
                ActionRow(
                    SfIcons.Pin,
                    stringResource(if (isPinned) R.string.unpin else R.string.pin),
                    onClick = it,
                )
            }
            if (onRename != null) {
                ActionRow(SfIcons.Pencil, stringResource(R.string.rename)) {
                    if (onRenameInSheet != null) {
                        onRenameInSheet()
                    } else {
                        renaming = true
                    }
                }
            }
            onReorder?.let {
                ActionRow(SfIcons.Reorder, stringResource(R.string.reorder_songs), onClick = it)
            }
            if (onDelete != null) {
                if (confirmingDelete) {
                    ActionRow(
                        icon = SfIcons.Trash,
                        label = stringResource(R.string.delete_playlist_confirmation, target.title),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onDelete,
                    )
                } else {
                    ActionRow(SfIcons.Trash, stringResource(R.string.delete_playlist)) { confirmingDelete = true }
                }
            }
            if (onDeleteDownload != null) {
                if (confirmingDeleteDownload) {
                    ActionRow(
                        icon = SfIcons.Trash,
                        label = stringResource(R.string.delete_download_confirmation, target.title),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onDeleteDownload,
                    )
                } else {
                    ActionRow(SfIcons.Trash, stringResource(R.string.delete_download)) {
                        confirmingDeleteDownload = true
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseType.localizedOpenLabel(): String = when (this) {
    BrowseType.ALBUM -> stringResource(R.string.open_album)
    BrowseType.ARTIST -> stringResource(R.string.open_artist)
    BrowseType.PLAYLIST -> stringResource(R.string.open_playlist)
    BrowseType.OTHER -> stringResource(R.string.open)
}
