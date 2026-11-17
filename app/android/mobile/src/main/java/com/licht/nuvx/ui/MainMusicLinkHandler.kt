package com.licht.nuvx.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.media3.session.MediaController
import com.licht.nuvx.R
import com.licht.nuvx.data.LocalMediaRepository
import com.licht.nuvx.data.YtMusicRepository
import com.licht.nuvx.data.model.PlaybackSourceType
import com.licht.nuvx.data.model.SearchFilter
import com.licht.nuvx.data.model.SearchResult
import com.licht.nuvx.playback.LinkRequest
import com.licht.nuvx.playback.MusicLink
import com.licht.nuvx.playback.QueueSource
import com.licht.nuvx.ui.navigation.AppNavigator

internal class MainMusicLinkHandler(
    private val context: Context,
    private val viewModel: MainViewModel,
    private val playbackStarter: MainPlaybackStarter,
    private val dismissPlayer: () -> Unit,
    private val navigator: AppNavigator,
    private val searchTab: Int,
    private val sharedLinkLabel: String,
    private val searchLabel: String,
    private val openedFileLabel: String,
    private val selectTab: (Int) -> Unit,
    private val setShowNowPlaying: (Boolean) -> Unit,
) {
    suspend fun handle(request: LinkRequest, session: MediaController) {
        when (request) {
            is LinkRequest.Track -> {
                val song = YtMusicRepository.trackLinks(request.videoId).getOrNull()
                if (song == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.couldnt_open_link),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    playbackStarter.playRadio(
                        song,
                        QueueSource(sharedLinkLabel, PlaybackSourceType.SHARED_LINK, song.videoId),
                    )
                }
            }
            is LinkRequest.Page -> {
                dismissPlayer()
                viewModel.openDetail(request.browseId, title = "")
            }
            is LinkRequest.Search -> {
                val songs = if (!request.play) null else {
                    YtMusicRepository.search(request.query, SearchFilter.SONGS).getOrNull()
                        ?.filterIsInstance<SearchResult.Track>()
                }
                val top = songs?.firstOrNull()?.song
                if (top != null) {
                    playbackStarter.playRadio(top, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
                } else {
                    dismissPlayer()
                    navigator.clear()
                    selectTab(searchTab)
                    viewModel.searchFor(request.query)
                }
            }
            LinkRequest.Resume -> if (session.mediaItemCount > 0) session.play()
            is LinkRequest.LocalFile -> {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        request.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val song = LocalMediaRepository.songFromUri(context, request.uri)
                playbackStarter.playFrom(
                    listOf(song),
                    0,
                    QueueSource(openedFileLabel, PlaybackSourceType.SHARED_LINK, song.localUri),
                )
                setShowNowPlaying(true)
            }
        }
        MusicLink.handled()
    }
}
