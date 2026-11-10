package com.bxane.nuvx.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.playback.PlaybackPosition
import com.bxane.nuvx.sharedui.resources.*
import com.bxane.nuvx.ui.LyricsProviderState
import com.bxane.nuvx.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun LyricsSidePanel(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    lyricsUnavailable: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val lyricsOffsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val lyricsPlayhead = rememberLyricPlayhead(position)
    val seekToLyric: (Long) -> Unit = { lineTimeMs ->
        onSeek(adjustedLyricsSeekTarget(lineTimeMs, lyricsOffsetMs))
    }
    val lyricsLoadingLines = stringArrayResource(Res.array.lyrics_loading_lines)
    val lyricsLoadingText = remember(song.videoId, lyricsLoadingLines) {
        lyricsLoadingLines.randomOrNull().orEmpty()
    }
    val lyricsTranslation = rememberLyricsTranslation(
        trackId = song.videoId,
        lyrics = lyrics,
        lyricsSource = lyricsSource,
        lyricsUnavailable = lyricsUnavailable,
        loadingText = lyricsLoadingText,
        haptics = haptics,
    )
    val haze = remember { HazeState() }
    var showLyricsProviders by remember { mutableStateOf(false) }

    Box(modifier) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            LandscapeLyricsPane(
                hasLyrics = lyricsTranslation.displayedLyrics.isNotEmpty(),
                placeholder = if (lyricsUnavailable) {
                    stringResource(Res.string.lyrics_not_available)
                } else {
                    lyricsLoadingText
                },
                status = lyricsTranslation.status,
                onStatusClick = { showLyricsProviders = true },
                romanizationToggle = {
                    RomanizationToggleButton(
                        state = lyricsTranslation.romanizationState,
                        showingRomanization = lyricsTranslation.showingRomanization,
                        enabled = !lyrics.isNullOrEmpty(),
                        onClick = lyricsTranslation.toggleRomanization,
                    )
                },
                translationToggle = {
                    TranslationToggleButton(
                        state = lyricsTranslation.translationState,
                        showingTranslation = lyricsTranslation.showingTranslation,
                        enabled = !lyrics.isNullOrEmpty(),
                        onClick = lyricsTranslation.toggleTranslation,
                    )
                },
            ) { panelModifier ->
                LyricsTranslationMotion(
                    trigger = lyricsTranslation.transition,
                    reduceMotion = lyricsTranslation.reduceMotion,
                    modifier = panelModifier,
                ) { particleProgress ->
                    LyricsPanel(
                        lines = lyrics.orEmpty(),
                        subLines = lyricsTranslation.subLines,
                        trackKey = song.videoId,
                        playhead = lyricsPlayhead,
                        looking = !lyricsUnavailable,
                        isPlaying = isPlaying && position.advancing,
                        onSeekToLine = seekToLyric,
                        controlsOpen = true,
                        onRevealControls = {},
                        onHideControls = {},
                        translationProgress = particleProgress,
                        centerFocus = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (showLyricsProviders) {
            LyricsProviderSheet(
                hazeState = haze,
                currentSource = lyricsSource,
                states = lyricsProviderStates,
                onSelect = onSelectLyricsProvider,
                onDismiss = { showLyricsProviders = false },
            )
        }
    }
}

@Composable
fun QueueSidePanel(
    queue: List<Song>,
    queueIndex: Int,
    autoplayEnabled: Boolean,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InlineQueue(
        queue = queue,
        currentIndex = queueIndex,
        autoplayEnabled = autoplayEnabled,
        controlsLocked = rememberControlsLocked(),
        onJumpTo = onJumpTo,
        onRemove = onRemove,
        onMove = onMove,
        onClear = onClear,
        modifier = modifier,
    )
}
