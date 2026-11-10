package com.bxane.nuvx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import java.util.Locale
import kotlin.math.floor
import kotlin.math.round
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect as SkRect

private val startNanos = System.nanoTime()

internal actual fun uptimeMillis(): Long = (System.nanoTime() - startNanos) / 1_000_000L

internal actual val renderEffectBlurSupported: Boolean = true

@Composable
internal actual fun KeepScreenOn(enabled: Boolean) = Unit

@Composable
internal actual fun appLanguageTag(): String = Locale.getDefault().toLanguageTag()

@Composable
actual fun PlayerBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    val entry = remember { PlayerBack.Entry { current.value() } }
    DisposableEffect(enabled) {
        if (enabled) PlayerBack.push(entry)
        onDispose { PlayerBack.remove(entry) }
    }
}

object PlayerBack {
    class Entry(val onBack: () -> Unit)

    private val entries = ArrayList<Entry>()

    internal fun push(entry: Entry) {
        synchronized(entries) {
            entries.remove(entry)
            entries.add(entry)
        }
    }

    internal fun remove(entry: Entry) {
        synchronized(entries) { entries.remove(entry) }
    }

    fun dispatch(): Boolean {
        val top = synchronized(entries) { entries.lastOrNull() } ?: return false
        top.onBack()
        return true
    }
}

private val subPixelSampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

internal actual val drawerFollowsListScroll: Boolean get() = false

internal actual fun DrawScope.clipShiftedDown(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    dy: Float,
    block: DrawScope.() -> Unit,
) {
    val whole = floor(dy)
    val fraction = dy - whole
    if (fraction < 0.02f || fraction > 0.98f) {
        clipRect(left = left, top = top, right = right, bottom = bottom) {
            translate(top = round(dy)) { block() }
        }
        return
    }
    val canvas = drawContext.canvas.nativeCanvas
    val paint = Paint().apply {
        imageFilter = ImageFilter.makeMatrixTransform(
            Matrix33.makeTranslate(0f, fraction),
            subPixelSampling,
            null,
        )
    }
    canvas.saveLayer(SkRect.makeLTRB(left, top - 1f, right, bottom + 1f), paint)
    clipRect(left = left, top = top - fraction, right = right, bottom = bottom - fraction) {
        translate(top = whole) { block() }
    }
    canvas.restore()
    paint.close()
}
