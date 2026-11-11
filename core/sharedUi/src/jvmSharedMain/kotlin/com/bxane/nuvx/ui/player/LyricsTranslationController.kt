package com.bxane.nuvx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.LocaleTags
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.lyrics.translationLanguageName
import com.bxane.nuvx.sharedui.resources.*
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.Haptics
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal sealed interface LyricsTranslationUiState {
    data object Idle : LyricsTranslationUiState
    data object Loading : LyricsTranslationUiState
    data class Ready(val lines: List<LyricLine>) : LyricsTranslationUiState
    data object SameLanguage : LyricsTranslationUiState
}

internal enum class LyricsDisplayMode { Original, Romanized, Translated }

internal class LyricsTranslationUi(
    val displayedLyrics: List<LyricLine>,
    val subLines: List<LyricLine>?,
    val translationState: LyricsTranslationUiState,
    val romanizationState: LyricsTranslationUiState,
    val showingTranslation: Boolean,
    val showingRomanization: Boolean,
    val transition: Int,
    val reduceMotion: Boolean,
    val status: String,
    val toggleTranslation: () -> Unit,
    val toggleRomanization: () -> Unit,
)

@Composable
internal fun rememberLyricsTranslation(
    trackId: String,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsUnavailable: Boolean,
    loadingText: String,
    haptics: Haptics,
): LyricsTranslationUi {
    val reduceTranslationMotion by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val configuredLocale = appLanguageTag()
    val preferredTranslation by PlayerSettings.translationLanguage.collectAsStateWithLifecycle()
    val translationLanguage = remember(configuredLocale, preferredTranslation) {
        preferredTranslation.ifBlank {
            if (LocaleTags.isChineseTag(configuredLocale)) {
                LocaleTags.translationWireTarget(configuredLocale)
            } else {
                Locale.forLanguageTag(configuredLocale).language.ifBlank { "en" }
            }
        }
    }
    val translationLanguageName = remember(configuredLocale, translationLanguage) {
        translationLanguageName(translationLanguage, Locale.forLanguageTag(configuredLocale))
    }
    val alreadyInLanguageMessage = stringResource(Res.string.lyrics_already_in_language, translationLanguageName)
    val translationUnavailableMessage = stringResource(Res.string.lyrics_translation_unavailable)
    val alreadyRomanizedMessage = stringResource(Res.string.lyrics_already_romanized)
    val romanizationUnavailableMessage = stringResource(Res.string.lyrics_romanization_unavailable)
    var translationState by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<LyricsTranslationUiState>(LyricsTranslationUiState.Idle)
    }
    var romanizationState by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<LyricsTranslationUiState>(LyricsTranslationUiState.Idle)
    }
    var lyricsDisplayMode by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf(LyricsDisplayMode.Original)
    }
    val showingTranslation = lyricsDisplayMode == LyricsDisplayMode.Translated
    val showingRomanization = lyricsDisplayMode == LyricsDisplayMode.Romanized
    var translationTransition by remember(trackId) { mutableIntStateOf(0) }
    var translationJob by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<Job?>(null)
    }
    var romanizationJob by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<Job?>(null)
    }
    DisposableEffect(trackId, translationLanguage, lyrics) {
        onDispose {
            translationJob?.cancel()
            romanizationJob?.cancel()
        }
    }
    val displayedLyrics = when (lyricsDisplayMode) {
        LyricsDisplayMode.Translated ->
            (translationState as? LyricsTranslationUiState.Ready)?.lines ?: lyrics.orEmpty()
        LyricsDisplayMode.Romanized ->
            (romanizationState as? LyricsTranslationUiState.Ready)?.lines ?: lyrics.orEmpty()
        LyricsDisplayMode.Original -> lyrics.orEmpty()
    }
    val lyricsSubLines = when (lyricsDisplayMode) {
        LyricsDisplayMode.Translated -> (translationState as? LyricsTranslationUiState.Ready)?.lines
        LyricsDisplayMode.Romanized -> (romanizationState as? LyricsTranslationUiState.Ready)?.lines
        LyricsDisplayMode.Original -> null
    }
    val translationScope = rememberCoroutineScope()
    val toggleTranslation: () -> Unit = toggleTranslation@{
        when (val state = translationState) {
            is LyricsTranslationUiState.Ready -> {
                lyricsDisplayMode = if (showingTranslation) {
                    LyricsDisplayMode.Original
                } else {
                    LyricsDisplayMode.Translated
                }
                translationTransition++
                haptics.play(Haptic.Select)
            }
            LyricsTranslationUiState.Loading -> Unit
            LyricsTranslationUiState.SameLanguage -> {
                haptics.play(Haptic.Tap)
                PlayerPlatform.host.showMessage(alreadyInLanguageMessage)
            }
            LyricsTranslationUiState.Idle -> {
                val source = lyrics.orEmpty()
                if (source.isEmpty()) return@toggleTranslation
                haptics.play(Haptic.Tap)
                translationState = LyricsTranslationUiState.Loading
                romanizationJob?.cancel()
                if (romanizationState is LyricsTranslationUiState.Loading) {
                    romanizationState = LyricsTranslationUiState.Idle
                }
                translationJob?.cancel()
                translationJob = translationScope.launch {
                    when (
                        val result = PlayerPlatform.host.translateLyrics(
                            trackId = trackId,
                            lines = source,
                            targetLanguageTag = translationLanguage,
                        )
                    ) {
                        is LyricsTranslationResult.Translated -> {
                            translationState = LyricsTranslationUiState.Ready(result.lines)
                            lyricsDisplayMode = LyricsDisplayMode.Translated
                            translationTransition++
                            haptics.play(Haptic.ToggleOn)
                        }
                        is LyricsTranslationResult.SameLanguage -> {
                            translationState = LyricsTranslationUiState.SameLanguage
                            PlayerPlatform.host.showMessage(alreadyInLanguageMessage)
                        }
                        LyricsTranslationResult.Unavailable -> {
                            translationState = LyricsTranslationUiState.Idle
                            PlayerPlatform.host.showMessage(translationUnavailableMessage)
                        }
                    }
                }
            }
        }
    }
    val toggleRomanization: () -> Unit = toggleRomanization@{
        when (val state = romanizationState) {
            is LyricsTranslationUiState.Ready -> {
                lyricsDisplayMode = if (showingRomanization) {
                    LyricsDisplayMode.Original
                } else {
                    LyricsDisplayMode.Romanized
                }
                translationTransition++
                haptics.play(Haptic.Select)
            }
            LyricsTranslationUiState.Loading -> Unit
            LyricsTranslationUiState.SameLanguage -> {
                haptics.play(Haptic.Tap)
                PlayerPlatform.host.showMessage(alreadyRomanizedMessage)
            }
            LyricsTranslationUiState.Idle -> {
                val source = lyrics.orEmpty()
                if (source.isEmpty()) return@toggleRomanization
                haptics.play(Haptic.Tap)
                romanizationState = LyricsTranslationUiState.Loading
                translationJob?.cancel()
                if (translationState is LyricsTranslationUiState.Loading) {
                    translationState = LyricsTranslationUiState.Idle
                }
                romanizationJob?.cancel()
                romanizationJob = translationScope.launch {
                    when (
                        val result = PlayerPlatform.host.romanizeLyrics(
                            trackId = trackId,
                            lines = source,
                            targetLanguageTag = translationLanguage,
                        )
                    ) {
                        is LyricsRomanizationResult.Romanized -> {
                            romanizationState = LyricsTranslationUiState.Ready(result.lines)
                            lyricsDisplayMode = LyricsDisplayMode.Romanized
                            translationTransition++
                            haptics.play(Haptic.ToggleOn)
                        }
                        LyricsRomanizationResult.AlreadyRomanized -> {
                            romanizationState = LyricsTranslationUiState.SameLanguage
                            PlayerPlatform.host.showMessage(alreadyRomanizedMessage)
                        }
                        LyricsRomanizationResult.Unavailable -> {
                            romanizationState = LyricsTranslationUiState.Idle
                            PlayerPlatform.host.showMessage(romanizationUnavailableMessage)
                        }
                    }
                }
            }
        }
    }
    val status = when {
        translationState is LyricsTranslationUiState.Loading ->
            stringResource(Res.string.translating_lyrics_to, translationLanguageName)
        romanizationState is LyricsTranslationUiState.Loading ->
            stringResource(Res.string.romanizing_lyrics)
        showingTranslation ->
            stringResource(Res.string.lyrics_translated_to, translationLanguageName)
        showingRomanization ->
            stringResource(Res.string.lyrics_romanized)
        translationState is LyricsTranslationUiState.SameLanguage ->
            stringResource(Res.string.lyrics_already_in_language, translationLanguageName)
        romanizationState is LyricsTranslationUiState.SameLanguage ->
            stringResource(Res.string.lyrics_already_romanized)
        lyricsSource != null -> stringResource(Res.string.lyrics_by, lyricsSource.label)
        lyricsUnavailable -> stringResource(Res.string.no_lyrics_found)
        lyrics.isNullOrEmpty() -> loadingText
        else -> stringResource(Res.string.lyrics_saved_with_download)
    }

    return LyricsTranslationUi(
        displayedLyrics = displayedLyrics,
        subLines = lyricsSubLines,
        translationState = translationState,
        romanizationState = romanizationState,
        showingTranslation = showingTranslation,
        showingRomanization = showingRomanization,
        transition = translationTransition,
        reduceMotion = reduceTranslationMotion,
        status = status,
        toggleTranslation = toggleTranslation,
        toggleRomanization = toggleRomanization,
    )
}
