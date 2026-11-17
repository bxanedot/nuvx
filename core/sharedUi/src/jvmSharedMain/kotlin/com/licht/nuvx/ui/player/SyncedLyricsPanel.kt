package com.licht.nuvx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.licht.nuvx.data.lyrics.LyricLine
import com.licht.nuvx.playback.PlaybackPosition

@Composable
fun SyncedLyricsPanel(
    lines: List<LyricLine>,
    trackKey: String,
    position: PlaybackPosition,
    looking: Boolean,
    isPlaying: Boolean,
    onSeekToLine: (Long) -> Unit,
    centerFocus: Boolean = false,
    modifier: Modifier = Modifier,
) {
    LyricsPanel(
        lines = lines,
        trackKey = trackKey,
        playhead = rememberLyricPlayhead(position),
        looking = looking,
        isPlaying = isPlaying,
        onSeekToLine = onSeekToLine,
        controlsOpen = true,
        onRevealControls = {},
        onHideControls = {},
        centerFocus = centerFocus,
        modifier = modifier,
    )
}
