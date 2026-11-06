package com.bxane.nuvx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class AndroidWelcomeLayoutTest {
    @Test fun portraitAtNormalFontKeepsEveryPageReachableAndCompletesOnce() = checkWelcome(360, 780, 1f)
    @Test fun portraitAtDoubleFontKeepsEveryPageReachableAndCompletesOnce() = checkWelcome(360, 780, 2f)
    @Test fun shortLandscapeAtNormalFontKeepsEveryPageReachableAndCompletesOnce() = checkWelcome(640, 360, 1f)
    @Test fun shortLandscapeAtDoubleFontKeepsEveryPageReachableAndCompletesOnce() = checkWelcome(640, 360, 2f)

    @Test fun skipCompletesOnceFromEachPageAtBothSizesAndFontScales() {
        for ((width, height) in listOf(360 to 780, 640 to 360)) for (scale in listOf(1f, 2f)) {
            for (page in 0..2) checkWelcome(width, height, scale, skipAt = page)
        }
    }

    private fun checkWelcome(width: Int, height: Int, fontScale: Float, skipAt: Int? = null) {
        SwingUtilities.invokeAndWait {
            val context = "${width}x$height fontScale=$fontScale skipAt=$skipAt"
            val completed = mutableStateOf(false)
            var completions = 0
            val scene = ImageComposeScene(width, height, density = Density(1f, fontScale)) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize(), color = NuvxColors.Background) {
                        // Reserve phone system-bar space without pretending to supply Android insets.
                        Box(Modifier.padding(top = 24.dp, bottom = 24.dp)) {
                            if (!completed.value) AndroidWelcome(onDone = {
                                completions++
                                completed.value = true
                            }, reduceMotion = true)
                        }
                    }
                }
            }
            var frame = 0L
            fun render() { repeat(60) { scene.render(frame++ * 16_000_000L).close() } }
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
            fun text(label: String) = nodes().single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
            }
            fun layout(node: SemanticsNode): TextLayoutResult {
                val results = mutableListOf<TextLayoutResult>()
                assertEquals(true, node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results), context)
                return results.single()
            }
            fun control(node: SemanticsNode) = generateSequence(node) { it.parent }.first {
                it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            fun click(label: String) {
                val action = control(text(label))
                assertEquals(Role.Button, action.config.getOrNull(SemanticsProperties.Role), context)
                assertFalse(action.config.contains(SemanticsProperties.Disabled), context)
                assertTrue(action.boundsInRoot.width > 0f && action.boundsInRoot.height > 0f, context)
                assertEquals(true, action.config[SemanticsActions.OnClick].action?.invoke(), context)
                render()
            }
            fun observe(label: String): LabelLayout {
                val node = text(label)
                return LabelLayout(label, node.boundsInRoot, control(node).boundsInRoot, layout(node))
            }
            fun checkScrollReachability() {
                val scroll = nodes().single { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                val viewport = scroll.boundsInRoot
                assertTrue(viewport.height > 0f, "$context has no intro viewport")
                assertTrue(viewport.top >= control(text("Skip")).boundsInRoot.bottom - 0.5f, context)
                assertTrue(viewport.bottom <= control(text(if (textExists(nodes(), "Continue")) "Continue" else "Start listening")).boundsInRoot.top + 0.5f, context)
                assertEquals(0f, range.value(), "$context must start each page at its beginning")
                if (height == 360) assertTrue(range.maxValue() > 0f, "$context must exercise actual scrolling")

                fun reveal(node: SemanticsNode, top: Float, bottom: Float) {
                    val target = (range.value() + node.positionInRoot.y + top - viewport.top).coerceIn(0f, range.maxValue())
                    val delta = target - range.value()
                    if (delta != 0f) {
                        assertEquals(true, scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, delta), context)
                        render()
                    }
                    val y = node.positionInRoot.y
                    assertTrue(y + top >= viewport.top - 1f && y + bottom <= viewport.bottom + 1f,
                        "$context unreachable intro segment: ${y + top}..${y + bottom}, viewport=$viewport, scroll=${range.value()}/${range.maxValue()}")
                }

                val brand = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("nuvx") == true }
                reveal(brand, 0f, brand.size.height.toFloat())
                val content = descendants(scroll).filter { it.config.getOrNull(SemanticsProperties.Text) != null }
                assertEquals(2, content.size, "$context must render both the actual title and detail")
                for (node in content) {
                    val result = layout(node)
                    assertFalse(result.didOverflowHeight, "$context intro text must be fully laid out")
                    assertTrue(result.linesFitWidth(), "$context intro text must fit horizontally")
                    assertEquals(result.layoutInput.text.length, result.getLineEnd(result.lineCount - 1))
                    for (line in 0 until result.lineCount) reveal(node, result.getLineTop(line), result.getLineBottom(line))
                }
                if (range.maxValue() > 0f) assertTrue(range.value() > 0f, "$context did not scroll to the detail")
                println("intro $context viewport=$viewport scroll=${range.value()}/${range.maxValue()} all lines reached")
            }

            try {
                render()
                val labels = mutableListOf<LabelLayout>()
                for (page in 0..(skipAt ?: 2)) {
                    assertEquals(0, completions, "$context completed before an exit action")
                    val indicator = nodes().single {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Introduction page ${page + 1} of 3") == true
                    }
                    assertEquals(true, indicator.config.getOrNull(SemanticsProperties.Selected), context)
                    val footer = if (page == 2) "Start listening" else "Continue"
                    labels += observe("Skip")
                    labels += observe(footer)
                    if (skipAt == null) checkScrollReachability()
                    click(if (page == skipAt) "Skip" else footer)
                }
                assertEquals(1, completions, "$context must complete exactly once")
                render()
                assertEquals(1, completions, "$context recomposition must not complete again")
                assertFalse(textExists(nodes(), "Skip"), "$context caller must leave onboarding after completion")
                val problems = labels.flatMap { it.problems(Rect(0f, 24f, width.toFloat(), height - 24f)) }
                labels.forEach { println("label $context ${it.label}: size=${it.layout.size} paragraphHeight=${it.layout.multiParagraph.height} visible=${it.bounds} control=${it.controlBounds} heightOverflow=${it.layout.didOverflowHeight}") }
                assertTrue(problems.isEmpty(), "$context\n${problems.joinToString("\n")}")
            } finally {
                scene.close()
            }
        }
    }

    private data class LabelLayout(val label: String, val bounds: Rect, val controlBounds: Rect, val layout: TextLayoutResult) {
        fun problems(viewport: Rect): List<String> = buildList {
            val evidence = "$label measured=${layout.size} visible=$bounds control=$controlBounds"
            if (layout.didOverflowHeight) add("$evidence text height overflow: needs=${layout.multiParagraph.height}")
            if (!layout.linesFitWidth()) add("$evidence text line width overflow")
            if (layout.getLineEnd(layout.lineCount - 1) != label.length || (0 until layout.lineCount).any(layout::isLineEllipsized))
                add("$evidence label is truncated")
            if (bounds.width <= 0f || bounds.height <= 0f || bounds.width + 0.5f < layout.size.width || bounds.height + 0.5f < layout.size.height)
                add("$evidence label is clipped by an ancestor")
            for (container in listOf(viewport, controlBounds)) {
                if (bounds.left < container.left - 0.5f || bounds.top < container.top - 0.5f || bounds.right > container.right + 0.5f || bounds.bottom > container.bottom + 0.5f)
                    add("$evidence label is outside $container")
            }
        }
    }

    private fun textExists(nodes: List<SemanticsNode>, label: String) = nodes.any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
    }

    private companion object {
        // Paragraph width can include unused space; compare actual line extents with one pixel of rounding tolerance.
        fun TextLayoutResult.linesFitWidth() = (0 until lineCount).all { getLineRight(it) - getLineLeft(it) <= size.width + 1f }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
