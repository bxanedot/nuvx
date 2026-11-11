package com.bxane.nuvx.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.data.model.ArtistCredit
import com.bxane.nuvx.data.model.CreditSegment
import com.bxane.nuvx.data.model.creditSegments
import com.bxane.nuvx.ui.components.LINE_FADE
import com.bxane.nuvx.ui.graphics.fadingEdges
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val MARQUEE_DP_PER_SEC = 26f

private val MARQUEE_GAP = 48.dp

private const val MARQUEE_REST_MS = 5_000L

internal const val MARQUEE_ARTIST_STAGGER_MS = 3_000L

@Composable
internal fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startDelayMillis: Long = 0L,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onOverflowChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        BoxWithConstraints(Modifier.weight(1f, fill = false).clipToBounds()) {
            val maxWidthPx = constraints.maxWidth
            val layout = remember(text, style, maxWidthPx) {
                textMeasurer.measure(text = text, style = style, maxLines = 1, softWrap = false)
            }
            val overflowing = layout.size.width > maxWidthPx
            val crawls = enabled && overflowing
            LaunchedEffect(crawls) { onOverflowChange(crawls) }

            val travelPx = if (crawls) {
                layout.size.width + with(density) { MARQUEE_GAP.roundToPx() }
            } else {
                0
            }

            val offsetX = remember { Animatable(0f) }
            LaunchedEffect(text, travelPx, startDelayMillis) {
                offsetX.snapTo(0f)
                if (travelPx <= 0) return@LaunchedEffect
                val pxPerMs = with(density) { MARQUEE_DP_PER_SEC.dp.toPx() } / 1000f
                val scrollMs = (travelPx / pxPerMs).roundToInt().coerceAtLeast(400)
                delay(startDelayMillis)
                while (true) {
                    offsetX.animateTo(-travelPx.toFloat(), tween(scrollMs, easing = LinearEasing))
                    offsetX.snapTo(0f)
                    delay(MARQUEE_REST_MS)
                }
            }

            Row(
                modifier = Modifier
                    .marqueeFade(overflowing, travelPx) { offsetX.value }
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarqueeLine(text = text, style = style, color = color)
                if (crawls) {
                    Spacer(Modifier.width(MARQUEE_GAP))
                    MarqueeLine(text = text, style = style, color = color)
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

private fun Modifier.marqueeFade(overflows: Boolean, travelPx: Int, offset: () -> Float): Modifier =
    if (!overflows) {
        this
    } else {
        composed {
            val fadePx = with(LocalDensity.current) { LINE_FADE.toPx() }
            fadingEdges(
                LINE_FADE,
                start = {
                    val moved = -offset()
                    minOf(moved, travelPx - moved) / fadePx
                },
                end = { 1f },
            )
        }
    }

@Composable
private fun MarqueeLine(text: String, style: TextStyle, color: Color) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

@Composable
internal fun ArtistCreditsMarquee(
    text: String,
    credits: List<ArtistCredit>,
    style: TextStyle,
    color: Color,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startDelayMillis: Long = 0L,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val segments = remember(text, credits) { creditSegments(text, credits) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f, fill = false).clipToBounds()) {
            val maxWidthPx = constraints.maxWidth
            val layout = remember(text, style, maxWidthPx) {
                textMeasurer.measure(text = text, style = style, maxLines = 1, softWrap = false)
            }
            val overflowing = layout.size.width > maxWidthPx
            val crawls = enabled && overflowing
            val travelPx = if (crawls) {
                layout.size.width + with(density) { MARQUEE_GAP.roundToPx() }
            } else {
                0
            }

            val offsetX = remember { Animatable(0f) }
            LaunchedEffect(text, travelPx, startDelayMillis) {
                offsetX.snapTo(0f)
                if (travelPx <= 0) return@LaunchedEffect
                val pxPerMs = with(density) { MARQUEE_DP_PER_SEC.dp.toPx() } / 1000f
                val scrollMs = (travelPx / pxPerMs).roundToInt().coerceAtLeast(400)
                delay(startDelayMillis)
                while (true) {
                    offsetX.animateTo(-travelPx.toFloat(), tween(scrollMs, easing = LinearEasing))
                    offsetX.snapTo(0f)
                    delay(MARQUEE_REST_MS)
                }
            }

            Row(
                modifier = Modifier
                    .marqueeFade(overflowing, travelPx) { offsetX.value }
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CreditLine(segments = segments, style = style, color = color, onOpenArtist = onOpenArtist)
                if (crawls) {
                    Spacer(Modifier.width(MARQUEE_GAP))
                    CreditLine(segments = segments, style = style, color = color, onOpenArtist = onOpenArtist)
                }
            }
        }
    }
}

@Composable
private fun CreditLine(
    segments: List<CreditSegment>,
    style: TextStyle,
    color: Color,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
) {
    segments.forEach { segment ->
        val credit = segment.credit
        Text(
            text = segment.text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = if (credit == null) {
                Modifier
            } else {
                Modifier
                    .clip(LinkPressShape)
                    .clickable { onOpenArtist(credit.browseId, credit.name) }
            },
        )
    }
}
