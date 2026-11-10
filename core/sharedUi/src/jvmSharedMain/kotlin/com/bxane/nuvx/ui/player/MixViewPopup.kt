package com.bxane.nuvx.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.bxane.nuvx.ui.graphics.toImageBitmap
import com.bxane.nuvx.ui.graphics.forPixelAccess
import coil3.request.SuccessResult
import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import coil3.SingletonImageLoader
import com.bxane.nuvx.data.settings.MixDeck
import com.bxane.nuvx.data.settings.MixTransitionView
import com.bxane.nuvx.sharedui.resources.*
import com.bxane.nuvx.ui.components.optimizedHazeEffect
import com.bxane.nuvx.ui.utils.containSheetGestures
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val PopupShape = RoundedCornerShape(24.dp)
private val ScrimColor = Color.Black.copy(alpha = 0.6f)
private val PopupColor = Color(0xFF0D0D0D)
private val PopupMaxWidth = 480.dp

private val DecksHeight = 288.dp
private val DeckHeight = 110.dp
private val DeckTop = 16.dp
private val DeckBottomInset = 34.dp

private val LowColor = Color(0xFF2E45E6)
private val MidColor = Color(0xFFE08B35)
private val HighColor = Color(0xFFF1DDC4)

private class WaveColors(val low: Color, val mid: Color, val high: Color)

private val DefaultWaveColors = WaveColors(LowColor, MidColor, HighColor)
private val FrameColor = Color(0xFF3A9BDC)
private val FaderColor = Color(0xFFF2C230)
private val PanelColor = Color(0xFF1E1E1E)
private val ChipColor = Color(0xFF2A2A2A)
private val SecondaryText = Color.White.copy(alpha = 0.55f)

private const val ContextFraction = 0.24f
private const val BoxStart = ContextFraction / (1f + 2f * ContextFraction)
private const val BoxEnd = 1f - BoxStart

private const val SilentAlpha = 0.32f

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun MixViewPopup(
    view: MixTransitionView?,
    visible: Boolean,
    live: Boolean,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = PlayerSettings.smartMixProgress.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { shown = visible }
    val scrimAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "mixViewScrim")
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (visible) {
                    Modifier
                        .containSheetGestures()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onDismiss,
                        )
                } else {
                    Modifier
                },
            )
            .background(ScrimColor.copy(alpha = ScrimColor.alpha * scrimAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(180)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.92f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.95f),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = PopupMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp)
                    .clip(PopupShape)
                    .then(
                        if (reduceDynamicBlur) {
                            Modifier.background(PopupColor)
                        } else {
                            Modifier
                                .optimizedHazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.regular(PopupColor),
                                )
                                .background(PopupColor.copy(alpha = 0.94f))
                        },
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MixViewContent(view ?: PlaceholderView, progress, live)
            }
        }
    }
}

private val PlaceholderDeck = MixDeck(
    title = "",
    artist = "",
    artworkUrl = null,
    bpm = 0.0,
    key = "",
    durationMs = 0L,
    waveStart = 0.0,
    waveEnd = 0.0,
    analysing = true,
)
private val PlaceholderView = MixTransitionView(
    outgoing = PlaceholderDeck,
    incoming = PlaceholderDeck,
    outStart = 0.0,
    outEnd = 1.0,
    inStart = 0.0,
    inEnd = 1.0,
    beats = 0,
    beatMarks = FloatArray(0),
    fadeOut = FloatArray(0),
    fadeIn = FloatArray(0),
)

@Composable
internal fun MixViewContent(view: MixTransitionView, progress: State<Float>, live: Boolean = true) {
    val sweep = rememberSkeletonSweep(
        view.outgoing.analysing || view.incoming.analysing || view.outgoing.decoding || view.incoming.decoding,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DeckHeader(view.outgoing, sweep, Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(12.dp))
        MixDecks(view, progress, live, sweep)
        Spacer(Modifier.height(12.dp))
        DeckHeader(view.incoming, sweep, Modifier.padding(horizontal = 14.dp))
    }
}

@Composable
private fun rememberSkeletonSweep(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "mixSkeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SKELETON_SWEEP_MS, easing = LinearEasing)),
        label = "mixSkeletonSweep",
    )
}

private const val SKELETON_SWEEP_MS = 1400

private val SkeletonBase = Color.White.copy(alpha = 0.08f)
private val SkeletonHighlight = Color.White.copy(alpha = 0.18f)

private fun skeletonBrush(width: Float, sweep: Float): Brush {
    val band = width * 0.5f
    val startX = -band + sweep * (width + band * 2)
    return Brush.horizontalGradient(
        colors = listOf(SkeletonBase, SkeletonHighlight, SkeletonBase),
        startX = startX,
        endX = startX + band,
    )
}

@Composable
private fun SkeletonBlock(sweep: State<Float>, modifier: Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
    Box(
        modifier
            .clip(shape)
            .drawBehind { drawRect(skeletonBrush(size.width, sweep.value)) },
    )
}

@Composable
private fun DeckHeader(deck: MixDeck, sweep: State<Float>, modifier: Modifier = Modifier) {
    val unknown = deck.title.isEmpty() && deck.analysing
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (unknown) {
            SkeletonBlock(sweep, Modifier.size(46.dp), RoundedCornerShape(6.dp))
        } else {
            AsyncImage(
                model = deck.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (unknown) {
                SkeletonBlock(sweep, Modifier.fillMaxWidth(0.62f).height(15.dp))
                Spacer(Modifier.height(7.dp))
                SkeletonBlock(sweep, Modifier.fillMaxWidth(0.38f).height(12.dp))
            } else {
                Text(
                    text = deck.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = deck.artist,
                    color = SecondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (deck.analysing) {
            Column(horizontalAlignment = Alignment.End) {
                SkeletonBlock(sweep, Modifier.width(48.dp).height(13.dp))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBlock(sweep, Modifier.width(24.dp).height(15.dp))
                    if (deck.durationMs > 0L) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatDeckDuration(deck.durationMs),
                            color = SecondaryText,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        } else {
            DeckNumbers(deck)
        }
    }
}

@Composable
private fun DeckNumbers(deck: MixDeck) {
    Column(horizontalAlignment = Alignment.End) {
        if (deck.bpm > 0) {
            Text(
                text = stringResource(Res.string.mix_view_bpm, deck.bpm.roundToInt()),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(5.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            camelotOf(deck.key)?.let { camelot ->
                Text(
                    text = camelot.code,
                    color = Color(0xFF151515),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(camelot.color)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = formatDeckDuration(deck.durationMs),
                color = SecondaryText,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun MixDecks(view: MixTransitionView, progress: State<Float>, live: Boolean, sweep: State<Float>) {
    val outColors = rememberWaveColors(view.outgoing.artworkUrl)
    val inColors = rememberWaveColors(view.incoming.artworkUrl)
    val pending = view.outgoing.analysing || view.incoming.analysing
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(DecksHeight),
    ) {
        val width = maxWidth
        val panelStart = width * BoxStart
        val panelWidth = width * (BoxEnd - BoxStart)
        Box(
            Modifier
                .offset(x = panelStart, y = 0.dp)
                .width(panelWidth)
                .height(DecksHeight - 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PanelColor.copy(alpha = 0.85f)),
        )
        Canvas(Modifier.fillMaxSize()) {
            val deckHeight = DeckHeight.toPx()
            val topDeck = DeckTop.toPx()
            val bottomDeck = size.height - DeckBottomInset.toPx() - deckHeight
            drawDeck(view, outgoing = true, top = topDeck, height = deckHeight, colors = outColors, sweep = sweep)
            drawDeck(view, outgoing = false, top = bottomDeck, height = deckHeight, colors = inColors, sweep = sweep)
            if (live) {
                val x = size.width * (BoxStart + (BoxEnd - BoxStart) * progress.value.coerceIn(0f, 1f))
                drawLine(
                    color = Color.White.copy(alpha = 0.9f),
                    start = Offset(x, topDeck - 6.dp.toPx()),
                    end = Offset(x, bottomDeck + deckHeight + 6.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = width * ((BoxStart + BoxEnd) / 2f) - width / 2)
                .clip(RoundedCornerShape(14.dp))
                .background(ChipColor)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (pending) {
                SkeletonBlock(sweep, Modifier.width(44.dp).height(13.dp))
            } else {
                val bars = (view.beats / 4f).roundToInt().coerceAtLeast(1)
                Text(
                    text = when {
                        view.beats <= 0 -> stringResource(Res.string.mix_view_seconds, (view.outEnd - view.outStart).roundToInt())
                        bars == 1 -> stringResource(Res.string.mix_view_one_bar)
                        else -> stringResource(Res.string.mix_view_bars, bars)
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun DrawScope.drawDeck(
    view: MixTransitionView,
    outgoing: Boolean,
    top: Float,
    height: Float,
    colors: WaveColors,
    sweep: State<Float>,
) {
    val deck = if (outgoing) view.outgoing else view.incoming
    val boxLeft = size.width * BoxStart
    val boxRight = size.width * BoxEnd
    val boxWidth = boxRight - boxLeft
    val centre = top + height / 2f
    val floor = top + height
    val half = height / 2f * 0.86f
    val fader = if (outgoing) view.fadeOut else view.fadeIn

    clipRect(boxLeft, top, boxRight, floor) {
        view.beatMarks.forEachIndexed { index, mark ->
            val x = boxLeft + boxWidth * mark
            drawLine(
                color = Color.White.copy(alpha = if (index % 4 == 0) 0.14f else 0.06f),
                start = Offset(x, top),
                end = Offset(x, floor),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }

    val bins = deck.low.size
    val (blendStart, blendEnd) = if (outgoing) view.outStart to view.outEnd else view.inStart to view.inEnd
    val context = (blendEnd - blendStart) * ContextFraction
    val shownStart = blendStart - context
    val pxPerSecond = (size.width / ((blendEnd - blendStart) + 2 * context)).toFloat()
    if (bins > 0 && deck.waveEnd > deck.waveStart) {
        val secondsPerBin = (deck.waveEnd - deck.waveStart) / bins
        val column = (secondsPerBin * pxPerSecond).toFloat()
        val swap = view.bassSwapAt
        for (bin in 0 until bins) {
            val x = ((deck.waveStart + bin * secondsPerBin - shownStart) * pxPerSecond).toFloat()
            if (x + column < 0f || x > size.width) continue
            val mid = x + column / 2f
            val inBlend = mid in boxLeft..boxRight
            val blend = ((mid - boxLeft) / boxWidth).coerceIn(0f, 1f)
            val silent = if (outgoing) mid > boxRight else mid < boxLeft
            val alpha = when {
                silent -> SilentAlpha
                inBlend -> 0.55f + 0.45f * sample(fader, blend)
                else -> 1f
            }
            val lowsOn = when {
                swap < 0f || !inBlend -> true
                outgoing -> blend < swap
                else -> blend >= swap
            }
            val width = column + 0.6f
            if (lowsOn) bar(x, width, centre, deck.low[bin] * half, colors.low.copy(alpha = alpha))
            bar(x, width, centre, deck.mid[bin] * half, colors.mid.copy(alpha = alpha))
            bar(x, width, centre, deck.high[bin] * half, colors.high.copy(alpha = alpha))
        }
    } else if (deck.analysing || deck.decoding) {
        drawWaveSkeleton(centre, half, sweep.value)
    } else {
        drawLine(
            color = colors.mid.copy(alpha = 0.35f),
            start = Offset(0f, centre),
            end = Offset(size.width, centre),
            strokeWidth = 1.dp.toPx(),
        )
    }

    val frame = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round)
    val edge = Path().apply {
        if (outgoing) {
            moveTo(boxLeft, top)
            lineTo(boxRight, top)
            lineTo(boxRight, floor)
        } else {
            moveTo(boxLeft, floor)
            lineTo(boxLeft, top)
            lineTo(boxRight, top)
        }
    }
    drawPath(edge, FrameColor, style = frame)

    if (fader.isNotEmpty()) {
        val line = Path()
        fader.forEachIndexed { index, level ->
            val x = boxLeft + boxWidth * index / (fader.size - 1).coerceAtLeast(1)
            val y = floor - (floor - centre) * level
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        drawPath(line, FaderColor, style = frame)
    }
}

@Composable
private fun rememberWaveColors(artworkUrl: String?): WaveColors {
    val context = LocalPlatformContext.current
    var target by remember(artworkUrl) {
        mutableStateOf(artworkUrl?.let { WaveColorCache[it] } ?: DefaultWaveColors)
    }
    LaunchedEffect(artworkUrl) {
        val url = artworkUrl ?: return@LaunchedEffect
        if (WaveColorCache[url] != null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(128)
            .forPixelAccess()
            .build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
            ?.image?.toImageBitmap() ?: return@LaunchedEffect
        val colors = withContext(Dispatchers.Default) { waveColorsOf(artworkMeshColors(bitmap)) }
        WaveColorCache[url] = colors
        target = colors
    }
    val fade = tween<Color>(WAVE_COLOR_FADE_MS, easing = FastOutSlowInEasing)
    val low by animateColorAsState(target.low, fade, label = "waveLow")
    val mid by animateColorAsState(target.mid, fade, label = "waveMid")
    val high by animateColorAsState(target.high, fade, label = "waveHigh")
    return WaveColors(low, mid, high)
}

private val WaveColorCache = java.util.concurrent.ConcurrentHashMap<String, WaveColors>()

private const val WAVE_COLOR_FADE_MS = 900

private fun waveColorsOf(colors: List<Color>): WaveColors {
    val hsv = colors.map { hsvOf(it) }
    if (hsv.isEmpty()) return DefaultWaveColors
    val vivid = hsv.maxBy { it[1] * (0.4f + it[2]) }
    if (vivid[1] < GREY_SATURATION) {
        return WaveColors(Color(0xFF7D7D86), Color(0xFFB9B9C2), Color(0xFFF2F2F5))
    }
    val contrast = hsv
        .filter { it !== vivid && it[1] >= GREY_SATURATION }
        .maxByOrNull { hueDistance(it[0], vivid[0]) * it[1] }
        ?.takeIf { hueDistance(it[0], vivid[0]) >= MIN_HUE_GAP }
        ?.get(0)
        ?: ((vivid[0] + 40f) % 360f)
    return WaveColors(
        low = Color.hsv(vivid[0], vivid[1].coerceIn(0.55f, 0.9f), 0.88f),
        mid = Color.hsv(contrast, 0.62f, 0.95f),
        high = Color.hsv(vivid[0], 0.16f, 1f),
    )
}

private const val GREY_SATURATION = 0.14f

private const val MIN_HUE_GAP = 35f

private fun hueDistance(a: Float, b: Float): Float {
    val d = kotlin.math.abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

private fun hsvOf(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return floatArrayOf(hue, if (max == 0f) 0f else delta / max, max)
}

private fun DrawScope.drawWaveSkeleton(centre: Float, half: Float, sweep: Float) {
    val brush = skeletonBrush(size.width, sweep)
    val column = 2.dp.toPx()
    val step = 4.dp.toPx()
    val corner = CornerRadius(column / 2f)
    var index = 0
    var x = 0f
    while (x < size.width) {
        val shape = 0.22f + 0.5f * abs(sin(index * 0.37f) * cos(index * 0.113f))
        val extent = half * shape
        drawRoundRect(brush, topLeft = Offset(x, centre - extent), size = Size(column, extent * 2f), cornerRadius = corner)
        x += step
        index++
    }
}

private fun DrawScope.bar(x: Float, width: Float, centre: Float, extent: Float, color: Color) {
    if (extent <= 0.3f) return
    drawRect(color, topLeft = Offset(x, centre - extent), size = Size(width, extent * 2f))
}

private fun sample(curve: FloatArray, at: Float): Float {
    if (curve.isEmpty()) return 1f
    val position = at * (curve.size - 1)
    val index = position.toInt().coerceIn(0, curve.size - 1)
    val next = (index + 1).coerceAtMost(curve.size - 1)
    return curve[index] + (curve[next] - curve[index]) * (position - index)
}

private fun formatDeckDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val seconds = ms / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

private class Camelot(val code: String, val color: Color)

private val PitchClasses = mapOf(
    "C" to 0, "C♯" to 1, "D♭" to 1, "D" to 2, "D♯" to 3, "E♭" to 3, "E" to 4, "F" to 5,
    "F♯" to 6, "G♭" to 6, "G" to 7, "G♯" to 8, "A♭" to 8, "A" to 9, "A♯" to 10, "B♭" to 10, "B" to 11,
)

private val MajorCamelot = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
private val MinorCamelot = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

private val CamelotHues = floatArrayOf(165f, 135f, 100f, 55f, 30f, 10f, 345f, 310f, 280f, 245f, 215f, 190f)

private fun camelotOf(key: String): Camelot? {
    val parts = key.trim().replace('#', '♯').replace('b', '♭').split(' ')
    if (parts.size != 2) return null
    val pitch = PitchClasses[parts[0].replaceFirstChar { it.uppercase() }] ?: return null
    val minor = when (parts[1].lowercase(Locale.ROOT)) {
        "minor" -> true
        "major" -> false
        else -> return null
    }
    val number = if (minor) MinorCamelot[pitch] else MajorCamelot[pitch]
    val hue = CamelotHues[number - 1]
    val color = if (minor) Color.hsv(hue, 0.32f, 0.93f) else Color.hsv(hue, 0.62f, 0.9f)
    return Camelot("$number${if (minor) "A" else "B"}", color)
}

object MixViewRequest {
    var open by mutableStateOf(false)
}

private const val PREVIEW_WAIT_MS = 2_500L

@Composable
internal fun MixViewOverlay(hazeState: HazeState) {
    val blend by PlayerSettings.smartMixView.collectAsStateWithLifecycle()
    val preview by PlayerSettings.smartMixPreview.collectAsStateWithLifecycle()
    var held by remember { mutableStateOf<MixTransitionView?>(null) }
    var heldBlend by remember { mutableStateOf(false) }
    val open = MixViewRequest.open
    val currentBlend = blend
    val currentPreview = preview
    LaunchedEffect(open) { PlayerSettings.setMixViewOpen(open) }
    DisposableEffect(Unit) { onDispose { PlayerSettings.setMixViewOpen(false) } }
    LaunchedEffect(currentBlend, currentPreview, open) {
        when {
            currentBlend != null -> {
                held = currentBlend
                heldBlend = true
            }
            !open -> {
                delay(240)
                held = null
                heldBlend = false
            }
            heldBlend -> Unit
            currentPreview != null -> held = currentPreview
        }
    }
    val unavailable = stringResource(Res.string.automix_overview_unavailable)
    LaunchedEffect(open, held == null) {
        if (!open || held != null) return@LaunchedEffect
        delay(PREVIEW_WAIT_MS)
        if (MixViewRequest.open && held == null) {
            MixViewRequest.open = false
            PlayerPlatform.host.showMessage(unavailable)
        }
    }
    PlayerBackHandler(enabled = open) { MixViewRequest.open = false }
    if (!open && held == null) return
    MixViewPopup(
        view = held,
        visible = open,
        live = currentBlend != null,
        hazeState = hazeState,
        onDismiss = { MixViewRequest.open = false },
    )
}
