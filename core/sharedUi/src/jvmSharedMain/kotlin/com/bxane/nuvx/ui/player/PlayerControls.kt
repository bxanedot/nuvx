package com.bxane.nuvx.ui.player

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import com.bxane.nuvx.sharedui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.listentogether.PartyMember
import com.bxane.nuvx.data.model.Song
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val SHUFFLE_TAP_WINDOW_MS = 400L
private const val AUTOPLAY_TAP_WINDOW_MS = 700L

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

@Composable
internal fun PlayerActionRow(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    autoplayEnabled: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onOpenOutput: () -> Unit,
    onListenTogether: () -> Unit,
    onOpenListenTogetherMembers: () -> Unit,
) {
    val showPartyButton by PlayerSettings.showListenTogetherButton.collectAsStateWithLifecycle()
    val inParty = rememberPartyBadge().inParty
    val compact = !queueOpen && !showPartyButton && !inParty
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val edgeInset = ((maxWidth - BOTTOM_ACTION_SIZE * 2 - pillWidth(3)) / 4).coerceAtLeast(0.dp)
        val compactInset = ((maxWidth - BOTTOM_ACTION_SIZE * 2 - pillWidth(2)) / 4).coerceAtLeast(0.dp)
        val closeIn = compactInset - edgeInset
        val compactness = animateFloatAsState(
            targetValue = if (compact) 1f else 0f,
            animationSpec = tween(220),
            label = "actionRowCompact",
        )
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = edgeInset),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((closeIn.toPx() * compactness.value).roundToInt(), 0) },
            ) {
                BottomGlyph(
                    icon = SfIcons.LyricsQuote,
                    contentDescription = stringResource(if (lyricsOpen) Res.string.close_lyrics else Res.string.open_lyrics),
                    onClick = onToggleLyrics,
                    highlighted = lyricsOpen,
                    vibrant = true,
                )
            }
            AnimatedContent(
                targetState = queueOpen,
                transitionSpec = {
                    (fadeIn(tween(180, delayMillis = 140)) togetherWith fadeOut(tween(140)))
                        .using(SizeTransform(clip = false) { _, _ -> tween(220) })
                },
                label = "playerBottomPill",
            ) { showQueueModes ->
                if (showQueueModes) {
                    Pill {
                        PillSegment(
                            icon = SfIcons.Shuffle,
                            contentDescription = stringResource(
                                if (shuffleEnabled) Res.string.shuffle_on else Res.string.shuffle_off,
                            ),
                            onClick = onToggleShuffle,
                            highlighted = shuffleEnabled,
                            haptic = if (shuffleEnabled) Haptic.ToggleOff else Haptic.ToggleOn,
                            tapWindowMs = SHUFFLE_TAP_WINDOW_MS,
                        )
                        PillDivider()
                        PillSegment(
                            icon = if (repeatMode == RepeatModes.ONE) null else SfIcons.Repeat,
                            label = if (repeatMode == RepeatModes.ONE) "1" else null,
                            contentDescription = when (repeatMode) {
                                RepeatModes.ONE -> stringResource(Res.string.repeat_one)
                                RepeatModes.ALL -> stringResource(Res.string.repeat_all)
                                else -> stringResource(Res.string.repeat_off)
                            },
                            onClick = onCycleRepeat,
                            highlighted = repeatMode != RepeatModes.OFF,
                            haptic = when (repeatMode) {
                                RepeatModes.OFF -> Haptic.ToggleOn
                                RepeatModes.ONE -> Haptic.ToggleOff
                                else -> Haptic.Select
                            },
                        )
                        PillDivider()
                        PillSegment(
                            icon = SfIcons.Infinity,
                            contentDescription = stringResource(
                                if (autoplayEnabled) Res.string.autoplay_on else Res.string.autoplay_off,
                            ),
                            onClick = onToggleAutoplay,
                            highlighted = autoplayEnabled,
                            haptic = if (autoplayEnabled) Haptic.ToggleOff else Haptic.ToggleOn,
                            tapWindowMs = AUTOPLAY_TAP_WINDOW_MS,
                        )
                    }
                } else {
                    OutputPartyPill(
                        onOutput = onOpenOutput,
                        onParty = onListenTogether,
                        onOpenMembers = onOpenListenTogetherMembers,
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset { IntOffset(-(closeIn.toPx() * compactness.value).roundToInt(), 0) },
            ) {
                BottomGlyph(
                    icon = SfIcons.QueueMusic,
                    contentDescription = stringResource(Res.string.up_next),
                    onClick = onToggleQueue,
                    highlighted = queueOpen,
                    haptic = if (queueOpen) Haptic.Tap else Haptic.Expand,
                    vibrant = true,
                )
            }
        }
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

private val BOTTOM_ACTION_SIZE = 44.dp

private val BOTTOM_ICON_SIZE = 26.dp

private val BOTTOM_ACTIVE_TILE_SIZE = 35.dp
private val BOTTOM_ACTIVE_TILE_CORNER = 7.dp
private const val BOTTOM_ACTIVE_TILE_ALPHA = 0.62f

private val PILL_SEGMENT_WIDTH = 64.dp

private val PILL_OUTPUT_SIZE = 23.dp
private val PILL_PARTY_SIZE = 22.dp

private val PILL_OUTPUT_WIDE_SIZE = 21.dp

private val PILL_OUTPUT_TALL_SIZE = 17.dp

private val PILL_OUTPUT_BUDS_SIZE = 29.dp

private val PILL_ICON_SIZE = 24.dp

private fun pillWidth(segments: Int): Dp =
    PILL_SEGMENT_WIDTH * segments + 1.dp * (segments - 1)

@Composable
private fun Pill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(BOTTOM_ACTION_SIZE)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PillDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.20f)),
    )
}

@Composable
private fun OutputPartyPill(
    onOutput: () -> Unit,
    onParty: () -> Unit,
    onOpenMembers: () -> Unit,
) {
    val badge = rememberPartyBadge()
    val shown by PlayerSettings.showListenTogetherButton.collectAsStateWithLifecycle()
    val output = rememberOutputGlyph()
    if (!shown && !badge.inParty) {
        BottomGlyph(
            icon = output.icon,
            iconSize = output.size * (BOTTOM_ICON_SIZE / PILL_OUTPUT_SIZE),
            contentDescription = stringResource(Res.string.audio_output),
            onClick = onOutput,
            vibrant = true,
        )
        return
    }
    Pill {
        PillSegment(
            icon = output.icon,
            iconSize = output.size,
            contentDescription = stringResource(Res.string.audio_output),
            onClick = onOutput,
            vibrant = true,
        )
        PillDivider()
        PillSegment(
            icon = SfIcons.Person,
            iconSize = PILL_PARTY_SIZE,
            contentDescription = if (badge.inParty) {
                stringResource(Res.string.listen_together_open_count, badge.members)
            } else {
                stringResource(Res.string.listen_together_open)
            },
            onClick = if (badge.inParty) onOpenMembers else onParty,
            highlighted = badge.inParty,
            trailingLabel = badge.members.takeIf { badge.inParty }?.toString(),
        )
    }
}

@Composable
private fun rememberOutputGlyph(): OutputGlyph {
    val cast by PlayerPlatform.host.castState.collectAsStateWithLifecycle()
    val watch by PlayerPlatform.host.watchOutput.collectAsStateWithLifecycle()
    val active = rememberAudioOutputs().firstOrNull { it.isActive }
    return when {
        cast.connectedName != null -> OutputGlyph(SfIcons.CastConnected, PILL_OUTPUT_WIDE_SIZE)
        watch.active -> OutputGlyph(SfIcons.Watch, PILL_OUTPUT_TALL_SIZE)
        else -> when (active?.kind) {
            null, AudioOutputKind.PHONE -> OutputGlyph(SfIcons.Cast, PILL_OUTPUT_WIDE_SIZE)
            AudioOutputKind.BLUETOOTH -> when (active.form) {
                BluetoothForm.BUDS -> OutputGlyph(vectorResource(Res.drawable.ic_output_buds), PILL_OUTPUT_BUDS_SIZE)
                BluetoothForm.SPEAKER -> OutputGlyph(vectorResource(Res.drawable.ic_output_speaker))
                BluetoothForm.CAR -> OutputGlyph(SfIcons.DirectionsCar)
                BluetoothForm.HEADPHONES, BluetoothForm.UNKNOWN -> OutputGlyph(SfIcons.Headphones)
            }
            AudioOutputKind.WIRED, AudioOutputKind.USB -> OutputGlyph(SfIcons.Headphones)
            AudioOutputKind.HDMI -> OutputGlyph(SfIcons.Tv, PILL_OUTPUT_WIDE_SIZE)
            AudioOutputKind.OTHER -> OutputGlyph(vectorResource(Res.drawable.ic_output_speaker))
        }
    }
}

private data class OutputGlyph(val icon: ImageVector, val size: Dp = PILL_OUTPUT_SIZE)

@Composable
private fun PillSegment(
    contentDescription: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    iconSize: Dp = PILL_ICON_SIZE,
    label: String? = null,
    trailingLabel: String? = null,
    highlighted: Boolean = false,
    haptic: Haptic = Haptic.Tap,
    loading: Boolean = false,
    tapWindowMs: Long = 0L,
    width: Dp = PILL_SEGMENT_WIDTH,
    vibrant: Boolean = false,
) {
    val haptics = rememberHaptics()
    val lastTap = remember { mutableLongStateOf(-tapWindowMs) }
    val chrome = LocalPlayerChrome.current
    Box(
        modifier = Modifier
            .width(width)
            .height(BOTTOM_ACTION_SIZE)
            .additiveChrome(chrome && !vibrant)
            .background(if (highlighted) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !loading,
            ) {
                val now = uptimeMillis()
                if (now - lastTap.longValue >= tapWindowMs) {
                    lastTap.longValue = now
                    haptics.play(haptic)
                    onClick()
                }
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (vibrant && chrome) {
            VibrantChromeInk
        } else {
            when {
                !highlighted -> chromeWhite(0.75f)
                chrome -> Color.White.copy(alpha = CHROME_FULL_ALPHA)
                else -> Color.White
            }
        }
        Crossfade(
            targetState = loading,
            modifier = Modifier.vibrantChrome(chrome && vibrant),
            animationSpec = tween(durationMillis = 200),
            label = "pillSegmentLoading",
        ) { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else if (icon != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Crossfade(
                        targetState = icon to iconSize,
                        animationSpec = tween(durationMillis = 200),
                        label = "pillSegmentIcon",
                    ) { (currentIcon, currentSize) ->
                        Icon(
                            imageVector = currentIcon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(currentSize),
                        )
                    }
                    if (trailingLabel != null) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = trailingLabel,
                            color = tint,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else if (label != null) {
                Text(
                    text = label,
                    color = tint,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun OutputCaption(
    accountName: String?,
    onOpenOutput: () -> Unit,
    onOpenMembers: () -> Unit,
) {
    val badge = rememberPartyBadge()
    val outputName = rememberAudioOutputName(accountName)
    val outputFormat by PlayerPlatform.host.outputFormat.collectAsStateWithLifecycle()
    val jamName = badge.hostFirstName
        ?.let { stringResource(Res.string.listen_together_jam, it) }
        ?: stringResource(Res.string.listen_together_jam_unnamed)
    val captionModifier = Modifier
        .fillMaxWidth(0.65f)
        .additiveChrome(LocalPlayerChrome.current)
        .clickable { if (badge.inParty) onOpenMembers() else onOpenOutput() }
    if (!badge.inParty && (outputFormat.bitPerfect || outputFormat.dacLimited)) {
        ShimmerText(
            text = outputName,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
            modifier = captionModifier,
        )
    } else {
        Text(
            text = if (badge.inParty) jamName else outputName,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = chromeWhite(0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = captionModifier,
        )
    }
}

private data class PartyBadge(
    val inParty: Boolean,
    val members: Int,
    val hostFirstName: String?,
)

private fun PartyUi.badge(): PartyBadge = PartyBadge(
    inParty = inParty,
    members = members.size,
    hostFirstName = members.firstOrNull(PartyMember::isHost)
        ?.displayName
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() },
)

@Composable
internal fun rememberControlsLocked(): Boolean {
    val locked = remember {
        PlayerPlatform.host.party.map { it.controlsLocked }.distinctUntilChanged()
    }
    return locked
        .collectAsStateWithLifecycle(initialValue = PlayerPlatform.host.party.value.controlsLocked)
        .value
}

@Composable
private fun rememberPartyBadge(): PartyBadge {
    val badges = remember {
        PlayerPlatform.host.party.map { it.badge() }.distinctUntilChanged()
    }
    return badges
        .collectAsStateWithLifecycle(initialValue = PlayerPlatform.host.party.value.badge())
        .value
}

@Composable
private fun BottomGlyph(
    icon: ImageVector?,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    haptic: Haptic = Haptic.Tap,
    label: String? = null,
    iconSize: Dp = BOTTOM_ICON_SIZE,
    tapWindowMs: Long = 0L,
    vibrant: Boolean = false,
) {
    val haptics = rememberHaptics()
    val lastTap = remember { mutableLongStateOf(-tapWindowMs) }
    val tileColor = chromeWhite(BOTTOM_ACTIVE_TILE_ALPHA)
    val tileChrome = LocalPlayerChrome.current
    Box(
        modifier = Modifier
            .size(BOTTOM_ACTION_SIZE)
            .then(
                if (highlighted) {
                    (
                        if (tileChrome) {
                            Modifier.additiveChrome(true)
                        } else {
                            Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        }
                    )
                        .drawWithContent {
                            drawContent()
                            val side = BOTTOM_ACTIVE_TILE_SIZE.toPx()
                            val corner = BOTTOM_ACTIVE_TILE_CORNER.toPx()
                            drawRoundRect(
                                color = tileColor,
                                topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                                size = Size(side, side),
                                cornerRadius = CornerRadius(corner, corner),
                                blendMode = BlendMode.SrcOut,
                            )
                        }
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                val now = uptimeMillis()
                if (now - lastTap.longValue >= tapWindowMs) {
                    lastTap.longValue = now
                    haptics.play(haptic)
                    onClick()
                }
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val chrome = LocalPlayerChrome.current && !highlighted
        val tint = if (vibrant && chrome) {
            VibrantChromeInk
        } else {
            if (highlighted) Color.White else chromeWhite(0.75f)
        }
        val glyphChrome = if (vibrant) Modifier.vibrantChrome(chrome) else Modifier.additiveChrome(chrome)
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize).then(glyphChrome),
            )
        } else if (label != null) {
            Text(
                text = label,
                color = tint,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = glyphChrome,
            )
        }
    }
}

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
