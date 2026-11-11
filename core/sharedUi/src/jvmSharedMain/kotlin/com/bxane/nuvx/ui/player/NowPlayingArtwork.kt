package com.bxane.nuvx.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

private const val MESH_SEAM_MOVE_MS = 320

@Composable
internal fun PlayerMeshBackdrop(
    trackKey: String,
    imageUrl: String?,
    canvasFrame: () -> ImageBitmap?,
    seamTarget: () -> Dp,
    alpha: () -> Float,
) {
    val mesh = key(trackKey) { rememberArtworkMesh(imageUrl, canvasFrame(), ART_PX) }
    val currentSeamTarget by rememberUpdatedState(seamTarget)
    val seam = remember { Animatable(Snapshot.withoutReadObservation { seamTarget() }, Dp.VectorConverter) }
    LaunchedEffect(seam) {
        snapshotFlow { currentSeamTarget() }.collectLatest { target ->
            if (seam.value == 0.dp || target == 0.dp) {
                seam.snapTo(target)
            } else {
                seam.animateTo(target, tween(MESH_SEAM_MOVE_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    ArtworkMeshBackdrop(
        mesh = mesh,
        seam = { seam.value },
        modifier = Modifier.graphicsLayer { this.alpha = alpha() },
    )
}

@Composable
internal fun LegacyMeshBackdrop(
    imageUrl: String?,
    canvasFrame: () -> ImageBitmap?,
    trackKey: String,
    modifier: Modifier,
) {
    MeshGradientBackground(
        palette = rememberArtworkColors(imageUrl, canvasFrame()),
        trackKey = trackKey,
        modifier = modifier,
    )
}

internal const val SPOTIFY_CANVAS_RESUME_DELAY_MS = 1_000L

internal fun DrawScope.drawCropped(image: ImageBitmap) {
    if (size.width <= 0f || size.height <= 0f) return
    val scale = maxOf(size.width / image.width, size.height / image.height)
    val srcWidth = (size.width / scale).roundToInt().coerceIn(1, image.width)
    val srcHeight = (size.height / scale).roundToInt().coerceIn(1, image.height)
    drawImage(
        image = image,
        srcOffset = IntOffset((image.width - srcWidth) / 2, (image.height - srcHeight) / 2),
        srcSize = IntSize(srcWidth, srcHeight),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}

@Immutable
internal class CanvasStill(val url: String, val image: ImageBitmap, val positionMs: Long)

internal object CanvasStills {
    var current by mutableStateOf<CanvasStill?>(null)

    var fresh by mutableStateOf(false)
}
