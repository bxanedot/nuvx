package com.bxane.nuvx.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.PathOperation
import kotlin.math.exp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.data.model.TopPickMood
import com.bxane.nuvx.sharedui.resources.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class MoodStyle(
    val background: Color,
    val stroke: Color,
    val periodMs: Int,
    val eyebrow: Color = lerp(stroke, Color.White, 0.35f),
)

internal fun TopPickMood.style(): MoodStyle = when (this) {
    TopPickMood.ENERGY -> MoodStyle(Color(0xFF1EBD38), Color(0xFFE2F6A2), 3_000, Color(0xFFE8FF9C))
    TopPickMood.RELAX -> MoodStyle(Color(0xFF31AFF3), Color(0xFFB4E9FF), 5_600, Color(0xFFD6F3FF))
    TopPickMood.FEELING_BLUE -> MoodStyle(Color(0xFF253AB2), Color(0xFF6CC4FF), 3_700, Color(0xFFA9DBFF))
    TopPickMood.FEEL_GOOD -> MoodStyle(Color(0xFFF2A900), Color(0xFFFFE8A0), 4_000, Color(0xFFFFF27A))
    TopPickMood.FOCUS -> MoodStyle(Color(0xFFF28400), Color(0xFFFFD6A4), 3_600, Color(0xFFFFE2B8))
    TopPickMood.HEARTBREAK -> MoodStyle(Color(0xFFEA3520), Color(0xFFFFA690), 2_700, Color(0xFFFFC9BC))
    TopPickMood.LOVE -> MoodStyle(Color(0xFFF34CA0), Color(0xFFFFB8DC), 1_700, Color(0xFFFFD0EA))
    TopPickMood.AFTER_HOURS -> MoodStyle(Color(0xFF3A1F78), Color(0xFFC9B6FF), 4_500)
    TopPickMood.ALONE_TIME -> MoodStyle(Color(0xFF2C6E8C), Color(0xFFA9E2F5), 4_000)
    TopPickMood.CHILL -> MoodStyle(Color(0xFF138A86), Color(0xFFA6F0E6), 5_000)
    TopPickMood.COMMUTING -> MoodStyle(Color(0xFF17855F), Color(0xFFA8EFD0), 2_400)
    TopPickMood.EATING_COOKING -> MoodStyle(Color(0xFFD9621A), Color(0xFFFFD3A8), 3_200)
    TopPickMood.EVENING -> MoodStyle(Color(0xFF2A3A8C), Color(0xFFB8C6FF), 6_000)
    TopPickMood.FITNESS -> MoodStyle(Color(0xFF8DB014), Color(0xFFEFFFA8), 2_000)
    TopPickMood.GAMING -> MoodStyle(Color(0xFF239A2A), Color(0xFFB8FFB0), 6_000)
    TopPickMood.HOME -> MoodStyle(Color(0xFF0E8A6C), Color(0xFFA8F2DB), 4_000)
    TopPickMood.MORNING -> MoodStyle(Color(0xFFF08C1A), Color(0xFFFFE2A6), 5_000)
    TopPickMood.MOTIVATION -> MoodStyle(Color(0xFFC01E4A), Color(0xFFFFB3C6), 1_800)
    TopPickMood.OUTDOORS -> MoodStyle(Color(0xFF4C8A1E), Color(0xFFD4F5A8), 6_000)
    TopPickMood.PARTY -> MoodStyle(Color(0xFFC2287A), Color(0xFFFFC0E0), 3_000)
    TopPickMood.SLEEP -> MoodStyle(Color(0xFF26266E), Color(0xFFC2C6FF), 4_500)
    TopPickMood.SOCIAL -> MoodStyle(Color(0xFFE0507A), Color(0xFFFFD0DE), 3_000)
    TopPickMood.SPORTS -> MoodStyle(Color(0xFF1C6CD0), Color(0xFFB8D8FF), 3_000)
    TopPickMood.SUMMERTIME -> MoodStyle(Color(0xFFFF8C00), Color(0xFFFFE6A8), 8_000)
    TopPickMood.HOLIDAY -> MoodStyle(Color(0xFFC62828), Color(0xFFFFC8C8), 8_000)
    TopPickMood.WEEKEND -> MoodStyle(Color(0xFF7A3CC2), Color(0xFFE2CCFF), 2_400)
    TopPickMood.WELLBEING -> MoodStyle(Color(0xFF36A98A), Color(0xFFC6F7E6), 5_000)
    TopPickMood.WORK -> MoodStyle(Color(0xFF3E63C9), Color(0xFFC4D2FF), 2_000)
}

internal fun TopPickMood.titleRes() = when (this) {
    TopPickMood.ENERGY -> Res.string.mood_energy
    TopPickMood.RELAX -> Res.string.mood_relax
    TopPickMood.FEELING_BLUE -> Res.string.mood_feeling_blue
    TopPickMood.FEEL_GOOD -> Res.string.mood_feel_good
    TopPickMood.FOCUS -> Res.string.mood_focus
    TopPickMood.HEARTBREAK -> Res.string.mood_heartbreak
    TopPickMood.LOVE -> Res.string.mood_love
    TopPickMood.AFTER_HOURS -> Res.string.mood_after_hours
    TopPickMood.ALONE_TIME -> Res.string.mood_alone_time
    TopPickMood.CHILL -> Res.string.mood_chill
    TopPickMood.COMMUTING -> Res.string.mood_commuting
    TopPickMood.EATING_COOKING -> Res.string.mood_eating_cooking
    TopPickMood.EVENING -> Res.string.mood_evening
    TopPickMood.FITNESS -> Res.string.mood_fitness
    TopPickMood.GAMING -> Res.string.mood_gaming
    TopPickMood.HOME -> Res.string.mood_home
    TopPickMood.MORNING -> Res.string.mood_morning
    TopPickMood.MOTIVATION -> Res.string.mood_motivation
    TopPickMood.OUTDOORS -> Res.string.mood_outdoors
    TopPickMood.PARTY -> Res.string.mood_party
    TopPickMood.SLEEP -> Res.string.mood_sleep
    TopPickMood.SOCIAL -> Res.string.mood_social
    TopPickMood.SPORTS -> Res.string.mood_sports
    TopPickMood.SUMMERTIME -> Res.string.mood_summertime
    TopPickMood.HOLIDAY -> Res.string.mood_holiday
    TopPickMood.WEEKEND -> Res.string.mood_weekend
    TopPickMood.WELLBEING -> Res.string.mood_wellbeing
    TopPickMood.WORK -> Res.string.mood_work
}

internal fun TopPickMood.lineRes() = when (this) {
    TopPickMood.ENERGY -> Res.string.mood_energy_line
    TopPickMood.RELAX -> Res.string.mood_relax_line
    TopPickMood.FEELING_BLUE -> Res.string.mood_feeling_blue_line
    TopPickMood.FEEL_GOOD -> Res.string.mood_feel_good_line
    TopPickMood.FOCUS -> Res.string.mood_focus_line
    TopPickMood.HEARTBREAK -> Res.string.mood_heartbreak_line
    TopPickMood.LOVE -> Res.string.mood_love_line
    TopPickMood.AFTER_HOURS -> Res.string.mood_after_hours_line
    TopPickMood.ALONE_TIME -> Res.string.mood_alone_time_line
    TopPickMood.CHILL -> Res.string.mood_chill_line
    TopPickMood.COMMUTING -> Res.string.mood_commuting_line
    TopPickMood.EATING_COOKING -> Res.string.mood_eating_cooking_line
    TopPickMood.EVENING -> Res.string.mood_evening_line
    TopPickMood.FITNESS -> Res.string.mood_fitness_line
    TopPickMood.GAMING -> Res.string.mood_gaming_line
    TopPickMood.HOME -> Res.string.mood_home_line
    TopPickMood.MORNING -> Res.string.mood_morning_line
    TopPickMood.MOTIVATION -> Res.string.mood_motivation_line
    TopPickMood.OUTDOORS -> Res.string.mood_outdoors_line
    TopPickMood.PARTY -> Res.string.mood_party_line
    TopPickMood.SLEEP -> Res.string.mood_sleep_line
    TopPickMood.SOCIAL -> Res.string.mood_social_line
    TopPickMood.SPORTS -> Res.string.mood_sports_line
    TopPickMood.SUMMERTIME -> Res.string.mood_summertime_line
    TopPickMood.HOLIDAY -> Res.string.mood_holiday_line
    TopPickMood.WEEKEND -> Res.string.mood_weekend_line
    TopPickMood.WELLBEING -> Res.string.mood_wellbeing_line
    TopPickMood.WORK -> Res.string.mood_work_line
}

private class Frame(val left: Float, val top: Float, val side: Float) {
    fun x(f: Float) = left + side * f
    fun y(f: Float) = top + side * f
    fun at(x: Float, y: Float) = Offset(x(x), y(y))
    val center get() = Offset(x(0.5f), y(0.5f))
}

@Composable
internal fun MoodArt(
    mood: TopPickMood,
    style: MoodStyle,
    modifier: Modifier,
    square: Boolean = false,
    still: Boolean = false,
) {
    val loop = if (still) null else loopPhase(style.periodMs)
    val cache = remember(mood) { EmblemCache() }
    Canvas(if (still) modifier else modifier.then(RetainedArt)) {
        drawRect(style.background)
        val w = size.width
        val h = size.height
        val f = if (square) {
            val side = w * 0.70f
            Frame(left = (w - side) / 2f, top = (h - side) / 2f, side = side)
        } else {
            val side = minOf(w * 0.84f, h * 0.62f)
            Frame(left = (w - side) / 2f, top = h * 0.37f - side / 2f, side = side)
        }
        val stroke = cache.stroke((if (square) 2.dp else 2.5.dp).toPx())
        drawEmblem(mood, f, loop?.value ?: mood.stillPhase(), style.stroke, stroke, cache)
    }
}

private fun TopPickMood.stillPhase(): Float = when (this) {
    TopPickMood.FITNESS -> 0.55f
    TopPickMood.WORK -> 0.6f
    else -> 0f
}

private val TAU = 2f * PI.toFloat()

private class EmblemCache {
    private var builtFor = Size.Unspecified
    private val outline = Path()
    val scratch = Path()
    val segment = Path()
    val measure = PathMeasure()
    var length = 0f
        private set
    private var strokeWidth = -1f
    private var strokeStyle = Stroke()

    fun outline(size: Size, measured: Boolean = false, build: (Path) -> Unit): Path {
        if (size != builtFor) {
            outline.reset()
            build(outline)
            if (measured) {
                measure.setPath(outline, false)
                length = measure.length
            }
            builtFor = size
        }
        return outline
    }

    fun stroke(width: Float): Stroke {
        if (width != strokeWidth) {
            strokeWidth = width
            strokeStyle = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        }
        return strokeStyle
    }
}

private fun DrawScope.drawEmblem(
    mood: TopPickMood,
    f: Frame,
    phase: Float,
    color: Color,
    stroke: Stroke,
    cache: EmblemCache,
) {
    val side = f.side
    val path = cache.scratch
    val segment = cache.segment
    when (mood) {
        TopPickMood.ENERGY -> {
            val path = cache.outline(size) { bolt(it, f) }
            val t = (phase - 0.28f) / 0.26f
            val flash = if (t in 0f..1f) sin(PI.toFloat() * t) else 0f
            if (flash > 0f) {
                translate(left = side * 0.07f * flash) { drawPath(path, color.copy(alpha = 0.55f * flash), style = stroke) }
                translate(left = -side * 0.05f * flash) { drawPath(path, color.copy(alpha = 0.35f * flash), style = stroke) }
            }
            drawPath(path, color, style = stroke)
        }
        TopPickMood.RELAX -> {
            val stones = listOf(
                Triple(0.17f, 0.44f, 0.18f),
                Triple(0.38f, 0.68f, 0.28f),
                Triple(0.66f, 0.84f, 0.36f),
            )
            stones.forEachIndexed { index, (cy, width, height) ->
                val sway = wave(phase, index * 0.22f)
                val center = Offset(f.x(0.5f + 0.015f * sway), f.y(cy))
                rotate(degrees = 4f * sway, pivot = center) {
                    drawOval(
                        color = color,
                        topLeft = Offset(center.x - side * width / 2f, center.y - side * height / 2f),
                        size = Size(side * width, side * height),
                        style = stroke,
                    )
                }
            }
        }
        TopPickMood.FEELING_BLUE -> {
            cache.outline(size, measured = true) { spiral(it, f) }
            val length = cache.length
            val from: Float
            val to: Float
            when {
                phase < 0.35f -> { from = 0f; to = length }
                phase < 0.8f -> { from = length * easeInOut((phase - 0.35f) / 0.45f); to = length }
                else -> { from = 0f; to = length * easeInOut((phase - 0.8f) / 0.2f) }
            }
            segment.reset()
            if (to > from) cache.measure.getSegment(from, to, segment, true)
            drawPath(segment, color, style = stroke)
        }
        TopPickMood.FEEL_GOOD -> {
            val path = cache.outline(size) { star(it, f) }
            val pulse = 0.9f + 0.1f * (0.5f + 0.5f * cos(TAU * phase))
            withTransform({
                rotate(8f * wave(phase, 0f), f.center)
                scale(pulse, pulse, f.center)
            }) { drawPath(path, color, style = stroke) }
        }
        TopPickMood.FOCUS -> {
            for (i in 0 until 4) {
                val share = (i + 1 - phase) / 4
                drawCircle(color.copy(alpha = edgeFade(share)), radius = side * 0.5f * share, center = f.center, style = stroke)
            }
        }
        TopPickMood.HEARTBREAK -> {
            val path = cache.outline(size) { heart(it, f) }
            val dash = side * 0.075f
            val gap = side * 0.055f
            drawPath(
                path,
                color,
                style = Stroke(
                    width = stroke.width,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), -phase * (dash + gap) * 2f),
                ),
            )
        }
        TopPickMood.LOVE -> {
            val path = cache.outline(size) { heart(it, f) }
            fun bump(at: Float): Float {
                val d = (phase - at) / 0.06f
                return exp(-d * d)
            }
            val beat = 1f + 0.07f * (bump(0.15f) + 0.7f * bump(0.38f))
            scale(beat, beat, f.center) { drawPath(path, color, style = stroke) }
        }
        TopPickMood.AFTER_HOURS -> {
            drawPath(cache.outline(size) { crescent(it, f, 0.42f, 0.52f, 0.34f) }, color, style = stroke)
            listOf(Triple(0.80f, 0.22f, 0.07f), Triple(0.86f, 0.58f, 0.05f), Triple(0.68f, 0.82f, 0.04f))
                .forEachIndexed { index, (x, y, r) ->
                    val twinkle = 0.25f + 0.75f * (0.5f + 0.5f * wave(phase, index / 3f))
                    sparkle(path, f.at(x, y), side * r)
                    drawPath(path, color.copy(alpha = twinkle), style = stroke)
                }
        }
        TopPickMood.ALONE_TIME -> {
            val center = f.at(0.5f, 0.55f)
            for (i in 0 until 4) {
                val share = (i + phase) / 4
                val rx = side * 0.5f * share
                drawOval(
                    color.copy(alpha = edgeFade(share)),
                    topLeft = Offset(center.x - rx, center.y - rx * 0.42f),
                    size = Size(rx * 2f, rx * 0.84f),
                    style = stroke,
                )
            }
        }
        TopPickMood.CHILL -> {
            clipRect(f.left, f.top, f.x(1f), f.y(1f)) {
                listOf(0.32f, 0.5f, 0.68f).forEachIndexed { row, y ->
                    path.reset()
                    for (k in 0..80) {
                        val u = k / 80f
                        val py = y + 0.05f * sin(TAU * (u / 0.33f - phase - row * 0.15f))
                        if (k == 0) path.moveTo(f.x(u), f.y(py)) else path.lineTo(f.x(u), f.y(py))
                    }
                    drawPath(path, color, style = stroke)
                }
            }
        }
        TopPickMood.COMMUTING -> {
            drawLine(color, f.at(0.06f, 0.95f), f.at(0.44f, 0.14f), stroke.width, StrokeCap.Round)
            drawLine(color, f.at(0.94f, 0.95f), f.at(0.56f, 0.14f), stroke.width, StrokeCap.Round)
            drawLine(color, f.at(0.15f, 0.14f), f.at(0.85f, 0.14f), stroke.width, StrokeCap.Round)
            for (k in 0 until 5) {
                val u = (k / 5f + phase) % 1f
                val y0 = 0.18f + 0.77f * u * u
                val y1 = (y0 + 0.02f + 0.08f * u).coerceAtMost(0.95f)
                drawLine(color.copy(alpha = edgeFade(u)), f.at(0.5f, y0), f.at(0.5f, y1), stroke.width, StrokeCap.Round)
            }
        }
        TopPickMood.EATING_COOKING -> {
            path.reset()
            path.arcTo(Rect(f.x(0.12f), f.y(0.25f), f.x(0.88f), f.y(0.95f)), 0f, 180f, true)
            drawPath(path, color, style = stroke)
            drawLine(color, f.at(0.05f, 0.6f), f.at(0.95f, 0.6f), stroke.width, StrokeCap.Round)
            listOf(0.36f, 0.5f, 0.64f).forEachIndexed { index, x ->
                val rise = (phase + index / 3f) % 1f
                path.reset()
                for (k in 0..30) {
                    val u = k / 30f
                    val px = x + 0.035f * sin(TAU * (u * 1.2f + rise))
                    val py = 0.5f - 0.3f * u - 0.1f * rise
                    if (k == 0) path.moveTo(f.x(px), f.y(py)) else path.lineTo(f.x(px), f.y(py))
                }
                drawPath(path, color.copy(alpha = sin(PI.toFloat() * rise)), style = stroke)
            }
        }
        TopPickMood.EVENING -> {
            val horizon = 0.62f
            val sink = 0.1f * (0.5f - 0.5f * cos(TAU * phase))
            clipRect(f.left - side, f.top - side, f.x(2f), f.y(horizon)) {
                drawCircle(color, radius = side * 0.26f, center = f.at(0.5f, horizon - 0.02f + sink), style = stroke)
            }
            drawLine(color, f.at(0.04f, horizon), f.at(0.96f, horizon), stroke.width, StrokeCap.Round)
            drawLine(color, f.at(0.2f, horizon + 0.11f), f.at(0.8f, horizon + 0.11f), stroke.width, StrokeCap.Round)
            drawLine(color, f.at(0.36f, horizon + 0.22f), f.at(0.64f, horizon + 0.22f), stroke.width, StrokeCap.Round)
        }
        TopPickMood.FITNESS -> {
            val trace = cache.outline(size, measured = true) { out ->
                listOf(0f to 0.55f, 0.28f to 0.55f, 0.36f to 0.42f, 0.44f to 0.76f, 0.52f to 0.18f, 0.6f to 0.84f, 0.67f to 0.55f, 1f to 0.55f)
                    .forEachIndexed { index, (x, y) -> if (index == 0) out.moveTo(f.x(x), f.y(y)) else out.lineTo(f.x(x), f.y(y)) }
            }
            drawPath(trace, color.copy(alpha = 0.3f), style = stroke)
            val length = cache.length
            val head = phase * 1.4f * length
            segment.reset()
            cache.measure.getSegment((head - 0.45f * length).coerceAtLeast(0f), head.coerceAtMost(length), segment, true)
            drawPath(segment, color, style = stroke)
        }
        TopPickMood.GAMING -> {
            listOf(0.9f, 0.62f, 0.34f).forEachIndexed { index, size ->
                val turn = (if (index % 2 == 0) 1f else -1f) * phase * 90f
                rotate(turn, f.center) {
                    drawRect(
                        color,
                        topLeft = f.at(0.5f - size / 2f, 0.5f - size / 2f),
                        size = Size(side * size, side * size),
                        style = stroke,
                    )
                }
            }
        }
        TopPickMood.HOME -> {
            val house = cache.outline(size) { out ->
                listOf(0.5f to 0.1f, 0.94f to 0.5f, 0.82f to 0.5f, 0.82f to 0.92f, 0.18f to 0.92f, 0.18f to 0.5f, 0.06f to 0.5f)
                    .forEachIndexed { index, (x, y) -> if (index == 0) out.moveTo(f.x(x), f.y(y)) else out.lineTo(f.x(x), f.y(y)) }
                out.close()
            }
            drawPath(house, color, style = stroke)
            drawRect(color, topLeft = f.at(0.41f, 0.66f), size = Size(side * 0.18f, side * 0.26f), style = stroke)
            val glow = 0.5f + 0.5f * wave(phase, 0f)
            drawRect(color.copy(alpha = 0.15f + 0.45f * glow), topLeft = f.at(0.27f, 0.56f), size = Size(side * 0.1f, side * 0.1f))
            drawRect(color, topLeft = f.at(0.27f, 0.56f), size = Size(side * 0.1f, side * 0.1f), style = stroke)
        }
        TopPickMood.MORNING -> {
            val horizon = 0.72f
            val sun = f.at(0.5f, horizon - 0.03f - 0.05f * (0.5f + 0.5f * wave(phase, 0f)))
            clipRect(f.left - side, f.top - side, f.x(2f), f.y(horizon)) {
                drawCircle(color, radius = side * 0.2f, center = sun, style = stroke)
                for (k in 0 until 9) {
                    val angle = PI.toFloat() + (k + 0.5f) * PI.toFloat() / 9f + phase * 0.35f
                    val a = Offset(sun.x + side * 0.28f * cos(angle), sun.y + side * 0.28f * sin(angle))
                    val b = Offset(sun.x + side * 0.38f * cos(angle), sun.y + side * 0.38f * sin(angle))
                    drawLine(color, a, b, stroke.width, StrokeCap.Round)
                }
            }
            drawLine(color, f.at(0.04f, horizon), f.at(0.96f, horizon), stroke.width, StrokeCap.Round)
        }
        TopPickMood.MOTIVATION -> {
            for (k in 0 until 3) {
                val u = (k / 3f + phase) % 1f
                val y = 0.82f - 0.66f * u
                path.reset()
                path.moveTo(f.x(0.22f), f.y(y + 0.17f))
                path.lineTo(f.x(0.5f), f.y(y))
                path.lineTo(f.x(0.78f), f.y(y + 0.17f))
                drawPath(path, color.copy(alpha = sin(PI.toFloat() * u)), style = stroke)
            }
        }
        TopPickMood.OUTDOORS -> {
            val ridge = cache.outline(size) { out ->
                listOf(0.02f to 0.86f, 0.36f to 0.36f, 0.55f to 0.62f, 0.7f to 0.44f, 0.98f to 0.86f)
                    .forEachIndexed { index, (x, y) -> if (index == 0) out.moveTo(f.x(x), f.y(y)) else out.lineTo(f.x(x), f.y(y)) }
            }
            drawPath(ridge, color, style = stroke)
            drawLine(color, f.at(0.02f, 0.86f), f.at(0.98f, 0.86f), stroke.width, StrokeCap.Round)
            drawCircle(color, radius = side * 0.09f, center = f.at(0.78f + 0.02f * wave(phase, 0.25f), 0.2f + 0.02f * wave(phase, 0f)), style = stroke)
        }
        TopPickMood.PARTY -> {
            listOf(
                Triple(0.24f, 0.24f, 0.14f), Triple(0.72f, 0.18f, 0.11f), Triple(0.52f, 0.5f, 0.17f),
                Triple(0.2f, 0.72f, 0.1f), Triple(0.8f, 0.66f, 0.13f), Triple(0.56f, 0.88f, 0.08f),
            ).forEachIndexed { index, (x, y, r) ->
                val pop = 0.85f + 0.15f * wave(phase, index / 6f)
                val spin = (if (index % 2 == 0) 1f else -1f) * phase * 360f
                fivePointStar(path, f.at(x, y), side * r)
                withTransform({
                    rotate(spin, f.at(x, y))
                    scale(pop, pop, f.at(x, y))
                }) { drawPath(path, color, style = stroke) }
            }
        }
        TopPickMood.SLEEP -> {
            val breath = 1f + 0.04f * wave(phase, 0f)
            scale(breath, breath, f.center) {
                path.reset()
                path.arcTo(Rect(f.x(0.12f), f.y(0.2f), f.x(0.88f), f.y(0.66f)), 10f, 160f, true)
                drawPath(path, color, style = stroke)
                for (k in 0 until 5) {
                    val angle = (30f + k * 30f) * PI.toFloat() / 180f
                    val base = Offset(f.x(0.5f + 0.38f * cos(angle)), f.y(0.43f + 0.23f * sin(angle)))
                    val tip = Offset(base.x + side * 0.08f * cos(angle), base.y + side * 0.1f * sin(angle))
                    drawLine(color, base, tip, stroke.width, StrokeCap.Round)
                }
            }
        }
        TopPickMood.SOCIAL -> {
            val gap = 0.12f + 0.07f * wave(phase, 0f)
            drawCircle(color, radius = side * 0.27f, center = f.at(0.5f - gap, 0.5f), style = stroke)
            drawCircle(color, radius = side * 0.27f, center = f.at(0.5f + gap, 0.5f), style = stroke)
        }
        TopPickMood.SPORTS -> {
            val r = side * 0.44f
            drawCircle(color, radius = r, center = f.center, style = stroke)
            path.reset()
            path.addOval(Rect(f.center, r))
            clipPath(path) {
                rotate(phase * 360f, f.center) {
                    drawCircle(color, radius = r * 0.95f, center = Offset(f.center.x - r * 1.25f, f.center.y), style = stroke)
                    drawCircle(color, radius = r * 0.95f, center = Offset(f.center.x + r * 1.25f, f.center.y), style = stroke)
                }
            }
        }
        TopPickMood.SUMMERTIME -> {
            drawCircle(color, radius = side * 0.22f, center = f.center, style = stroke)
            rotate(phase * 30f, f.center) {
                for (k in 0 until 12) {
                    val angle = k * TAU / 12f
                    drawLine(
                        color,
                        Offset(f.center.x + side * 0.31f * cos(angle), f.center.y + side * 0.31f * sin(angle)),
                        Offset(f.center.x + side * 0.46f * cos(angle), f.center.y + side * 0.46f * sin(angle)),
                        stroke.width,
                        StrokeCap.Round,
                    )
                }
            }
        }
        TopPickMood.HOLIDAY -> {
            rotate(phase * 60f, f.center) {
                for (k in 0 until 6) {
                    val angle = k * TAU / 6f
                    val dir = Offset(cos(angle), sin(angle))
                    fun point(d: Float) = Offset(f.center.x + side * d * dir.x, f.center.y + side * d * dir.y)
                    drawLine(color, f.center, point(0.46f), stroke.width, StrokeCap.Round)
                    listOf(0.22f, 0.34f).forEach { d ->
                        val base = point(d)
                        listOf(-0.785f, 0.785f).forEach { turn ->
                            val a = angle + turn
                            drawLine(color, base, Offset(base.x + side * 0.1f * cos(a), base.y + side * 0.1f * sin(a)), stroke.width, StrokeCap.Round)
                        }
                    }
                }
            }
        }
        TopPickMood.WEEKEND -> {
            drawCircle(color, radius = side * 0.46f, center = f.center, style = stroke)
            drawCircle(color, radius = side * 0.16f, center = f.center, style = stroke)
            drawCircle(color, radius = side * 0.025f, center = f.center)
            rotate(phase * 360f, f.center) {
                listOf(0.36f, 0.27f).forEach { r ->
                    val box = Rect(f.center, side * r)
                    drawArc(color, 200f, 70f, false, box.topLeft, box.size, style = stroke)
                    drawArc(color, 20f, 70f, false, box.topLeft, box.size, style = stroke)
                }
            }
        }
        TopPickMood.WELLBEING -> {
            val open = 0.85f + 0.15f * wave(phase, 0f)
            val base = f.at(0.5f, 0.84f)
            listOf(-62f, -31f, 0f, 31f, 62f).forEach { angle ->
                rotate(angle * open, base) {
                    path.reset()
                    path.moveTo(base.x, base.y)
                    path.quadraticTo(f.x(0.66f), f.y(0.52f), f.x(0.5f), f.y(0.14f + (1f - open) * 0.3f))
                    path.quadraticTo(f.x(0.34f), f.y(0.52f), base.x, base.y)
                    drawPath(path, color, style = stroke)
                }
            }
        }
        TopPickMood.WORK -> {
            drawRoundRect(
                color,
                topLeft = f.at(0.16f, 0.18f),
                size = Size(side * 0.68f, side * 0.48f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.03f),
                style = stroke,
            )
            drawLine(color, f.at(0.04f, 0.78f), f.at(0.96f, 0.78f), stroke.width, StrokeCap.Round)
            drawLine(color, f.at(0.16f, 0.66f), f.at(0.04f, 0.78f), stroke.width, StrokeCap.Round)
            drawLine(color, f.at(0.84f, 0.66f), f.at(0.96f, 0.78f), stroke.width, StrokeCap.Round)
            val typed = 0.28f + 0.36f * (phase * 1.25f).coerceAtMost(1f)
            drawLine(color, f.at(0.28f, 0.36f), f.at(0.64f, 0.36f), stroke.width, StrokeCap.Round)
            drawLine(color, f.at(0.28f, 0.48f), f.at(typed, 0.48f), stroke.width, StrokeCap.Round)
            if ((phase * 4f).toInt() % 2 == 0) {
                drawLine(color, f.at(typed + 0.04f, 0.43f), f.at(typed + 0.04f, 0.53f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

private fun edgeFade(share: Float): Float = when {
    share > 0.85f -> (1f - share) / 0.15f
    share < 0.15f -> share / 0.15f
    else -> 1f
}.coerceIn(0f, 1f)

private fun easeInOut(t: Float): Float = 0.5f - 0.5f * cos(PI.toFloat() * t.coerceIn(0f, 1f))

private fun bolt(path: Path, f: Frame) {
    val points = listOf(
        0.486f to 0.123f, 0.886f to 0.123f, 0.532f to 0.509f, 0.877f to 0.509f,
        0.114f to 0.895f, 0.409f to 0.614f, 0.105f to 0.614f,
    )
    path.reset()
    points.forEachIndexed { index, (x, y) ->
        if (index == 0) path.moveTo(f.x(x), f.y(y)) else path.lineTo(f.x(x), f.y(y))
    }
    path.close()
}

private fun star(path: Path, f: Frame) {
    path.reset()
    path.moveTo(f.x(0.5f), f.y(0.06f))
    path.quadraticTo(f.x(0.56f), f.y(0.44f), f.x(0.94f), f.y(0.5f))
    path.quadraticTo(f.x(0.56f), f.y(0.56f), f.x(0.5f), f.y(0.94f))
    path.quadraticTo(f.x(0.44f), f.y(0.56f), f.x(0.06f), f.y(0.5f))
    path.quadraticTo(f.x(0.44f), f.y(0.44f), f.x(0.5f), f.y(0.06f))
    path.close()
}

private fun sparkle(path: Path, center: Offset, r: Float) {
    path.reset()
    path.moveTo(center.x, center.y - r)
    path.quadraticTo(center.x + r * 0.12f, center.y - r * 0.12f, center.x + r, center.y)
    path.quadraticTo(center.x + r * 0.12f, center.y + r * 0.12f, center.x, center.y + r)
    path.quadraticTo(center.x - r * 0.12f, center.y + r * 0.12f, center.x - r, center.y)
    path.quadraticTo(center.x - r * 0.12f, center.y - r * 0.12f, center.x, center.y - r)
    path.close()
}

private fun fivePointStar(path: Path, center: Offset, r: Float) {
    path.reset()
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) r else r * 0.45f
        val angle = i * PI.toFloat() / 5f - PI.toFloat() / 2f
        val x = center.x + radius * cos(angle)
        val y = center.y + radius * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

private fun crescent(path: Path, f: Frame, cx: Float, cy: Float, r: Float) {
    val disc = Path().apply { addOval(Rect(f.at(cx, cy), f.side * r)) }
    val bite = Path().apply { addOval(Rect(f.at(cx + r * 0.5f, cy - r * 0.3f), f.side * r * 0.88f)) }
    path.reset()
    path.op(disc, bite, PathOperation.Difference)
}

private fun heart(path: Path, f: Frame) {
    val r = 0.24f
    path.reset()
    path.moveTo(f.x(0.5f), f.y(0.92f))
    path.lineTo(f.x(0.28f - r * 0.707f), f.y(0.30f + r * 0.707f))
    path.arcTo(Rect(f.x(0.28f - r), f.y(0.30f - r), f.x(0.28f + r), f.y(0.30f + r)), 135f, 195f, false)
    path.arcTo(Rect(f.x(0.72f - r), f.y(0.30f - r), f.x(0.72f + r), f.y(0.30f + r)), 210f, 195f, false)
    path.close()
}

private fun spiral(path: Path, f: Frame) {
    val turns = 5.5f
    val steps = 320
    path.reset()
    for (k in 0..steps) {
        val t = k.toFloat() / steps
        val angle = PI.toFloat() + t * turns * TAU
        val rx = 0.38f * (1f - 0.72f * t)
        val ry = 0.075f * (1f - 0.55f * t)
        val x = f.x(0.5f + rx * cos(angle))
        val y = f.y(0.10f + 0.78f * t + ry * sin(angle))
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
}
