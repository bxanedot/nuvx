package com.bxane.nuvx.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.bxane.nuvx.R
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.PlaybackSourceType
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.ui.navigation.AppNavigator
import com.bxane.nuvx.ui.navigation.AppRoute
import com.bxane.nuvx.ui.replay.ReplayStoryPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MainNowPlayingNavigator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModel: MainViewModel,
    private val navigator: AppNavigator,
    private val queueLabel: String,
    private val dismissPlayer: () -> Unit,
    private val selectTab: (Int) -> Unit,
    private val setReplayLandingPage: (ReplayStoryPage) -> Unit,
    private val homeTab: Int,
    private val searchTab: Int,
    private val exploreTab: Int,
) {
    fun openSource(song: Song) {
        val sourceType = song.playbackSourceType ?: PlaybackSourceType.QUEUE
        val sourceTitle = song.playbackSource
            ?: song.albumName
            ?: queueLabel
        val sourceId = song.playbackSourceId

        dismissPlayer()
        navigator.clear()

        when (sourceType) {
            PlaybackSourceType.BROWSE -> {
                val id = sourceId ?: return
                viewModel.closeMoodGenre()
                viewModel.openDetail(id, sourceTitle)
            }
            PlaybackSourceType.HOME -> {
                viewModel.closeMoodGenre()
                selectTab(homeTab)
            }
            PlaybackSourceType.SEARCH -> {
                viewModel.closeMoodGenre()
                selectTab(searchTab)
            }
            PlaybackSourceType.HISTORY -> {
                navigator.push(AppRoute.History)
                viewModel.loadHistory()
            }
            PlaybackSourceType.REPLAY -> {
                setReplayLandingPage(ReplayStoryPage.INTRO)
                navigator.push(AppRoute.Replay)
            }
            PlaybackSourceType.EXPLORE -> selectTab(exploreTab)
            PlaybackSourceType.SHARED_LINK -> {
                val id = sourceId ?: return
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://music.youtube.com/watch?v=$id"),
                    ),
                )
            }
            PlaybackSourceType.QUEUE -> Unit
        }
    }

    fun openAlbum(song: Song, albumId: String) {
        dismissPlayer()
        viewModel.openDetail(
            albumId,
            song.albumName ?: song.title,
            song.artist,
            song.thumbnailUrl,
            BrowseType.ALBUM,
        )
    }

    fun openArtist(artistId: String?, name: String) {
        if (artistId != null) {
            dismissPlayer()
            viewModel.openDetail(
                artistId,
                name,
                context.getString(R.string.artist),
                null,
                BrowseType.ARTIST,
            )
        } else {
            scope.launch {
                val found = YtMusicRepository.findArtistPageId(name).getOrNull()
                if (found != null) {
                    dismissPlayer()
                    viewModel.openDetail(
                        found,
                        name,
                        context.getString(R.string.artist),
                        null,
                        BrowseType.ARTIST,
                    )
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.couldnt_find, name),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}
