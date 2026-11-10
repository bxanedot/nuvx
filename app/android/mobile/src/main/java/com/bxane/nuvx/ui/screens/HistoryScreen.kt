package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.R

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.model.playingRowMatcher
import com.bxane.nuvx.ui.components.MessageState
import com.bxane.nuvx.ui.components.ROW_DIVIDER_INSET
import com.bxane.nuvx.ui.components.SongRow
import com.bxane.nuvx.ui.components.songListSkeleton

@Composable
fun HistoryScreen(
    state: UiState<List<Song>>,
    listState: LazyListState,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        when (state) {
            is UiState.Loading -> songListSkeleton(count = 10, keyPrefix = "skeleton:history")

            is UiState.Error -> item(key = "history:message") {
                MessageState(
                    message = state.message,
                    actionLabel = stringResource(R.string.try_again),
                    onAction = onRetry,
                )
            }

            is UiState.Success -> {
                val songs = state.data
                val isCurrentRow = playingRowMatcher(songs, currentSong)
                itemsIndexed(songs) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { onSongClick(songs, index) },
                        onLongPress = { onSongLongPress(song) },
                        onSwipeToQueue = { onSongSwipe(song) },
                        isCurrent = isCurrentRow(song),
                        isPlaying = isCurrentRow(song) && isPlaying,
                        searchPlayingStyle = true,
                    )
                    if (index < songs.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    songs: List<Song>,
    row: @Composable (Int, Song) -> Unit,
) = items(
    count = songs.size,
    key = { "${songs[it].videoId}:$it" },
) { index -> row(index, songs[index]) }
