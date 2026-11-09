// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Convx's ConvxIntro (GPL-3.0, see THIRD_PARTY_NOTICES.md).
package com.bxane.nuvx.ui.intro

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Cold-start intro, drawn over the already-composed app: a white disc springs in
 * carrying the nuvx signature, beats three times — a wave pumping outwards and
 * [onBeat] (a strong vibration) on the same frame each time — then zooms through
 * the camera and a hole opens from the centre onto the live app underneath.
 *
 * Plain Compose with no project dependencies, so the watch compiles this same
 * file (wearos/build.gradle.kts). One clock drives every layer; it advances per
 * frame, capped at [MAX_STEP_MS], so a startup stall pauses the intro instead of
 * skipping it. Skipped outright when the system animator scale is 0.
 */
@Composable
fun NuvxIntro(logo: ImageVector, onBeat: () -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val beat by rememberUpdatedState(onBeat)
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        if (!reducedMotion) {
            var last = withFrameNanos { it }
            while (clock.floatValue < TOTAL_MS) {
                val now = withFrameNanos { it }
                val before = clock.floatValue
                val after = (before + ((now - last) / 1e6f).coerceAtMost(MAX_STEP_MS)).coerceAtMost(TOTAL_MS)
                last = now
                clock.floatValue = after
                if (BEATS_MS.any { it > before && it <= after }) beat()
            }
        }
        onFinished()
    }
    if (reducedMotion) return

    // A raw path under a canvas scale: a VectorPainter would re-rasterise a fresh
    // bitmap every frame of the zoom, which is what makes an intro like this stutter.
    val path = remember(logo) { Path().apply { fillType = PathFillType.EvenOdd; addGroup(logo.root, Offset.Zero) } }
    val aspect = logo.viewportHeight / logo.viewportWidth
    val hole = remember { Path() }
    Canvas(
        Modifier
            .fillMaxSize()
            // Swallow touches until the app is revealed.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        val t = clock.floatValue
        val c = center
        val r0 = 52.dp.toPx()
        val cover = hypot(size.width, size.height) / 2f * 1.05f

        val holeR = cover * outCubic(phase(t, REVEAL_START, TOTAL_MS))
        hole.reset()
        if (holeR > 0f) hole.addOval(Rect(c, holeR))
        clipPath(hole, ClipOp.Difference) {
            drawRect(Color.Black)

            // The waves, under the disc: each leaves its edge on its beat.
            BEATS_MS.forEach { b ->
                val x = (t - b) / RIPPLE_MS
                if (x in 0f..1f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.5f * (1f - x)),
                        radius = r0 * (1f + 2.6f * outCubic(x)),
                        center = c,
                        style = Stroke(width = 2.5.dp.toPx() * (1f - 0.6f * x)),
                    )
                }
            }

            val pump = BEATS_MS.sumOf { pulse(t - it).toDouble() }.toFloat()
            val zoom = inCubic(phase(t, ZOOM_START, ZOOM_END))
            val discR = r0 * outBack(phase(t, 0f, 450f)) * (1f + 0.1f * pump) + (cover - r0) * zoom
            drawCircle(Color.White, radius = discR.coerceAtLeast(0f), center = c)

            val glyphIn = phase(t, 100f, 550f)
            // Outgrows the disc while zooming, so the black stroke sweeps past the camera.
            val w = r0 * 2f * 0.72f * outBack(glyphIn) * (1f + 0.1f * pump) * (1f + 16f * zoom.pow(1.6f))
            val spin = -140f * (1f - outCubic(glyphIn)) + 35f * zoom
            if (w > 0f) {
                rotate(spin, pivot = c) {
                    translate(c.x - w / 2f, c.y - w * aspect / 2f) {
                        scale(w / logo.viewportWidth, pivot = Offset.Zero) { drawPath(path, Color.Black) }
                    }
                }
            }
        }
    }
}

/** Every path of [group], with its groups' offsets applied (the logo sits in a translated group). */
private fun Path.addGroup(group: VectorGroup, offset: Offset) {
    val at = offset + Offset(group.translationX, group.translationY)
    for (node in group) when (node) {
        is VectorGroup -> addGroup(node, at)
        is VectorPath -> addPath(node.pathData.toPath(), at)
    }
}

private const val TOTAL_MS = 1950f
private const val ZOOM_START = 1250f
private const val ZOOM_END = 1730f
private const val REVEAL_START = 1650f
private const val RIPPLE_MS = 600f
private const val PULSE_MS = 220f

/** Most the clock advances per frame (~two 60 Hz frames), so stalls pause rather than skip. */
private const val MAX_STEP_MS = 34f

/** Three eighth notes at 120 BPM: "da-da-dum". */
private val BEATS_MS = listOf(600f, 850f, 1100f)

private fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)

private fun pulse(dt: Float) = if (dt in 0f..PULSE_MS) sin(PI.toFloat() * dt / PULSE_MS) else 0f

private fun outCubic(x: Float) = 1f - (1f - x).pow(3)

private fun inCubic(x: Float) = x * x * x

private fun outBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
}
