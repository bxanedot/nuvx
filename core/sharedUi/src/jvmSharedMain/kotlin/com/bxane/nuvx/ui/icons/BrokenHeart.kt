package com.bxane.nuvx.ui.icons

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import com.composables.sfsymbols.SfSymbols
import com.composables.sfsymbols.monochrome.SFHeart
import com.composables.sfsymbols.monochrome.SFHeartFill

object BrokenHeart {
    val Outline: ImageVector by lazy { broken(SfSymbols.Monochrome.SFHeart, "nx_broken_heart", outline = true) }
    val Fill: ImageVector by lazy { broken(SfSymbols.Monochrome.SFHeartFill, "nx_broken_heart_fill", outline = false) }

    private const val WEIGHT = 0.35f

    private const val HALF_GAP = 0.55f

    private val crack = listOf(
        0f to -1f,
        0f to 4.4f,
        -1.6f to 9f,
        1.4f to 13.2f,
        -1f to 17.6f,
        0f to 26f,
    )

    private fun broken(regular: ImageVector, name: String, outline: Boolean): ImageVector {
        val pad = SfIcons.BOLD_STROKE / 2f
        val width = regular.viewportWidth + pad * 2f
        val height = regular.viewportHeight + pad * 2f
        val middle = pad + regular.viewportWidth / 2f
        val right = width + 2f
        val bottom = height + 2f
        val heartShape = heartFillNodes()
        val edgeWidth = REGULAR_LINE + WEIGHT

        return SfIcons.canvas(name, width, height) {
            for (side in listOf(-1f, 1f)) {
                val shift = side * HALF_GAP
                addGroup(clipPathData = halfClip(middle + shift, if (side < 0) -2f else right, bottom))
                addGroup(translationX = pad, translationY = pad)
                SfIcons.copyInto(this, regular.root, extraStroke = WEIGHT)
                clearGroup()
                if (outline) {
                    addGroup(translationX = pad, translationY = pad, clipPathData = heartShape)
                    path(
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = edgeWidth,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Round,
                    ) {
                        val edge = middle - pad + side * (HALF_GAP + edgeWidth / 2f)
                        crack.forEachIndexed { i, (dx, y) ->
                            if (i == 0) moveTo(edge + dx, y - pad) else lineTo(edge + dx, y - pad)
                        }
                    }
                    clearGroup()
                }
                clearGroup()
            }
        }
    }

    private fun halfClip(crackX: Float, edgeX: Float, bottom: Float): List<PathNode> =
        PathBuilder().apply {
            moveTo(edgeX, -2f)
            crack.forEach { (dx, y) -> lineTo(crackX + dx, y) }
            lineTo(edgeX, bottom)
            close()
        }.nodes

    private fun heartFillNodes(): List<PathNode> {
        val nodes = mutableListOf<PathNode>()
        fun collect(group: VectorGroup) {
            for (node in group) {
                when (node) {
                    is VectorGroup -> collect(node)
                    is VectorPath -> nodes += node.pathData
                }
            }
        }
        collect(SfSymbols.Monochrome.SFHeartFill.root)
        return nodes.ifEmpty { addPathNodes("") }
    }

    private const val REGULAR_LINE = 1.73f
}
