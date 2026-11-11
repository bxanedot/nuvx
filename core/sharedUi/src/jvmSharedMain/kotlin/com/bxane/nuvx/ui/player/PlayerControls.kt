package com.bxane.nuvx.ui.player

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import com.bxane.nuvx.sharedui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.model.Song
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
internal fun playbackOriginText(song: Song, playedBy: String?): String =
    playedBy?.let {
        stringResource(Res.string.played_by, it)
    } ?: song.radioName?.let {
        stringResource(Res.string.playing_radio, it)
    } ?: stringResource(
        Res.string.playing_from,
        song.playbackSource ?: song.albumName ?: stringResource(Res.string.queue),
    )

@Composable
internal fun PlaybackOriginCaption(
    text: String,
    onClick: () -> Unit,
    textAlign: TextAlign,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalPlayerChrome.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.55f),
                offset = Offset(0f, 1f),
                blurRadius = 4f,
            ),
        ),
        color = Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier
            .additiveChrome(chrome)
            .clickable(onClick = onClick)
            .padding(contentPadding),
    )
}

internal val SCRUBBER_LABEL_LIFT = 9.dp

@Composable
internal fun PlayerScrubber(
    shown: () -> Float,
    durationMs: Long,
    loading: Boolean,
    mixing: Boolean,
    transitionWindow: ClosedFloatingPointRange<Float>?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    centerLabel: @Composable BoxScope.() -> Unit = {},
) {
    val shown = shown()
    val mixBlend = PlayerSettings.smartMixBlend.collectAsStateWithLifecycle()
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val mixPulse = rememberMixPulse({ mixBlend.value }, enabled = !reduceAnimation)
    Column(Modifier.fillMaxWidth()) {
        ThinSlider(
            value = shown,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            loading = loading,
            mixing = mixing,
            mixPulse = mixPulse,
            transitionWindow = transitionWindow,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = -SCRUBBER_LABEL_LIFT),
        ) {
            val timeColor = chromeWhite(0.55f)
            Row(
                modifier = Modifier.fillMaxWidth().additiveChrome(LocalPlayerChrome.current),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatTime((shown * durationMs).toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = timeColor,
                )
                Text(
                    text = "-" + formatTime(durationMs - (shown * durationMs).toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = timeColor,
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 1f - mixPulse.cover },
            ) {
                centerLabel()
            }
            val mixingShown by remember { derivedStateOf { mixPulse.cover > 0f } }
            if (mixingShown) {
                MixingLabel(mixPulse, Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun MixingLabel(pulse: MixPulse, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.mixing),
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { MixViewRequest.open = true }
            .graphicsLayer {
                alpha = pulse.cover * pulse.alpha(1f, restShare = MIXING_LABEL_REST)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .additiveChrome(LocalPlayerChrome.current),
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Medium,
            fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + BADGE_SIZE_BUMP).sp,
        ),
        color = chromeWhite(0.85f),
        maxLines = 1,
    )
}

private const val MIXING_LABEL_REST = 0.5f

internal const val BADGE_SIZE_BUMP = 0.5f

@Composable
internal fun TransportRow(
    isPlaying: Boolean,
    isLoading: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    compact: Boolean = false,
) {
    val playSize = if (compact) 58.dp else 74.dp
    val playTouch = if (compact) 76.dp else 92.dp
    val skipSize = if (compact) 44.dp else PLAYER_SKIP_ICON_SIZE
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportGlyph(
            icon = Res.drawable.ic_player_previous,
            contentDescription = stringResource(Res.string.widget_previous),
            size = skipSize,
            touchSize = PLAYER_SKIP_TOUCH_SIZE,
            heightScale = PLAYER_SKIP_HEIGHT_SCALE,
            onClick = onPrevious,
            enabled = previousEnabled,
            haptic = Haptic.SkipPrevious,
        )
        if (isLoading) {
            Box(Modifier.size(playTouch), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(if (compact) 30.dp else 38.dp),
                )
            }
        } else {
            TransportGlyph(
                icon = if (isPlaying) Res.drawable.ic_player_pause else Res.drawable.ic_player_play,
                contentDescription = stringResource(if (isPlaying) Res.string.pause else Res.string.play),
                size = playSize,
                touchSize = playTouch,
                onClick = onPlayPause,
                haptic = if (isPlaying) Haptic.Pause else Haptic.Resume,
            )
        }
        TransportGlyph(
            icon = Res.drawable.ic_player_next,
            contentDescription = stringResource(Res.string.widget_next),
            size = skipSize,
            touchSize = PLAYER_SKIP_TOUCH_SIZE,
            heightScale = PLAYER_SKIP_HEIGHT_SCALE,
            onClick = onNext,
            enabled = nextEnabled,
            haptic = Haptic.SkipNext,
        )
    }
}

internal val VOLUME_ROW_HEIGHT = 32.dp

@Composable
internal fun VolumeRow(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val chrome = LocalPlayerChrome.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            SfIcons.VolumeDown,
            contentDescription = null,
            tint = if (chrome) VibrantChromeInk else chromeWhite(0.5f),
            modifier = Modifier.size(20.dp).vibrantChrome(chrome),
        )
        Spacer(Modifier.width(10.dp))
        ThinSlider(
            value = value(),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            idleHeight = 6.dp,
            activeHeight = 10.dp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            SfIcons.VolumeUp,
            contentDescription = null,
            tint = if (chrome) VibrantChromeInk else chromeWhite(0.5f),
            modifier = Modifier.size(20.dp).vibrantChrome(chrome),
        )
    }
}

private const val CIRCLE_DISC_DODGE_GAIN = 2.5f

@Composable
internal fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    haptic: Haptic = Haptic.Tap,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "glyphDisc",
    )
    val chrome = LocalPlayerChrome.current
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .vibrantChrome(chrome)
                .background(
                    if (chrome) {
                        VibrantChromeInk.copy(alpha = (discAlpha * CIRCLE_DISC_DODGE_GAIN).coerceAtMost(1f))
                    } else {
                        Color.White.copy(alpha = discAlpha)
                    },
                ),
        )
        Crossfade(
            targetState = icon,
            animationSpec = tween(durationMillis = 180),
            label = "playerMenuGlyph",
        ) { glyph ->
            Icon(
                imageVector = glyph,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun TransportGlyph(
    icon: DrawableResource,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    touchSize: androidx.compose.ui.unit.Dp = size,
    onClick: () -> Unit,
    enabled: Boolean = true,
    haptic: Haptic = Haptic.Tap,
    heightScale: Float = 1f,
) {
    val haptics = rememberHaptics()
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.3f,
        label = "transportAlpha",
    )
    Box(
        modifier = Modifier
            .size(touchSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier
                .size(size)
                .then(if (heightScale != 1f) Modifier.graphicsLayer { scaleY = heightScale } else Modifier),
        )
    }
}

private val PLAYER_SKIP_ICON_SIZE = 53.dp
private val PLAYER_SKIP_TOUCH_SIZE = 53.dp

private const val PLAYER_SKIP_HEIGHT_SCALE = 0.85f

internal fun Modifier.opensPage(browseId: String?, onOpen: (String) -> Unit): Modifier =
    if (browseId == null) {
        this
    } else {
        clip(LinkPressShape).clickable { onOpen(browseId) }
    }

internal val LinkPressShape = RoundedCornerShape(6.dp)

internal fun Modifier.bleedHorizontally(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = constraints.maxWidth + extra,
        )
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) {
        placeable.place(-(placeable.width - width) / 2, 0)
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(Locale.ROOT, minutes, seconds)
}
