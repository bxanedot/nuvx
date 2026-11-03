package com.bxane.nuvx.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** nuvx's own minimal 24×24 icon set. Filled shapes for tabs/transport, 2px strokes for utility glyphs. */
object NuvxIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.fill(d: PathBuilder.() -> Unit) = path(fill = SolidColor(Color.White), pathBuilder = d)
    private fun ImageVector.Builder.stroke(d: PathBuilder.() -> Unit) =
        path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = d)

    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y); lineTo(x + w - r, y); quadTo(x + w, y, x + w, y + r); lineTo(x + w, y + h - r); quadTo(x + w, y + h, x + w - r, y + h)
        lineTo(x + r, y + h); quadTo(x, y + h, x, y + h - r); lineTo(x, y + r); quadTo(x, y, x + r, y); close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy); arcTo(r, r, 0f, true, true, cx + r, cy); arcTo(r, r, 0f, true, true, cx - r, cy); close()
    }

    val Home = icon("Home") { fill { moveTo(12f, 3f); lineTo(21f, 10.5f); lineTo(21f, 19f); quadTo(21f, 21f, 19f, 21f); lineTo(15f, 21f); lineTo(15f, 15f); lineTo(9f, 15f); lineTo(9f, 21f); lineTo(5f, 21f); quadTo(3f, 21f, 3f, 19f); lineTo(3f, 10.5f); close() } }
    val Explore = icon("Explore") { fill { roundRect(3f, 3f, 8f, 8f, 2.5f); roundRect(13f, 3f, 8f, 8f, 2.5f); roundRect(3f, 13f, 8f, 8f, 2.5f); roundRect(13f, 13f, 8f, 8f, 2.5f) } }
    val Library = icon("Library") { fill { roundRect(4f, 8f, 16f, 13f, 2.5f); roundRect(6f, 5f, 12f, 2f, 1f); roundRect(8f, 2f, 8f, 2f, 1f) } }
    val Search = icon("Search") { stroke { circle(10.5f, 10.5f, 6.5f); moveTo(15.5f, 15.5f); lineTo(20.5f, 20.5f) } }
    val Play = icon("Play") { fill { moveTo(7f, 4.5f); lineTo(19.5f, 12f); lineTo(7f, 19.5f); close() } }
    val Pause = icon("Pause") { fill { roundRect(6f, 4.5f, 4f, 15f, 1.2f); roundRect(14f, 4.5f, 4f, 15f, 1.2f) } }
    val Forward = icon("Forward") { fill { moveTo(3f, 6f); lineTo(12f, 12f); lineTo(3f, 18f); close(); moveTo(12f, 6f); lineTo(21f, 12f); lineTo(12f, 18f); close() } }
    val Back = icon("Back") { fill { moveTo(21f, 6f); lineTo(12f, 12f); lineTo(21f, 18f); close(); moveTo(12f, 6f); lineTo(3f, 12f); lineTo(12f, 18f); close() } }
    val More = icon("More") { fill { circle(5.5f, 12f, 1.8f); circle(12f, 12f, 1.8f); circle(18.5f, 12f, 1.8f) } }
    val Person = icon("Person") { fill { circle(12f, 8f, 4f); moveTo(4f, 20f); quadTo(4f, 14f, 12f, 14f); quadTo(20f, 14f, 20f, 20f); close() } }
    val Lyrics = icon("Lyrics") { stroke { roundRect(3.5f, 4f, 17f, 13f, 3f); moveTo(8f, 17f); lineTo(8f, 20.5f); lineTo(12f, 17f); moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 12.5f); lineTo(13f, 12.5f) } }
    val Shuffle = icon("Shuffle") { stroke { moveTo(3f, 7f); lineTo(7f, 7f); curveTo(12f, 7f, 12f, 17f, 17f, 17f); lineTo(20f, 17f); moveTo(3f, 17f); lineTo(7f, 17f); curveTo(12f, 17f, 12f, 7f, 17f, 7f); lineTo(20f, 7f); moveTo(17.5f, 4.5f); lineTo(20f, 7f); lineTo(17.5f, 9.5f); moveTo(17.5f, 14.5f); lineTo(20f, 17f); lineTo(17.5f, 19.5f) } }
    val Repeat = icon("Repeat") { stroke { moveTo(4f, 11f); lineTo(4f, 9f); quadTo(4f, 7f, 6f, 7f); lineTo(19f, 7f); moveTo(16.5f, 4.5f); lineTo(19f, 7f); lineTo(16.5f, 9.5f); moveTo(20f, 13f); lineTo(20f, 15f); quadTo(20f, 17f, 18f, 17f); lineTo(5f, 17f); moveTo(7.5f, 14.5f); lineTo(5f, 17f); lineTo(7.5f, 19.5f) } }
    val RepeatOne = icon("RepeatOne") { stroke { moveTo(4f, 11f); lineTo(4f, 9f); quadTo(4f, 7f, 6f, 7f); lineTo(19f, 7f); moveTo(16.5f, 4.5f); lineTo(19f, 7f); lineTo(16.5f, 9.5f); moveTo(20f, 13f); lineTo(20f, 15f); quadTo(20f, 17f, 18f, 17f); lineTo(5f, 17f); moveTo(7.5f, 14.5f); lineTo(5f, 17f); lineTo(7.5f, 19.5f); moveTo(11f, 10.5f); lineTo(12.5f, 9.5f); lineTo(12.5f, 14.5f) } }
    val SpeakerLow = icon("SpeakerLow") { fill { moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close() } }
    val SpeakerHigh = icon("SpeakerHigh") { fill { moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close() }; stroke { moveTo(15.5f, 9f); quadTo(17.5f, 12f, 15.5f, 15f); moveTo(18.5f, 6.5f); quadTo(22f, 12f, 18.5f, 17.5f) } }
    val Note = icon("Note") { fill { circle(7f, 18f, 3f); circle(18f, 16f, 3f); moveTo(8.5f, 18f); lineTo(8.5f, 6f); lineTo(21f, 3f); lineTo(21f, 16f); lineTo(19.5f, 16f); lineTo(19.5f, 7f); lineTo(10f, 9.2f); lineTo(10f, 18f); close() } }
    val Queue = icon("Queue") { stroke { moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 18f); lineTo(14f, 18f) } }
    val Chevron = icon("Chevron") { stroke { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) } }
    val Close = icon("Close") { stroke { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    val Heart = icon("Heart") { stroke { moveTo(12f, 20f); curveTo(5f, 15f, 2.5f, 11.5f, 4f, 8f); curveTo(5.5f, 4.5f, 10f, 4.5f, 12f, 8f); curveTo(14f, 4.5f, 18.5f, 4.5f, 20f, 8f); curveTo(21.5f, 11.5f, 19f, 15f, 12f, 20f); close() } }
    val HeartFilled = icon("HeartFilled") { fill { moveTo(12f, 20f); curveTo(5f, 15f, 2.5f, 11.5f, 4f, 8f); curveTo(5.5f, 4.5f, 10f, 4.5f, 12f, 8f); curveTo(14f, 4.5f, 18.5f, 4.5f, 20f, 8f); curveTo(21.5f, 11.5f, 19f, 15f, 12f, 20f); close() } }
    val Together = icon("Together") { fill { circle(8.5f, 8f, 3.2f); circle(16f, 9f, 2.7f); moveTo(2.5f, 19f); quadTo(2.5f, 13.5f, 8.5f, 13.5f); quadTo(14.5f, 13.5f, 14.5f, 19f); close(); moveTo(15.5f, 19f); quadTo(15.5f, 15.5f, 14f, 14f); quadTo(21.5f, 12.5f, 21.5f, 19f); close() } }
    val Settings = icon("Settings") { stroke { circle(12f, 12f, 3f); circle(12f, 12f, 8f) } }
}
