package com.bxane.nuvx.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import com.bxane.nuvx.ui.graphics.argbPixels
import com.bxane.nuvx.ui.graphics.forPixelAccess
import com.bxane.nuvx.ui.graphics.imageBitmapOf
import com.bxane.nuvx.ui.graphics.toImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.ui.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.bxane.nuvx.data.model.CARD_ART_PX
import com.bxane.nuvx.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

@Immutable
class ArtworkMesh internal constructor(internal val image: ImageBitmap)

@Composable
fun rememberFullArtworkBlurImage(
    imageUrl: String?,
    artPx: Int = CARD_ART_PX,
    prepare: Boolean = true,
): ImageBitmap? {
    val context = LocalPlatformContext.current
    var image by remember { mutableStateOf(imageUrl?.let(fullBlurCache::get)) }

    LaunchedEffect(imageUrl, artPx, prepare) {
        imageUrl?.let(fullBlurCache::get)?.let {
            image = it
            return@LaunchedEffect
        }
        if (!prepare || imageUrl == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(imageUrl.artworkAt(artPx))
            .size(FULL_BLUR_SOURCE_PX)
            .forPixelAccess()
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toImageBitmap() ?: return@LaunchedEffect
        val blurred = withContext(Dispatchers.Default) { bitmap.boxBlurred(FULL_BLUR_PASSES) }
        val ready = blurred
        fullBlurCache[imageUrl] = ready
        image = ready
    }
    return image
}

@Composable
fun FullArtworkBlurBackdrop(
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(FallbackBackdrop)) {
        Crossfade(
            targetState = image,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "preparedBackdropImage",
        ) { bitmap ->
            if (bitmap != null) {
                val painter = remember(bitmap) { BitmapPainter(bitmap) }
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Canvas(Modifier.fillMaxSize()) { drawRect(brush = FullArtworkScrim) }
    }
}

@Composable
fun rememberArtworkMesh(
    imageUrl: String?,
    canvasFrame: ImageBitmap? = null,
    artPx: Int = CARD_ART_PX,
): ArtworkMesh? {
    val context = LocalPlatformContext.current
    val heldMesh = remember { mutableStateOf<ArtworkMesh?>(null) }
    var mesh by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { clipMeshFor(it) ?: meshCache[it] } ?: heldMesh.value)
    }
    var meshUrl by remember(imageUrl) {
        mutableStateOf(
            if (imageUrl != null && (clipMeshFor(imageUrl) != null || meshCache[imageUrl] != null)) {
                imageUrl
            } else {
                null
            },
        )
    }
    LaunchedEffect(mesh) { heldMesh.value = mesh }

    LaunchedEffect(imageUrl, artPx) {
        if (imageUrl == null || meshUrl == imageUrl) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(imageUrl.artworkAt(artPx))
            .size(MESH_PX)
            .forPixelAccess()
            .build()
        repeat(MESH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(MESH_RETRY_DELAY_MS)
            val result = SingletonImageLoader.get(context).execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toImageBitmap()
            if (bitmap != null) {
                val found = withContext(Dispatchers.Default) { meshOf(bitmap, imageUrl.hashCode()) }
                if (found != null) {
                    meshCache[imageUrl] = found
                    mesh = found
                }
                meshUrl = imageUrl
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(canvasFrame) {
        val frame = canvasFrame ?: return@LaunchedEffect
        val found = withContext(Dispatchers.Default) {
            if (isLikelyBlackFrame(frame)) null else meshOf(frame, imageUrl?.hashCode() ?: 0)
        } ?: return@LaunchedEffect
        mesh = found
        meshUrl = imageUrl
        if (imageUrl != null) lastClipMesh = ClipMesh(imageUrl, found)
    }
    return mesh
}

@Composable
fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
    seam: () -> Dp = { 0.dp },
    fallback: Color = FallbackBackdrop,
) {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()

    var shown by remember { mutableStateOf(mesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(mesh) {
        val next = mesh ?: return@LaunchedEffect
        incoming?.let { shown = it }
        incoming = null
        val current = shown
        if (next === current) return@LaunchedEffect

        if (current == null || reduceAnimation) {
            shown = next
            return@LaunchedEffect
        }
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(MESH_FADE_MS, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(fallback),
    ) {
        val seamY = seam().toPx().coerceIn(0f, size.height)
        shown?.let { drawMesh(it, seamY, alpha = 1f) }
        incoming?.let { drawMesh(it, seamY, alpha = fade.value) }

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.30f),
                ),
            ),
        )
    }
}

private fun DrawScope.drawMesh(mesh: ArtworkMesh, seamY: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val image = mesh.image
    val width = size.width.roundToInt()

    if (seamY > 0.5f) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, 1),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, seamY.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }

    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(0, seamY.roundToInt()),
        dstSize = IntSize(width, (size.height - seamY).roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

private val FullArtworkScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.34f),
    0.55f to Color.Black.copy(alpha = 0.48f),
    1f to Color.Black.copy(alpha = 0.64f),
)

private val FallbackBackdrop = Color(0xFF121212)

private const val FULL_BLUR_SOURCE_PX = 128
private const val FULL_BLUR_PASSES = 3

private val fullBlurCache = object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 8
}

private fun ImageBitmap.boxBlurred(passes: Int): ImageBitmap {
    val width = width
    val height = height
    if (width < 2 || height < 2) return this
    var source = argbPixels()
    var target = IntArray(source.size)
    val radius = (minOf(width, height) / 12).coerceAtLeast(2)

    repeat(passes) {
        boxBlurPass(source, target, width, height, radius, horizontal = true)
        boxBlurPass(target, source, width, height, radius, horizontal = false)
    }
    return imageBitmapOf(source, width, height)
}

private fun boxBlurPass(
    source: IntArray,
    target: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val major = if (horizontal) width else height
    val minor = if (horizontal) height else width
    val window = radius * 2 + 1
    repeat(minor) { fixed ->
        var red = 0
        var green = 0
        var blue = 0
        fun pixel(at: Int): Int {
            val position = at.coerceIn(0, major - 1)
            return if (horizontal) source[fixed * width + position]
            else source[position * width + fixed]
        }
        for (offset in -radius..radius) {
            val color = pixel(offset)
            red += color shr 16 and 0xFF
            green += color shr 8 and 0xFF
            blue += color and 0xFF
        }
        repeat(major) { moving ->
            val index = if (horizontal) fixed * width + moving else moving * width + fixed
            target[index] = argb(
                (red + window / 2) / window,
                (green + window / 2) / window,
                (blue + window / 2) / window,
            )
            val leaving = pixel(moving - radius)
            val entering = pixel(moving + radius + 1)
            red += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            green += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

private val meshCache = object : LinkedHashMap<String, ArtworkMesh>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ArtworkMesh>) = size > MESH_CACHE_ENTRIES
}

private class ClipMesh(val imageUrl: String, val mesh: ArtworkMesh)

private var lastClipMesh: ClipMesh? = null

private fun clipMeshFor(imageUrl: String): ArtworkMesh? =
    lastClipMesh?.takeIf { it.imageUrl == imageUrl }?.mesh

private const val MESH_CACHE_ENTRIES = 64

private const val MESH_PX = 120

private const val MESH_ATTEMPTS = 4

private const val MESH_RETRY_DELAY_MS = 1_500L

private const val MESH_GRID = 6

private const val MESH_TEX = 32

private const val MESH_BLUR_RADIUS = 1
private const val MESH_BLUR_PASSES = 3

private const val MESH_FADE_MS = 900

private const val MESH_SAMPLE = 128

private fun meshOf(source: ImageBitmap, seed: Int): ArtworkMesh? {
    val width = source.width
    val height = source.height
    if (width < 1 || height < 1) return null

    val cols = MESH_GRID.coerceAtMost(width)
    val rows = MESH_GRID.coerceAtMost(height)
    val rowStep = (height / MESH_SAMPLE).coerceAtLeast(1)
    val colStep = (width / MESH_SAMPLE).coerceAtLeast(1)

    val cells = rows * cols
    val red = LongArray(cells)
    val green = LongArray(cells)
    val blue = LongArray(cells)
    val count = IntArray(cells)

    val line = IntArray(width)
    var y = 0
    while (y < height) {
        source.readPixels(line, startX = 0, startY = y, width = width, height = 1)
        val rowBase = ((height - 1 - y) * rows / height) * cols
        var x = 0
        while (x < width) {
            val cell = rowBase + x * cols / width
            val pixel = line[x]
            red[cell] += (pixel shr 16) and 0xFF
            green[cell] += (pixel shr 8) and 0xFF
            blue[cell] += pixel and 0xFF
            count[cell]++
            x += colStep
        }
        y += rowStep
    }

    val grid = IntArray(cells) { cell ->
        val n = count[cell].coerceAtLeast(1)
        argb((red[cell] / n).toInt(), (green[cell] / n).toInt(), (blue[cell] / n).toInt()).lifted()
    }
    val texels = grid.rotatedBelowSeam(cols, rows, seed).resampled(cols, rows, MESH_TEX)
    val scratch = IntArray(texels.size)
    repeat(MESH_BLUR_PASSES) {
        boxBlurPass(texels, scratch, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = true)
        boxBlurPass(scratch, texels, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = false)
    }
    return ArtworkMesh(imageBitmapOf(texels, MESH_TEX, MESH_TEX))
}

private fun IntArray.rotatedBelowSeam(cols: Int, rows: Int, seed: Int): IntArray {
    if (rows <= 1) return this
    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)
    val out = copyOf()
    for (row in 1 until rows) {
        val base = row * cols
        for (x in 0 until cols) {
            val src = if (mirror) cols - 1 - x else x
            out[base + x] = this[base + (src + shift) % cols]
        }
    }
    return out
}

private fun IntArray.resampled(cols: Int, rows: Int, size: Int): IntArray {
    val out = IntArray(size * size)
    for (ty in 0 until size) {
        val fy = (ty + 0.5f) / size * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smoothstep(fy - y0)
        for (tx in 0 until size) {
            val fx = (tx + 0.5f) / size * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smoothstep(fx - x0)
            val top = lerpArgb(this[y0 * cols + x0], this[y0 * cols + x1], wx)
            val bottom = lerpArgb(this[y1 * cols + x0], this[y1 * cols + x1], wx)
            out[ty * size + tx] = lerpArgb(top, bottom, wy)
        }
    }
    return out
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * t)).roundToInt().coerceIn(0, 255)
    }
    return argb(channel(16), channel(8), channel(0))
}

private fun argb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

private fun Int.lifted(): Int {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(this, it) }
    hsl[1] = (hsl[1] * MESH_VIBRANCE).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceAtLeast(MESH_FLOOR)
    return ColorUtils.HSLToColor(hsl)
}

private const val MESH_VIBRANCE = 1.12f
private const val MESH_FLOOR = 0.045f
