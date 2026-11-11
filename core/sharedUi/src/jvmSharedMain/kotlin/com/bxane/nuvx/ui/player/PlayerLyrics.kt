package com.bxane.nuvx.ui.player

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.sharedui.resources.*
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics

private val CONTROLS_SCROLL_SLOP = 20.dp


@Composable
internal fun LyricsStatusWithChange(
    status: String,
    onStatusClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier.additiveChrome(LocalPlayerChrome.current),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.titleMedium,
            color = chromeWhite(0.55f),
            textDecoration = if (onStatusClick != null) TextDecoration.Underline else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (onStatusClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptics.play(Haptic.Select)
                            onStatusClick()
                        }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

@Composable
internal fun TranslationToggleButton(
    state: LyricsTranslationUiState,
    showingTranslation: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val active = showingTranslation || state is LyricsTranslationUiState.Loading
    val chrome = LocalPlayerChrome.current
    val tint = when {
        !enabled || state is LyricsTranslationUiState.SameLanguage -> chromeWhite(0.42f)
        active -> Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 1f)
        else -> chromeWhite(0.78f)
    }
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "translateDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .additiveChrome(chrome)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state is LyricsTranslationUiState.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = tint,
                strokeWidth = 1.7.dp,
            )
        } else {
            Icon(
                imageVector = SfIcons.Translate,
                contentDescription = stringResource(
                    if (showingTranslation) Res.string.show_original_lyrics
                    else Res.string.translate_lyrics,
                ),
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
internal fun RomanizationToggleButton(
    state: LyricsTranslationUiState,
    showingRomanization: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val active = showingRomanization || state is LyricsTranslationUiState.Loading
    val chrome = LocalPlayerChrome.current
    val tint = when {
        !enabled || state is LyricsTranslationUiState.SameLanguage -> chromeWhite(0.42f)
        active -> Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 1f)
        else -> chromeWhite(0.78f)
    }
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "romanizeDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .additiveChrome(chrome)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state is LyricsTranslationUiState.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = tint,
                strokeWidth = 1.7.dp,
            )
        } else {
            Icon(
                imageVector = SfIcons.Language,
                contentDescription = stringResource(
                    if (showingRomanization) Res.string.show_original_lyrics
                    else Res.string.romanize_lyrics,
                ),
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
internal fun rememberPlayerControlsOnScroll(
    enabled: Boolean = true,
    onReveal: () -> Unit,
    onHide: () -> Unit,
): NestedScrollConnection {
    val controlsSlopPx = with(LocalDensity.current) { CONTROLS_SCROLL_SLOP.toPx() }
    val revealControls by rememberUpdatedState(onReveal)
    val hideControls by rememberUpdatedState(onHide)
    return remember(enabled, controlsSlopPx) {
        object : NestedScrollConnection {
            private var travel = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (enabled && source == NestedScrollSource.UserInput && available.y != 0f) {
                    if (travel != 0f && (travel > 0f) != (available.y > 0f)) travel = 0f
                    travel += available.y
                    if (travel <= -controlsSlopPx) {
                        travel = 0f
                        hideControls()
                    } else if (travel >= controlsSlopPx) {
                        travel = 0f
                        revealControls()
                    }
                }
                return Offset.Zero
            }
        }
    }
}
