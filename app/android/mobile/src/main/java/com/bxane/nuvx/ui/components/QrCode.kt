package com.bxane.nuvx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    foreground: Color = Color.Black,
    background: Color = Color.White,
    quietZone: Dp = 12.dp,
) {
    val density = LocalDensity.current
    val sidePx = with(density) { (size - quietZone * 2).roundToPx() }

    val image by produceState<ImageBitmap?>(null, content, sidePx, foreground) {
        value = withContext(Dispatchers.Default) {
            renderQr(content, sidePx, foreground, density)
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(quietZone),
    ) {
        AnimatedVisibility(visible = image != null, enter = fadeIn()) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun renderQr(
    content: String,
    sidePx: Int,
    foreground: Color,
    density: Density,
): ImageBitmap? {
    if (sidePx <= 0 || content.isBlank()) return null
    val matrix = runCatching {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            QR_MATRIX_SIZE,
            QR_MATRIX_SIZE,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }.getOrNull() ?: return null

    val modules = matrix.width
    if (modules <= 0) return null

    val image = ImageBitmap(sidePx, sidePx)
    val side = sidePx.toFloat()
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(side, side)) {
        val cell = side / modules
        val dot = cell * 0.86f
        val radius = CornerRadius(dot * 0.3f, dot * 0.3f)
        val inset = (cell - dot) / 2f
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (!matrix.get(x, y)) continue
                drawRoundRect(
                    color = foreground,
                    topLeft = Offset(x * cell + inset, y * cell + inset),
                    size = Size(dot, dot),
                    cornerRadius = radius,
                )
            }
        }
    }
    return image
}

private const val QR_MATRIX_SIZE = 256
