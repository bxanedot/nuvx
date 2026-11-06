package com.bxane.nuvx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.core.AudioQualityPreference
import com.bxane.nuvx.core.VideoQualityPreference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class SettingsQualityLayoutTest {
    @Test fun audioChoicesAtNormalFontAreFullyVisibleAndChooseOnce() = checkAudio(1f)
    @Test fun audioChoicesAtDoubleFontAreFullyVisibleAndChooseOnce() = checkAudio(2f)
    @Test fun videoChoicesAtNormalFontAreFullyVisibleAndChooseOnce() = checkVideo(1f)
    @Test fun videoChoicesAtDoubleFontAreFullyVisibleAndChooseOnce() = checkVideo(2f)

    private fun checkAudio(fontScale: Float) = checkQuality(
        "Preferred audio quality", "For the next InnerTubeX stream; eXcor may differ. Lossless is unavailable.",
        AudioQualityPreference.entries.map { it.label }, AudioQualityPreference.HIGH.label, fontScale,
    )

    private fun checkVideo(fontScale: Float) = checkQuality(
        "Preferred video quality", "Applies when the next video loads; the source may return lower.",
        VideoQualityPreference.entries.map { it.label }, VideoQualityPreference.AUTO.label, fontScale,
    )

    private fun checkQuality(title: String, subtitle: String, choices: List<String>, initial: String, fontScale: Float) {
        val problems = linkedSetOf<String>()
        for (width in listOf(360, 320)) for (direction in LayoutDirection.entries) for (reduced in listOf(false, true)) {
            SwingUtilities.invokeAndWait {
                val context = "$title width=$width fontScale=$fontScale $direction reduceMotion=$reduced"
                val selected = mutableStateOf(initial)
                val requests = mutableListOf<String>()
                val scene = ImageComposeScene(width, 900, density = Density(1f, fontScale)) {
                    MaterialTheme {
                        CompositionLocalProvider(LocalLayoutDirection provides direction, LocalGlassMotionReduced provides reduced) {
                            // Match SettingsPage's 20dp screen inset, card and vertically scrollable content.
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NuvxColors.Card)) {
                                    SettingsRowView(
                                        SettingRow(null, title, subtitle, value = selected.value, choices = choices, segmented = true,
                                            onChoose = { requests += it; selected.value = it }),
                                        desktop = false, androidUi = true, reduceMotion = reduced,
                                    )
                                }
                            }
                        }
                    }
                }
                var frame = 0L
                fun render() { repeat(12) { scene.render(frame++ * 16_000_000L).close() } }
                fun nodes(merged: Boolean) = scene.semanticsOwners.flatMap {
                    descendants(if (merged) it.rootSemanticsNode else it.unmergedRootSemanticsNode)
                }
                fun radios() = nodes(true).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                fun radio(choice: String) = radios().single {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == choice } == true
                }
                fun checkSelection() {
                    assertEquals(choices.size, radios().size, "$context must expose every choice exactly once")
                    for (choice in choices) {
                        assertEquals(choice == selected.value, radio(choice).config.getOrNull(SemanticsProperties.Selected), context)
                        assertFalse(radio(choice).config.contains(SemanticsProperties.Disabled), context)
                    }
                }
                fun checkLabels() {
                    for (choice in choices) {
                        val node = nodes(false).single {
                            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == choice } == true
                        }
                        val results = mutableListOf<TextLayoutResult>()
                        assertEquals(true, node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results), context)
                        val layout = results.single()
                        val bounds = node.boundsInRoot
                        val control = radio(choice).boundsInRoot
                        val evidence = "$context $choice measured=${layout.size} needsHeight=${layout.multiParagraph.height} visible=$bounds control=$control"
                        if (layout.didOverflowHeight) problems += "$evidence height overflow"
                        // Compare actual line extents, not paragraph width that can include unused space.
                        if ((0 until layout.lineCount).any { layout.getLineRight(it) - layout.getLineLeft(it) > layout.size.width + 1f })
                            problems += "$evidence line width overflow"
                        if (layout.getLineEnd(layout.lineCount - 1) != choice.length || (0 until layout.lineCount).any(layout::isLineEllipsized))
                            problems += "$evidence incomplete label"
                        if (bounds.width <= 0f || bounds.height <= 0f || bounds.width + 0.5f < layout.size.width || bounds.height + 0.5f < layout.size.height)
                            problems += "$evidence clipped by ancestor"
                        for (container in listOf(control, Rect(20f, 0f, width - 20f, 900f))) {
                            if (bounds.left < container.left - 0.5f || bounds.top < container.top - 0.5f || bounds.right > container.right + 0.5f || bounds.bottom > container.bottom + 0.5f)
                                problems += "$evidence outside $container"
                        }
                    }
                }
                try {
                    render()
                    assertTrue(requests.isEmpty(), "$context must not choose during composition")
                    checkSelection()
                    checkLabels()
                    for (choice in choices) {
                        val count = requests.size
                        val action = radio(choice)
                        assertTrue(action.boundsInRoot.width > 0f && action.boundsInRoot.height > 0f, "$context $choice must be reachable")
                        assertEquals(true, action.config[SemanticsActions.OnClick].action?.invoke(), context)
                        render()
                        assertEquals(count + 1, requests.size, "$context $choice must dispatch exactly once")
                        assertEquals(choice, requests.last(), context)
                        assertEquals(choice, selected.value, context)
                        checkSelection()
                        checkLabels()
                    }
                    render()
                    assertEquals(choices, requests, "$context must not choose again on recomposition")
                    println("$context callbacks=$requests controls=${choices.map { it to radio(it).boundsInRoot }}")
                } finally {
                    scene.close()
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
