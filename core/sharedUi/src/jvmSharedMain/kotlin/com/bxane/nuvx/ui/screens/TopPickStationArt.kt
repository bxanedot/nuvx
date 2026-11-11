package com.bxane.nuvx.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import com.bxane.nuvx.ui.components.MOTION_FRAME_GAP_MS
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import com.bxane.nuvx.sharedui.resources.*
import kotlin.math.PI
import kotlin.math.sin

private const val STATION_PERIOD_MS = 9_000

@Composable
internal fun loopPhase(periodMs: Int): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val running = LocalArtworkMotion.current
    val scrolling = LocalArtworkScrolling.current
    LaunchedEffect(running, periodMs, scrolling) {
        if (!running) return@LaunchedEffect
        var last = Long.MIN_VALUE
        while (true) {
            if (scrolling()) {
                snapshotFlow { scrolling() }.first { !it }
                last = Long.MIN_VALUE
            }
            withFrameNanos { now ->
                if (last != Long.MIN_VALUE) {
                    phase.floatValue = (phase.floatValue + (now - last) / (periodMs * 1_000_000f)) % 1f
                }
                last = now
            }
            delay(MOTION_FRAME_GAP_MS)
        }
    }
    return phase
}

internal val RetainedArt = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

internal fun wave(phase: Float, offset: Float): Float = sin(2f * PI.toFloat() * (phase + offset))

private class SizeCache<T : Any>(private val build: (Size) -> T) {
    private var size = Size.Unspecified
    private var value: T? = null

    fun get(current: Size): T {
        val cached = value
        if (cached != null && current == size) return cached
        size = current
        return build(current).also { value = it }
    }
}

private val PersonalLayers = listOf(
    Triple(1.00f, Color(0xFFFE5E83), Color(0xFFFA6196)),
    Triple(0.66f, Color(0xFFFE5065), Color(0xFFFC657C)),
    Triple(0.43f, Color(0xFFFB594C), Color(0xFFFC776D)),
    Triple(0.21f, Color(0xFFFB6729), Color(0xFFF98A48)),
)

private class PersonalBrushes(val background: Brush, val layers: List<Brush>)

@Composable
internal fun PersonalStationArt(modifier: Modifier) {
    val phase by loopPhase(STATION_PERIOD_MS)
    val path = remember { Path() }
    val brushes = remember {
        SizeCache { size ->
            PersonalBrushes(
                background = Brush.horizontalGradient(
                    0f to Color(0xFFF7334F),
                    0.38f to Color(0xFFFA6094),
                    0.75f to Color(0xFFF02A4A),
                    1f to Color(0xFFEE213D),
                ),
                layers = PersonalLayers.map { (rest, deep, light) ->
                    Brush.horizontalGradient(listOf(deep, light), startX = 0f, endX = size.width * rest)
                },
            )
        }
    }
    Canvas(modifier.then(RetainedArt)) {
        val w = size.width
        val h = size.height
        val cached = brushes.get(size)
        drawRect(cached.background)
        PersonalLayers.forEachIndexed { index, (rest, _, _) ->
            val tip = if (index == 0) rest else rest + 0.07f * wave(phase, index * 0.19f)
            path.reset()
            path.moveTo(0f, 0f)
            path.lineTo(w * tip, h / 2f)
            path.lineTo(0f, h)
            path.close()
            drawPath(path, cached.layers[index])
        }
    }
}

private val DiscoveryTopColors = listOf(
    Color(0xFF3A2496), Color(0xFF2F4FB0), Color(0xFF3B30A0),
    Color(0xFF2E7FB8), Color(0xFF27988A), Color(0xFF3C8DA8),
)
private val DiscoveryBottomColors = listOf(
    Color(0xFF3A2496), Color(0xFF333EA4), Color(0xFF2F62B6),
    Color(0xFF288B99), Color(0xFF3477BA), Color(0xFF42A296),
)

private val DiscoveryEdges = listOf(0f, 0.24f, 0.44f, 0.62f, 0.78f, 0.91f, 1.02f)

private class DiscoveryBrushes(val background: Brush, val top: List<Brush>, val bottom: List<Brush>, val arrow: Brush)

@Composable
internal fun DiscoveryStationArt(modifier: Modifier) {
    val phase by loopPhase(STATION_PERIOD_MS)
    val path = remember { Path() }
    val brushes = remember {
        SizeCache { size ->
            val apex = Offset(size.width, size.height / 2f)
            fun shards(edgeY: Float, colors: List<Color>) = (0 until DiscoveryEdges.lastIndex).map { i ->
                val mid = size.width * (DiscoveryEdges[i] + DiscoveryEdges[i + 1]) / 2f
                val base = colors[i % colors.size]
                Brush.linearGradient(listOf(lerp(base, Color.White, 0.10f), base), start = apex, end = Offset(mid, edgeY))
            }
            DiscoveryBrushes(
                background = Brush.linearGradient(
                    listOf(Color(0xFF2A869F), Color(0xFF3477BA)),
                    Offset.Zero,
                    Offset(size.width, size.height),
                ),
                top = shards(0f, DiscoveryTopColors),
                bottom = shards(size.height, DiscoveryBottomColors),
                arrow = Brush.horizontalGradient(
                    0f to Color(0xFF502798),
                    0.55f to Color(0xFF421C88),
                    1f to Color(0xFF4A2292),
                ),
            )
        }
    }
    Canvas(modifier.then(RetainedArt)) {
        val w = size.width
        val h = size.height
        val cached = brushes.get(size)
        drawRect(cached.background)
        fun fan(edgeY: Float, shards: List<Brush>, offset: Float) {
            var previous = DiscoveryEdges[0]
            for (i in 1..DiscoveryEdges.lastIndex) {
                val rest = DiscoveryEdges[i]
                val edge = if (i == DiscoveryEdges.lastIndex) rest else rest + 0.06f * wave(phase, offset + i * 0.11f)
                path.reset()
                path.moveTo(w, h / 2f)
                path.lineTo(w * previous, edgeY)
                path.lineTo(w * edge, edgeY)
                path.close()
                drawPath(path, shards[i - 1])
                previous = edge
            }
        }
        fan(0f, cached.top, 0f)
        fan(h, cached.bottom, 0.5f)

        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(w, h / 2f)
        path.lineTo(0f, h)
        path.close()
        drawPath(path, cached.arrow)
        val inner = 0.08f + 0.04f * wave(phase, 0.25f)
        path.reset()
        path.moveTo(0f, h * inner)
        path.lineTo(w * (0.9f - inner), h / 2f)
        path.lineTo(0f, h * (1f - inner))
        path.close()
        drawPath(path, DiscoveryInnerArrow)
    }
}

private val DiscoveryInnerArrow = Color(0xFF3C1981).copy(alpha = 0.35f)
