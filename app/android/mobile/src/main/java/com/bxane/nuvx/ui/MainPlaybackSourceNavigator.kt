package com.bxane.nuvx.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bxane.nuvx.data.model.PlaybackSourceType
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.ui.navigation.AppNavigator
import com.bxane.nuvx.ui.navigation.AppRoute
import com.bxane.nuvx.ui.replay.ReplayStoryPage

internal class MainPlaybackSourceNavigator(
    private val context: Context,
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
    fun open(song: Song) {
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
}
