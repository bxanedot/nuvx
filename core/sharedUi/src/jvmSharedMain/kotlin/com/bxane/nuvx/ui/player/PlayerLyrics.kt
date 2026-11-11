package com.bxane.nuvx.ui.player

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.playback.PlaybackPosition
import com.bxane.nuvx.sharedui.resources.*
import com.bxane.nuvx.data.LocaleTags
import com.bxane.nuvx.data.lyrics.CharGrowth
import com.bxane.nuvx.data.lyrics.isGeniusSectionHeader
import com.bxane.nuvx.data.lyrics.GrowingWord
import com.bxane.nuvx.data.lyrics.LyricAlignment
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.lyrics.translationLanguageName
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.Haptics
import com.bxane.nuvx.ui.haptics.rememberHaptics
import com.bxane.nuvx.ui.rememberIsForeground
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch


private const val UNSUNG_ALPHA = 0.45f

private const val GLOW_ALPHA = 0.62f

private val GLOW_RADIUS = 6.dp

private val GLOW_ROOM = 10.dp

private val BACKING_FONT_SIZE = 23.sp
private val BACKING_LINE_HEIGHT = 29.sp
private const val BACKING_ALPHA = 0.72f

private const val BACKING_OPEN_MS = 400

private const val PHASE_BEFORE = 0
private const val PHASE_MOVING = 1
private const val PHASE_AFTER = 2

private const val GAP_RUNNING = -1f

private const val BACKING_LEAD_MS = 250L
private const val BACKING_CLOSE_MS = 450
private val BACKING_RISE = 6.dp
private const val BACKING_REST_SCALE = 0.94f

private const val BACKING_FADE_LEAD = 1.6f

private val SUB_LYRIC_FONT_SIZE = 20.sp
private val SUB_LYRIC_LINE_HEIGHT = 25.sp
private val SUB_BACKING_FONT_SIZE = 16.sp
private val SUB_BACKING_LINE_HEIGHT = 21.sp
private const val SUB_LYRIC_ALPHA = 0.85f
private val SUB_LYRIC_TUCK = 6.dp
private const val SUB_LYRIC_OPEN_MS = 460
private const val SUB_LYRIC_CLOSE_MS = 260

private val WIPE_FEATHER = 30.dp

private val WORD_RISE = 2.dp

private const val GROW_HEADROOM = 3f

private val DUET_LANE = 44.dp

private val GAP_ROW_HEIGHT = 40.dp
private val GAP_ROW_SPACING = 16.dp

private val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
private val LINE_FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)

private val LINE_FALLOFF_ALPHA_PHONE = floatArrayOf(1f, 0.74f, 0.6f, 0.48f, 0.4f, 0.34f)

private val SKELETON_BLOCKS = listOf(
    floatArrayOf(0.97f, 0.54f),
    floatArrayOf(0.92f, 0.99f, 0.41f),
    floatArrayOf(0.68f),
    floatArrayOf(0.95f, 0.73f),
    floatArrayOf(0.89f, 0.96f, 0.37f),
)

private val SKELETON_BAR = 26.dp
private val SKELETON_LEADING = 15.dp
private val SKELETON_BLOCK_GAP = 35.dp
private const val SKELETON_PERIOD_MS = 1_400

private const val BROWSING_ALPHA = 0.8f

private const val INACTIVE_SCALE = 0.98f

private const val PRESSED_SCALE = 0.96f

private const val GAP_DOTS = 3
private val GAP_DOT_SIZE = 13.dp
private val GAP_DOT_GAP = 5.dp
private const val GAP_DOT_REST = 0.25f
private const val GAP_REST_SCALE = 0.76f

private const val SCROLL_LEAD_MIN_MS = 350L
private const val SCROLL_LEAD_MAX_MS = 500L

private val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
private const val LYRIC_SETTLE_MS = 400

private const val STAGGER_STEPS = 3
private const val STAGGER_FRACTION = 0.06f

private class ScrollRun(val id: Int, val delta: Float, val durationMs: Int) {
    val spanMs: Float get() = durationMs * (1f + STAGGER_FRACTION * STAGGER_STEPS)
}

private fun scrollLead(lines: List<LyricLine>, positionMs: Long): Long {
    val current = lines.indexOfLast { it.timeMs <= positionMs }
    if (current < 0) return SCROLL_LEAD_MIN_MS
    val next = lines.getOrNull(current + 1) ?: return SCROLL_LEAD_MIN_MS
    val gap = next.timeMs - lines[current].endMs
    return gap.coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
}

private val CONTROLS_SCROLL_SLOP = 20.dp

private const val TRANSLATION_MOTION_MS = 540
private const val PARTICLES_PER_VOICE = 18

private data class TranslationParticle(
    val anchor: Offset,
    val drift: Offset,
    val radius: Float,
    val delay: Float,
)

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
internal fun SweptLyricLine(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    glowAlpha: Float = 0f,
    glowRadius: Dp = GLOW_RADIUS,
    glowRoom: Dp = 0.dp,
    feather: Boolean = false,
    rise: Boolean = true,
    alignEnd: Boolean = false,
    laneLocked: Boolean = false,
    translationProgress: State<Float>? = null,
    color: Color = Color.White,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    val growth = remember { CharGrowth() }

    val phase = remember(line, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now < line.animatesFromMs -> PHASE_BEFORE
                now > line.animatesUntilMs -> PHASE_AFTER
                else -> PHASE_MOVING
            }
        }
    }
    val drawnAt: () -> Long = {
        when (phase.value) {
            PHASE_MOVING -> clock.longValue
            PHASE_BEFORE -> line.animatesFromMs - 1
            else -> line.animatesUntilMs + 1
        }
    }

    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

    val riseAgainst: (Modifier) -> Modifier = { inner ->
        if (!rise) {
            inner
        } else {
            Modifier
                .drawWithContent {
                    val measured = layout
                    if (measured == null || line.words.isEmpty()) {
                        drawContent()
                    } else {
                        riseWith(
                            layout = measured,
                            line = line,
                            positionMs = drawnAt(),
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    }
                }
                .then(inner)
        }
    }

    val sweep = Modifier.drawWithContent {
        val position = drawnAt()
        when {
            position >= line.endMs -> drawContent()
            position <= line.timeMs -> Unit
            else -> layout?.let {
                sweepTo(it, line.revealedChars(position), line.sweepSpans, feather)
            }
        }
    }

    val right = alignsRight(line.text, alignEnd, laneLocked)
    Box(
        modifier.lyricParticles(layout, translationProgress, glowRoom),
        contentAlignment = if (right) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft,
    ) {
        Text(
            text = line.text,
            style = style,
            color = color.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = riseAgainst(room),
        )
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .graphicsLayer { alpha = glowAlpha }
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .then(room)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        val measured = layout ?: return@drawWithContent
                        glowGrown(
                            layout = measured,
                            line = line,
                            positionMs = drawnAt(),
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    },
            )
        }
        Text(
            text = line.text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            modifier = riseAgainst(
                Modifier
                    .graphicsLayer {
                        compositingStrategy = if (feather) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                    }
                    .then(room)
                    .then(sweep),
            ),
        )
    }
}

private fun ContentDrawScope.glowGrown(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isGrowing(positionMs)) return
    val em = layout.layoutInput.style.fontSize.toPx()
    val length = layout.layoutInput.text.length
    for (word in line.growingWords) {
        if (positionMs < word.startMs || positionMs > word.restsAtMs) continue
        val span = line.wordSpans[word.index]
        val fall = line.wordFall(word.index, positionMs)
        for (char in span.first..minOf(span.last, length - 1)) {
            word.sampleInto(char - span.first, positionMs, growth)
            if (growth.bloom <= 0.01f) continue
            val visualLine = layout.getLineForOffset(char)
            val from = layout.xOn(char, visualLine, inset)
            val to = layout.xOn(char + 1, visualLine, inset)
            if (to <= from) continue
            val dx = growth.shift * em
            val dy = -growth.rise * peak * fall
            val rowTop = layout.getLineTop(visualLine) + inset
            val bottom = layout.getLineBottom(visualLine) + inset
            val overhang = (to - from) * (growth.scale - 1f) / 2f
            clipRect(
                left = from - overhang + dx,
                top = rowTop - peak * GROW_HEADROOM,
                right = to + overhang + dx,
                bottom = bottom,
            ) {
                translate(left = dx, top = dy) {
                    scale(
                        growth.scale,
                        growth.scale,
                        Offset((from + to) / 2f, (rowTop + bottom) / 2f),
                    ) {
                        this@glowGrown.drawContent()
                    }
                }
                drawRect(
                    color = Color.White.copy(alpha = growth.bloom),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

private fun ContentDrawScope.riseWith(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isLifted(positionMs)) {
        drawContent()
        return
    }
    val em = layout.layoutInput.style.fontSize.toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
        val top = layout.getLineTop(visualLine) + inset
        val bottom = layout.getLineBottom(visualLine) + inset
        var at = lineStart
        var edge = layout.getLineLeft(visualLine) + inset
        for (index in line.words.indices) {
            val span = line.wordSpans[index]
            val start = maxOf(span.first, lineStart)
            val end = minOf(span.last + 1, lineEnd)
            if (start >= end) continue
            val held = line.growingAt(index)?.takeIf { positionMs in it.startMs..it.restsAtMs }
            val lift = line.wordLift(index, positionMs)
            if (held == null && lift <= 0.01f) continue
            val from = layout.xOn(start, visualLine, inset)
            val to = layout.xOn(end, visualLine, inset)
            if (to <= from) continue
            if (start > at) sliceRisen(edge, top, from, bottom, 0f)
            if (held != null) {
                growEach(
                    layout, held, line, positionMs, visualLine,
                    start, end, top, bottom, inset, peak, em, growth,
                )
            } else {
                sliceRisen(from, top - peak, to, bottom, -lift * peak)
            }
            at = end
            edge = to
        }
        if (at < lineEnd) {
            sliceRisen(edge, top, layout.getLineRight(visualLine) + inset, bottom, 0f)
        }
    }
}

@Suppress("LongParameterList")
private fun ContentDrawScope.growEach(
    layout: TextLayoutResult,
    word: GrowingWord,
    line: LyricLine,
    positionMs: Long,
    visualLine: Int,
    start: Int,
    end: Int,
    top: Float,
    bottom: Float,
    inset: Float,
    peak: Float,
    em: Float,
    growth: CharGrowth,
) {
    val fall = line.wordFall(word.index, positionMs)
    val first = line.wordSpans[word.index].first
    val ceiling = top - peak * GROW_HEADROOM
    val middle = (top + bottom) / 2f
    for (char in start until end) {
        word.sampleInto(char - first, positionMs, growth)
        val from = layout.xOn(char, visualLine, inset)
        val to = layout.xOn(char + 1, visualLine, inset)
        if (to <= from) continue
        val dx = growth.shift * em
        val dy = -growth.rise * peak * fall
        val overhang = (to - from) * (growth.scale - 1f) / 2f
        clipShiftedDown(
            left = from - overhang + dx,
            top = ceiling,
            right = to + overhang + dx,
            bottom = bottom,
            dy = dy,
        ) {
            translate(left = dx) {
                scale(growth.scale, growth.scale, Offset((from + to) / 2f, middle)) {
                    this@growEach.drawContent()
                }
            }
        }
    }
}

private fun TextLayoutResult.xOn(offset: Int, visualLine: Int, inset: Float): Float {
    val lineStart = getLineStart(visualLine)
    val lineEndVisible = getLineEnd(visualLine, visibleEnd = true)
    val lineLeft = getLineLeft(visualLine) + inset
    val lineRight = getLineRight(visualLine) + inset

    val rtl = getParagraphDirection(lineStart.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) ==
              androidx.compose.ui.text.style.ResolvedTextDirection.Rtl

    return when {
        offset <= lineStart -> if (rtl) lineRight else lineLeft
        offset >= lineEndVisible -> if (rtl) lineLeft else lineRight
        else -> (getHorizontalPosition(offset, usePrimaryDirection = true) + inset)
            .coerceIn(lineLeft, lineRight)
    }
}

private fun ContentDrawScope.sliceRisen(
    from: Float,
    top: Float,
    to: Float,
    bottom: Float,
    dy: Float,
) {
    if (to <= from) return
    clipShiftedDown(left = from, top = top, right = to, bottom = bottom, dy = dy) {
        this@sliceRisen.drawContent()
    }
}

private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    visualLine: Int,
    spans: List<IntRange>,
): Float {
    val lineStart = layout.getLineStart(visualLine)
    val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
    val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
    val word = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (word != null && word.first >= lineStart && word.last + 1 <= lineEndVisible) {
        val from = layout.xOn(word.first, visualLine, 0f)
        val to = layout.xOn(word.last + 1, visualLine, 0f)
        return from + (to - from) * (chars - word.first) / (word.last + 1 - word.first)
    }
    val index = chars.toInt().coerceIn(lineStart, lineEndAll)
    val here = layout.xOn(index, visualLine, 0f)
    val rawNext = index + 1
    val textLen = layout.layoutInput.text.length
    val spills: Boolean = layout.getLineForOffset(rawNext.coerceIn(0, textLen)) != visualLine
    val next: Float = if (spills) {
        if (rawNext > lineEndVisible) {
            val rtl = layout.getParagraphDirection(
                visualLine.coerceAtMost(lineEndAll).coerceIn(0, textLen - 1)
            ) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
            val farLeft = layout.getLineLeft(visualLine)
            val farRight = layout.getLineRight(visualLine)
            if (rtl) farLeft else farRight
        } else {
            layout.xOn(rawNext.coerceAtMost(lineEndVisible), visualLine, 0f)
        }
    } else {
        layout.xOn(rawNext, visualLine, 0f)
    }
    return here + (next - here) * (chars - index)
}

private fun ContentDrawScope.sweepTo(
    layout: TextLayoutResult,
    revealedChars: Float,
    spans: List<IntRange>,
    feather: Boolean,
) {
    if (revealedChars <= 0f) return
    val textLen = layout.layoutInput.text.length
    if (revealedChars >= textLen) {
        drawContent()
        return
    }
    val offset = layout.getLineStart(0).coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
    val rtl = layout.getParagraphDirection(offset) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl

    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
        val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)

        if (revealedChars <= lineStart) return

        val cut = revealedChars < lineEndAll
        val charPos = if (cut) {
            horizontalAt(layout, revealedChars, visualLine, spans)
        } else {
            if (rtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
        }

        val lineLeft = layout.getLineLeft(visualLine)
        val lineRight = layout.getLineRight(visualLine)

        val safeCharPos = charPos.coerceIn(lineLeft, lineRight)

        val featherPx = if (feather && cut) {
            minOf(
                WIPE_FEATHER.toPx(),
                if (rtl) lineRight - safeCharPos else safeCharPos - lineLeft,
            )
        } else {
            0f
        }
        val lead = if (rtl) {
            (safeCharPos - featherPx).coerceAtLeast(lineLeft)
        } else {
            (safeCharPos + featherPx).coerceAtMost(lineRight)
        }

        val (clipLeft, clipRight) = if (rtl) {
            lead to lineRight
        } else {
            lineLeft to lead
        }

        if (clipLeft >= clipRight) continue

        val top = layout.getLineTop(visualLine)
        val bottom = layout.getLineBottom(visualLine)
        clipRect(
            left = clipLeft,
            top = top,
            right = clipRight,
            bottom = bottom,
        ) {
            this@sweepTo.drawContent()
        }

        if (featherPx < 0.5f) continue

        clipRect(top = top, bottom = bottom) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White,
                    1f to Color.Transparent,
                    startX = safeCharPos,
                    endX = if (rtl) safeCharPos - featherPx else safeCharPos + featherPx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
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
internal fun LyricsTranslationMotion(
    trigger: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (State<Float>?) -> Unit,
) {
    val progress = remember { Animatable(1f) }
    val foreground = rememberIsForeground()
    var consumedTrigger by remember { mutableIntStateOf(trigger) }
    LaunchedEffect(trigger, reduceMotion, foreground) {
        val changed = trigger != consumedTrigger
        consumedTrigger = trigger
        if (!changed || trigger <= 0 || reduceMotion || !foreground) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = TRANSLATION_MOTION_MS, easing = LinearEasing),
            )
        }
    }

    Box(modifier = modifier) {
        content(progress.asState().takeIf { !reduceMotion && foreground && trigger > 0 })
    }
}

private fun Modifier.lyricParticles(
    layout: TextLayoutResult?,
    progress: State<Float>?,
    room: Dp,
): Modifier {
    if (layout == null || progress == null) return this
    return drawWithCache {
        val text = layout.layoutInput.text.text
        val candidates = text.indices.filter { text[it].isLetterOrDigit() }
        val random = Random(text.hashCode())
        val inset = room.toPx()
        val particles = candidates.shuffled(random).take(PARTICLES_PER_VOICE).map { index ->
            val glyph = layout.getBoundingBox(index)
            TranslationParticle(
                anchor = glyph.center + Offset(inset, inset),
                drift = Offset((random.nextFloat() - 0.5f) * 12.dp.toPx(),
                    -(5f + random.nextFloat() * 11f).dp.toPx()),
                radius = (0.65f + random.nextFloat() * 0.65f).dp.toPx(),
                delay = 0.16f * index / text.length.coerceAtLeast(1),
            )
        }
        onDrawWithContent {
            drawContent()
            val value = progress.value
            if (value > 0f && value < 1f) {
                particles.forEach { particle ->
                    val t = ((value - particle.delay) / 0.84f).coerceIn(0f, 1f)
                    val envelope = sin(PI * t).toFloat()
                    val ease = 1f - (1f - t) * (1f - t)
                    val center = particle.anchor + Offset(
                        particle.drift.x * ease,
                        particle.drift.y * ease + 3.dp.toPx() * t * t,
                    )
                    drawCircle(Color.White, particle.radius * 2.7f, center, alpha = envelope * 0.07f)
                    drawCircle(Color.White, particle.radius, center, alpha = envelope * 0.58f)
                }
            }
        }
    }
}

@Composable
private fun LyricsSkeleton(modifier: Modifier = Modifier) {
    val sweep = rememberInfiniteTransition(label = "lyricsSkeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(SKELETON_PERIOD_MS, easing = LinearEasing),
        ),
        label = "sweep",
    )
    BoxWithConstraints(
        modifier.padding(top = 40.dp),
    ) {
        val column = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(SKELETON_BLOCK_GAP)) {
            SKELETON_BLOCKS.forEach { rows ->
                Column(verticalArrangement = Arrangement.spacedBy(SKELETON_LEADING)) {
                    rows.forEach { fraction ->
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(SKELETON_BAR)
                                .clip(RoundedCornerShape(4.dp))
                                .drawWithCache {
                                    val full = column.toPx()
                                    val band = full * 0.45f
                                    val startX = -band + sweep.value * (full + band * 2)
                                    val brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.10f),
                                            Color.White.copy(alpha = 0.26f),
                                            Color.White.copy(alpha = 0.10f),
                                        ),
                                        startX = startX,
                                        endX = startX + band,
                                    )
                                    onDrawBehind { drawRect(brush) }
                                },
                        )
                    }
                }
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LyricsPanel(
    lines: List<LyricLine>,
    subLines: List<LyricLine>? = null,
    trackKey: String,
    playhead: LyricPlayhead,
    looking: Boolean,
    isPlaying: Boolean,
    active: Boolean = true,
    onSeekToLine: (Long) -> Unit,
    controlsOpen: Boolean,
    onRevealControls: () -> Unit,
    onHideControls: () -> Unit,
    translationProgress: State<Float>? = null,
    onScrollingChange: (Boolean) -> Unit = {},
    canPick: Boolean = false,
    picking: Boolean = false,
    picked: Set<Int> = emptySet(),
    onPickLine: (Int) -> Unit = {},
    onTogglePick: (Int) -> Unit = {},
    centerFocus: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val panelPlaying = isPlaying && active
    val clock = rememberLyricClock(trackKey, playhead, panelPlaying)
    val subReveal = rememberSubLyricsReveal(subLines, trackKey)

    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    val duet = remember(lines) { lines.any { it.alignment == LyricAlignment.End } }

    val activeRows by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced) emptyList() else activeLyricRows(lines, clock.longValue)
        }
    }
    val scrollLine = activeRows.firstOrNull() ?: -1
    val leadLine by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced) {
                -1
            } else {
                val now = clock.longValue
                activeLyricRows(lines, now + scrollLead(lines, now)).firstOrNull() ?: -1
            }
        }
    }
    val focusLine = if (leadLine >= 0) leadLine else scrollLine
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect(onScrollingChange)
    }
    val viewportHeight by remember(listState) {
        derivedStateOf { listState.layoutInfo.viewportSize.height }
    }
    val keepScroll = remember(listState) { keepScrollInList(listState) }
    var browsingByDrag by remember { mutableStateOf(false) }
    val browsing = browsingByDrag || picking
    val onBottomHalfTap: () -> Unit = {
        if (!picking && !listState.isScrollInProgress) {
            onRevealControls()
        }
    }

    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val lyricsBlur by PlayerSettings.lyricsBlur.collectAsStateWithLifecycle()
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()

    val glowing = !reduceAnimation && !reduceDynamicBlur && lyricsBlur &&
        renderEffectBlurSupported

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                browsingByDrag = true
            }
        }
    }

    val controlsOnScroll = rememberPlayerControlsOnScroll(
        onReveal = { if (!picking) onRevealControls() },
        onHide = onHideControls,
    )

    val currentLine by rememberUpdatedState(focusLine)
    val activeOnScreen by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.index == currentLine }
        }
    }
    LaunchedEffect(browsingByDrag, activeOnScreen, listState.isScrollInProgress, panelPlaying) {
        if (panelPlaying && browsingByDrag && activeOnScreen && !listState.isScrollInProgress) {
            delay(600)
            browsingByDrag = false
        }
    }

    LaunchedEffect(browsingByDrag, listState.isScrollInProgress, panelPlaying) {
        if (panelPlaying && browsingByDrag && !listState.isScrollInProgress) {
            delay(5_000)
            browsingByDrag = false
        }
    }

    var run by remember(lines) { mutableStateOf(ScrollRun(0, 0f, LYRIC_SETTLE_MS)) }
    val since = remember(lines) { mutableFloatStateOf(0f) }
    LaunchedEffect(run.id) {
        if (run.id == 0) return@LaunchedEffect
        animate(
            initialValue = 0f,
            targetValue = run.spanMs,
            animationSpec = tween(run.spanMs.toInt(), easing = LinearEasing),
        ) { value, _ -> since.floatValue = value }
    }
    var placed by remember(trackKey) { mutableStateOf(false) }
    val centered = centerFocus && isSynced
    val focusOffset: (Int) -> Int = { size ->
        if (!centered) {
            0
        } else {
            val info = listState.layoutInfo
            (info.viewportStartOffset + info.viewportEndOffset) / 2 - size / 2
        }
    }
    LaunchedEffect(focusLine, browsing, controlsOpen, active) {
        if (active && isSynced && !browsing &&
            focusLine >= 0 && focusLine in lines.indices
        ) {
            snapshotFlow { listState.layoutInfo }.first {
                it.viewportSize.height > 0 && (!centered || it.beforeContentPadding > 0)
            }
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusLine }
            when {
                !placed -> {
                    listState.scrollToItem(focusLine, scrollOffset = -focusOffset(0))
                    if (centered) {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == focusLine }
                            ?.let { listState.scrollBy((it.offset - focusOffset(it.size)).toFloat()) }
                    }
                    placed = true
                }
                visible != null -> {
                    val span = scrollLead(lines, clock.longValue).toInt()
                    val distance = (visible.offset - focusOffset(visible.size)).toFloat()
                    run = ScrollRun(run.id + 1, distance, span)
                    listState.animateScrollBy(
                        value = distance,
                        animationSpec = tween(durationMillis = span, easing = LYRIC_EASING),
                    )
                }
                else -> {
                    listState.animateScrollToItem(focusLine, scrollOffset = -focusOffset(0))
                    if (centered) {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == focusLine }
                            ?.let {
                                listState.animateScrollBy(
                                    value = (it.offset - focusOffset(it.size)).toFloat(),
                                    animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                                )
                            }
                    }
                }
            }
        }
    }

    if (lines.isEmpty()) {
        val empty = modifier.revealLyricsControlsOnTap(!controlsOpen, onBottomHalfTap)
        if (looking) {
            LyricsSkeleton(empty)
        } else {
            Box(empty, contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.no_lyrics_for_track),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .bleedHorizontally(PLAYER_GUTTER)
            .nestedScroll(controlsOnScroll)
            .nestedScroll(keepScroll)
            .revealLyricsControlsOnTap(!controlsOpen && !picking, onBottomHalfTap)
            .fadingEdges(),
        contentPadding = PaddingValues(
            top = if (centered) {
                with(LocalDensity.current) { viewportHeight.toDp() } * 0.5f
            } else {
                40.dp - GLOW_ROOM
            },
            bottom = with(LocalDensity.current) { viewportHeight.toDp() } * 0.8f,
            start = PLAYER_GUTTER - GLOW_ROOM,
            end = PLAYER_GUTTER - GLOW_ROOM,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            if (!isSynced && isGeniusSectionHeader(line.text)) {
                val sectionTitle = line.text.removePrefix("[").removeSuffix("]").trim()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 6.dp else 24.dp, bottom = 8.dp)
                        .padding(horizontal = GLOW_ROOM),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 11.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = sectionTitle.uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(
                                letterSpacing = 1.3.sp,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                            ),
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
                return@itemsIndexed
            }

            if (!isSynced && line.isGap) {
                Spacer(Modifier.height(14.dp))
                return@itemsIndexed
            }

            val offset = if (scrollLine < 0) 0 else index - scrollLine
            val distance = abs(offset)
            val isActive = isSynced && index in activeRows
            val falloff = if (centerFocus) LINE_FALLOFF_ALPHA else LINE_FALLOFF_ALPHA_PHONE
            val step = distance.coerceAtMost(falloff.lastIndex)
            val blur by animateDpAsState(
                targetValue = when {
                    !isSynced || reduceDynamicBlur || !lyricsBlur || browsing || isActive -> 0.dp
                    else -> LINE_FALLOFF_BLUR[step.coerceAtMost(LINE_FALLOFF_BLUR.lastIndex)]
                },
                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "lyricBlur",
            )
            val lineAlpha by animateFloatAsState(
                targetValue = when {
                    !isSynced -> 0.95f
                    isActive -> 1f
                    browsing -> BROWSING_ALPHA
                    else -> falloff[step]
                },
                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "lyricAlpha",
            )
            if (line.isGap) {
                val until = lines.getOrNull(index + 1)?.timeMs ?: line.endMs
                val gapFill = remember(line, until, clock) {
                    derivedStateOf(structuralEqualityPolicy()) {
                        val now = clock.longValue
                        when {
                            now <= line.timeMs -> 0f
                            now >= until -> 1f
                            else -> GAP_RUNNING
                        }
                    }
                }
                val swell by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (isActive) 400 else 350,
                        easing = LYRIC_EASING,
                    ),
                    label = "gapSwell",
                )
                val instrumental = stringResource(Res.string.instrumental)
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .height((GAP_ROW_HEIGHT + GAP_ROW_SPACING) * swell)
                        .clipToBounds(),
                ) {
                Box(
                    modifier = Modifier
                        .blur(blur, BlurredEdgeTreatment.Unbounded)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = isSynced && !picking) { onSeekToLine(line.timeMs) }
                        .padding(GLOW_ROOM)
                        .size(
                            width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2,
                            height = GAP_DOT_SIZE,
                        )
                        .graphicsLayer {
                            val grow = GAP_REST_SCALE + (1f - GAP_REST_SCALE) * swell
                            scaleX = grow
                            scaleY = grow
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            alpha = lineAlpha * swell
                        }
                        .drawBehind {
                            val span = (until - line.timeMs).coerceAtLeast(1L)
                            val through = gapFill.value.takeUnless { it == GAP_RUNNING }
                                ?: ((clock.longValue - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                            val radius = GAP_DOT_SIZE.toPx() / 2f
                            val stride = (GAP_DOT_SIZE + GAP_DOT_GAP).toPx()
                            repeat(GAP_DOTS) { dot ->
                                val lit = (through * GAP_DOTS - dot).coerceIn(0f, 1f)
                                drawCircle(
                                    color = Color.White.copy(
                                        alpha = GAP_DOT_REST + (1f - GAP_DOT_REST) * lit,
                                    ),
                                    radius = radius,
                                    center = Offset(radius + dot * stride, size.height / 2f),
                                )
                            }
                        }
                        .semantics { contentDescription = instrumental },
                )
                }
            } else {
                val alignEnd = duet && line.alignment == LyricAlignment.End
                val style = if (isSynced) {
                    MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 34.sp,
                        lineHeight = 41.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                    )
                } else {
                    MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 30.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                    )
                }
                val sung = offset < 0
                val behind = if (run.delta >= 0f) index - focusLine else focusLine - index
                val staggerDelay = behind.coerceIn(0, STAGGER_STEPS) *
                    STAGGER_FRACTION * run.durationMs
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = when {
                        pressed -> PRESSED_SCALE
                        isActive -> 1f
                        else -> INACTIVE_SCALE
                    },
                    animationSpec = tween(
                        durationMillis = if (pressed) 120 else LYRIC_SETTLE_MS,
                        easing = LYRIC_EASING,
                    ),
                    label = "lyricScale",
                )
                val glow by animateFloatAsState(
                    targetValue = if (isActive && glowing) GLOW_ALPHA else 0f,
                    animationSpec = tween(durationMillis = 420),
                    label = "lyricGlow",
                )
                val shape = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (duet && alignEnd) DUET_LANE else 0.dp,
                        end = if (duet && !alignEnd) DUET_LANE else 0.dp,
                    )
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                        alpha = lineAlpha
                        translationY = if (staggerDelay <= 0f) {
                            0f
                        } else {
                            val elapsed = since.floatValue
                            run.delta * (
                                LYRIC_EASING.transform(
                                    (elapsed / run.durationMs).coerceIn(0f, 1f),
                                ) - LYRIC_EASING.transform(
                                    ((elapsed - staggerDelay) / run.durationMs)
                                        .coerceIn(0f, 1f),
                                )
                                )
                        }
                    }
                    .blur(blur, BlurredEdgeTreatment.Unbounded)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            index in picked -> Color.White.copy(alpha = 0.16f)
                            picking -> Color.White.copy(alpha = 0.05f)
                            else -> Color.Transparent
                        },
                    )
                    .then(
                        if (canPick) {
                            Modifier.combinedClickable(
                                enabled = picking || isSynced,
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                onLongClick = { onPickLine(index) },
                                onClick = {
                                    if (picking) {
                                        onTogglePick(index)
                                    } else {
                                        onSeekToLine(line.timeMs)
                                    }
                                },
                            )
                        } else {
                            Modifier.clickable(
                                enabled = isSynced,
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                            ) { onSeekToLine(line.timeMs) }
                        },
                    )
                val sub = subReveal.lines?.getOrNull(index)
                val subStyle = style.copy(
                    fontSize = SUB_LYRIC_FONT_SIZE,
                    lineHeight = SUB_LYRIC_LINE_HEIGHT,
                    fontWeight = FontWeight.Bold,
                )
                val backingOpen = line.background?.let { backing ->
                    val due by remember(backing, clock) {
                        derivedStateOf {
                            val now = clock.longValue
                            now >= backing.timeMs - BACKING_LEAD_MS && now < backing.endMs
                        }
                    }
                    val wanted = !isSynced || picking || if (backing.isWordSynced) {
                        due
                    } else {
                        isActive || index == focusLine
                    }
                    animateFloatAsState(
                        targetValue = if (wanted) 1f else 0f,
                        animationSpec = when {
                            reduceAnimation -> tween(0)
                            wanted -> tween(BACKING_OPEN_MS, easing = LYRIC_EASING)
                            else -> tween(
                                BACKING_CLOSE_MS,
                                delayMillis = if (index < focusLine) run.durationMs else 0,
                                easing = FastOutSlowInEasing,
                            )
                        },
                        label = "backingOpen",
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(modifier = shape) {
                    PanelVoice(
                        line = line,
                        clock = clock,
                        style = voiceStyle(style, line.text, alignEnd, duet),
                        isActive = isActive,
                        sung = sung,
                        synced = isSynced,
                        browsing = browsing,
                        glowAlpha = glow,
                        room = GLOW_ROOM,
                        alignEnd = alignEnd,
                        laneLocked = duet,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    sub?.takeIf { it.text.differsFrom(line.text) }?.let { subLine ->
                        PanelVoice(
                            line = subLine,
                            clock = clock,
                            style = voiceStyle(subStyle, subLine.text, alignEnd, duet),
                            isActive = isActive,
                            sung = sung,
                            synced = isSynced,
                            browsing = browsing,
                            glowAlpha = 0f,
                            room = 0.dp,
                            alignEnd = alignEnd,
                            laneLocked = duet,
                            rise = false,
                            translationProgress = translationProgress.takeIf {
                                if (isSynced) abs(index - focusLine) <= 1 else index < 4
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .revealBelow(subReveal.progress)
                                .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                .offset(y = -SUB_LYRIC_TUCK)
                                .graphicsLayer { alpha = SUB_LYRIC_ALPHA },
                        )
                    }
                    line.background?.let { backing ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .then(backingOpen?.let { Modifier.revealBacking(it, alignEnd) } ?: Modifier),
                        ) {
                            PanelVoice(
                                line = backing.withoutBracketPunctuation(),
                                clock = clock,
                                style = voiceStyle(style.copy(
                                    fontSize = BACKING_FONT_SIZE,
                                    lineHeight = BACKING_LINE_HEIGHT,
                                ), backing.text, alignEnd, duet),
                                isActive = isActive,
                                sung = sung,
                                synced = isSynced,
                                browsing = browsing,
                                glowAlpha = 0f,
                                room = 0.dp,
                                alignEnd = alignEnd,
                                laneLocked = duet,
                                rise = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                    .graphicsLayer { alpha = BACKING_ALPHA },
                            )
                            sub?.background
                                ?.takeIf { it.text.differsFrom(backing.text) }
                                ?.let { subBacking ->
                                    PanelVoice(
                                        line = subBacking.withoutBracketPunctuation(),
                                        clock = clock,
                                        style = voiceStyle(subStyle.copy(
                                            fontSize = SUB_BACKING_FONT_SIZE,
                                            lineHeight = SUB_BACKING_LINE_HEIGHT,
                                        ), subBacking.text, alignEnd, duet),
                                        isActive = isActive,
                                        sung = sung,
                                        synced = isSynced,
                                        browsing = browsing,
                                        glowAlpha = 0f,
                                        room = 0.dp,
                                        alignEnd = alignEnd,
                                        laneLocked = duet,
                                        rise = false,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .revealBelow(subReveal.progress)
                                            .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                            .offset(y = -SUB_LYRIC_TUCK)
                                            .graphicsLayer { alpha = BACKING_ALPHA * SUB_LYRIC_ALPHA },
                                    )
                                }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun PanelVoice(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    sung: Boolean,
    synced: Boolean,
    browsing: Boolean,
    glowAlpha: Float,
    room: Dp,
    alignEnd: Boolean,
    laneLocked: Boolean = false,
    translationProgress: State<Float>? = null,
    rise: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val arabicScript = remember(line.text) { usesArabicScript(line.text) }
    val lyricStyle = if (arabicScript) {
        val vazirmatn = Font(Res.font.vazirmatn_regular)
        remember(style, vazirmatn) { style.copy(fontFamily = FontFamily(vazirmatn)) }
    } else style
    if (line.isWordSynced && !browsing) {
        val tail by animateFloatAsState(
            targetValue = if (sung) 1f else UNSUNG_ALPHA,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = line,
            clock = clock,
            style = lyricStyle,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = glowAlpha,
            glowRoom = room,
            feather = isActive,
            rise = rise,
            alignEnd = alignEnd,
            laneLocked = laneLocked,
            translationProgress = translationProgress,
            color = Color.White,
        )
    } else if (line.isWordSynced) {
        val tail by animateFloatAsState(
            targetValue = if (sung) 1f else UNSUNG_ALPHA,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = line,
            clock = clock,
            style = lyricStyle,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = 0f,
            glowRoom = room,
            rise = rise,
            alignEnd = alignEnd,
            laneLocked = laneLocked,
            translationProgress = translationProgress,
            color = Color.White,
        )
    } else {
        val lit by animateFloatAsState(
            targetValue = if (!synced || sung || isActive) 1f else UNSUNG_ALPHA,
            label = "lyricLit",
        )
        var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = line.text,
            style = lyricStyle,
            color = Color.White.copy(alpha = lit),
            onTextLayout = { layout = it },
            modifier = modifier.lyricParticles(layout, translationProgress, room).padding(room),
        )
    }
}

private class SubLyricsReveal(
    val progress: State<Float>,
    lines: State<List<LyricLine>?>,
) {
    val lines: List<LyricLine>? by lines
}

@Composable
private fun rememberSubLyricsReveal(target: List<LyricLine>?, trackKey: String): SubLyricsReveal {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val shown = remember(trackKey) { mutableStateOf(target) }
    val progress = remember(trackKey) { Animatable(if (target != null) 1f else 0f) }
    LaunchedEffect(target, trackKey, reduceAnimation) {
        if (reduceAnimation) {
            shown.value = target
            progress.snapTo(if (target != null) 1f else 0f)
            return@LaunchedEffect
        }
        if (shown.value != null && shown.value !== target && progress.value > 0f) {
            progress.animateTo(0f, tween(SUB_LYRIC_CLOSE_MS, easing = FastOutSlowInEasing))
        }
        if (target != null) {
            shown.value = target
            progress.animateTo(1f, tween(SUB_LYRIC_OPEN_MS, easing = LYRIC_EASING))
        } else {
            shown.value = null
        }
    }
    return remember(trackKey) { SubLyricsReveal(progress.asState(), shown) }
}

private fun Modifier.revealBelow(progress: State<Float>): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val open = progress.value
        val height = (placeable.height * open).roundToInt()
        layout(placeable.width, height) {
            placeable.placeWithLayer(0, 0) {
                translationY = -placeable.height * (1f - open) * 0.6f
                alpha = open * open
            }
        }
    }

private fun Modifier.revealBacking(progress: State<Float>, alignEnd: Boolean): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val open = progress.value
        val rise = BACKING_RISE.toPx()
        layout(placeable.width, (placeable.height * open).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                alpha = (open * BACKING_FADE_LEAD).coerceAtMost(1f)
                translationY = rise * (1f - open)
                val grow = BACKING_REST_SCALE + (1f - BACKING_REST_SCALE) * open
                scaleX = grow
                scaleY = grow
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
            }
        }
    }

private fun String.differsFrom(original: String): Boolean =
    trim().lowercase(Locale.ROOT) != original.trim().lowercase(Locale.ROOT)

private fun LyricLine.withoutBracketPunctuation(): LyricLine = copy(
    text = text.stripParens(),
    words = words.mapNotNull { word -> word.withoutChars { it == '(' || it == ')' } },
)

private fun String.stripParens(): String = replace("(", "").replace(")", "").trim()
