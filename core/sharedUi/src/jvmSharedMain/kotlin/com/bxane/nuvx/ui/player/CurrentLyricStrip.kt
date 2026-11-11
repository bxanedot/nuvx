package com.bxane.nuvx.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.sharedui.resources.*
import com.bxane.nuvx.ui.icons.SfIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

private const val UNSUNG_ALPHA_STRIP = 0.55f
private const val LYRICS_UNAVAILABLE_HOLD_MS = 5_000L
private const val LYRICS_UNAVAILABLE_FADE_MS = 900

private fun isRtl(text: String): Boolean {
    var rtlCount = 0
    var ltrCount = 0
    for (ch in text) {
        val code = ch.code
        if (code in 0xFB1D..0xFB4F) return true
        val dir = Character.getDirectionality(ch)
        when (dir) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtlCount++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltrCount++
        }
    }
    return rtlCount > ltrCount
}

internal fun usesArabicScript(text: String): Boolean = text.any { ch ->
    val code = ch.code
    code in 0x0600..0x06FF || code in 0x0750..0x077F ||
        code in 0x08A0..0x08FF || code in 0xFB50..0xFDFF ||
        code in 0xFE70..0xFEFF
}

internal fun alignsRight(text: String, alignEnd: Boolean, laneLocked: Boolean): Boolean =
    if (laneLocked) alignEnd else isRtl(text)

internal fun voiceStyle(
    base: TextStyle,
    text: String,
    alignEnd: Boolean,
    laneLocked: Boolean,
): TextStyle = base.copy(
    textAlign = if (alignsRight(text, alignEnd, laneLocked)) TextAlign.Right else TextAlign.Left,
    textDirection = if (isRtl(text)) androidx.compose.ui.text.style.TextDirection.Rtl else androidx.compose.ui.text.style.TextDirection.Ltr,
)

@Composable
internal fun CurrentLyricStrip(
    lines: List<LyricLine>,
    trackKey: String,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
    durationMs: Long,
    lyricsUnavailable: Boolean,
    loadingText: String,
    onClick: () -> Unit,
) {
    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    var stripRtl by remember { mutableStateOf(false) }
    var activeText by remember { mutableStateOf("") }
    if (isSynced) {
        val clock = rememberLyricClock(trackKey, playhead, isPlaying)
        val index by remember(lines) {
            derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } }
        }
        val current = lines.getOrNull(index)
        val instrumental = current == null || current.isGap
        val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
        val intro = instrumental && firstSung >= 0 && index < firstSung
        val introLines = stringArrayResource(Res.array.lyrics_intro_lines)
        val introLine = remember(trackKey) { introLines.random() }
        activeText = when {
            intro -> introLine
            instrumental -> stringResource(Res.string.instrumental)
            else -> current.text
        }
        stripRtl = isRtl(activeText)
    }

    val align: Alignment = if (stripRtl) Alignment.CenterStart else Alignment.CenterStart

    CompositionLocalProvider(LocalLayoutDirection provides if (stripRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = 6.dp),
        ) {
            if (lines.isNotEmpty()) {
                CurrentLyricLine(
                    lines = lines,
                    trackKey = trackKey,
                    playhead = playhead,
                    isPlaying = isPlaying,
                    durationMs = durationMs,
                    onClick = onClick,
                    modifier = Modifier
                        .align(align),
                )
            } else if (lyricsUnavailable) {
                LyricsUnavailableLine(
                    trackKey = trackKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(align),
                )
            } else {
                LyricsLoadingLine(
                    text = loadingText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(align),
                )
            }
        }
    }
}

private val STRIP_LINE_HEIGHT = 20.sp

private const val LYRIC_FADE_FRACTION = 0.28f
private const val LYRIC_FADE_MIN_MS = 160f
private const val LYRIC_FADE_MAX_MS = 700f

@Composable
private fun CurrentLyricLine(
    lines: List<LyricLine>,
    trackKey: Any,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    val stripStyle = MaterialTheme.typography.titleMedium.copy(
        lineHeight = STRIP_LINE_HEIGHT,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    if (!isSynced) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        ) {
            Icon(
                imageVector = SfIcons.Instrumental,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.open_lyrics),
                style = stripStyle,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = SfIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
        return
    }

    val clock = rememberLyricClock(trackKey, playhead, isPlaying)

    val index by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } }
    }
    val current = lines.getOrNull(index)
    val instrumental = current == null || current.isGap
    val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
    val intro = instrumental && firstSung >= 0 && index < firstSung
    val introLines = stringArrayResource(Res.array.lyrics_intro_lines)
    val introLine = remember(trackKey) { introLines.random() }
    val text = when {
        intro -> introLine
        instrumental -> stringResource(Res.string.instrumental)
        else -> current.text
    }

    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()

    val stripRtl = isRtl(text)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (stripRtl) Arrangement.End else Arrangement.Start,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        if (instrumental) {
            Icon(
                imageVector = SfIcons.Instrumental,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        AnimatedContent(
            targetState = Triple(index, current, text),
            transitionSpec = {
                val duration = if (reduceAnimation) 0 else 340
                if (reduceAnimation) {
                    (fadeIn(snap()) togetherWith fadeOut(snap())).using(
                        SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> snap() })
                    )
                } else {
                    slideInVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { height -> (height * 0.35f).toInt() }
                        .togetherWith(
                            fadeOut(animationSpec = tween(duration, easing = FastOutSlowInEasing)) +
                                slideOutVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { height -> -(height * 0.35f).toInt() }
                        ).using(
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(duration, easing = FastOutSlowInEasing) })
                        )
                }
            },
            label = "currentLyricTransition",
            modifier = Modifier.weight(1f, fill = false),
        ) { (itemIndex, lineItem, lineText) ->
            val itemInstrumental = lineItem == null || lineItem.isGap
            val swept = lineItem?.takeIf { !itemInstrumental && it.isWordSynced }
            val fading = Modifier.graphicsLayer {
                if (itemInstrumental) return@graphicsLayer
                val start = lines.getOrNull(itemIndex)?.timeMs ?: 0L
                val end = lines.getOrNull(itemIndex + 1)?.timeMs
                    ?: durationMs.takeIf { it > start }
                    ?: (start + 4_000L)
                val fade = ((end - start) * LYRIC_FADE_FRACTION)
                    .coerceIn(LYRIC_FADE_MIN_MS, LYRIC_FADE_MAX_MS)
                val remaining = (end - clock.longValue).toFloat()
                alpha = 0.78f * (remaining / fade).coerceIn(0f, 1f)
            }
            if (swept != null) {
                SweptLyricLine(
                    line = swept,
                    clock = clock,
                    style = voiceStyle(stripStyle, text, alignEnd = false, laneLocked = false),
                    dimAlpha = UNSUNG_ALPHA_STRIP,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    rise = false,
                    modifier = fading,
                )
            } else {
                Text(
                    modifier = fading,
                    text = lineText,
                    style = voiceStyle(
                        stripStyle.copy(
                            color = if (itemInstrumental) Color.White.copy(alpha = 0.5f) else Color.White
                        ),
                        lineText,
                        alignEnd = false,
                        laneLocked = false,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!stripRtl) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = SfIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        } else {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = SfIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { scaleX = -1f },
            )
        }
    }
}

@Composable
private fun LyricsUnavailableLine(trackKey: Any, modifier: Modifier = Modifier) {
    var visible by remember(trackKey) { mutableStateOf(true) }
    LaunchedEffect(trackKey) {
        delay(LYRICS_UNAVAILABLE_HOLD_MS)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.55f else 0f,
        animationSpec = tween(durationMillis = LYRICS_UNAVAILABLE_FADE_MS),
        label = "lyricsUnavailableAlpha",
    )
    Text(
        text = stringResource(Res.string.lyrics_not_available),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(vertical = 4.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun LyricsLoadingLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(vertical = 4.dp),
    )
}
