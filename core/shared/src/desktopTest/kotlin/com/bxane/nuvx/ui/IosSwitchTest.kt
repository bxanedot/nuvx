package com.bxane.nuvx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class IosSwitchTest {
    @Test fun settingsRowExposesOneNamedToggleWithCheckedState() {
        SwingUtilities.invokeAndWait {
            val checked = mutableStateOf(false)
            var clicks = 0
            val scene = ImageComposeScene(500, 120) {
                MaterialTheme {
                    SettingsRowView(
                        SettingRow(null, "Reduce motion", toggle = checked.value, onClick = {
                            clicks++
                            checked.value = !checked.value
                        }),
                        desktop = false, androidUi = true, reduceMotion = true,
                    )
                }
            }
            var frame = 0L
            fun render() { repeat(4) { scene.render(frame++ * 16_000_000L).close() } }
            fun toggles() = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            try {
                render()
                assertEquals(1, toggles().size, "Settings row must be a single accessibility target")
                val row = toggles().single()
                assertEquals(listOf("Reduce motion"), row.config[SemanticsProperties.Text].map { it.text })
                assertEquals(ToggleableState.Off, row.config[SemanticsProperties.ToggleableState])
                assertEquals(true, row.config[SemanticsActions.OnClick].action?.invoke())
                render()
                assertEquals(1, clicks)
                assertEquals(ToggleableState.On, toggles().single().config[SemanticsProperties.ToggleableState])
            } finally {
                scene.close()
            }
        }
    }

    @Test fun accessibleSwitchTogglesOnceInBothDirectionsAndMotionModes() {
        SwingUtilities.invokeAndWait {
            for (direction in LayoutDirection.entries) for (reduceMotion in listOf(false, true)) {
                val checked = mutableStateOf(false)
                val changes = mutableListOf<Boolean>()
                val scene = ImageComposeScene(160, 100) {
                    MaterialTheme {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) {
                            IosSwitch(checked.value, "Reduce motion", reduceMotion) {
                                changes += it
                                checked.value = it
                            }
                        }
                    }
                }
                var frame = 0L
                fun render() { repeat(4) { scene.render(frame++ * 16_000_000L).close() } }
                fun switch(): SemanticsNode = scene.semanticsOwners
                    .flatMap { descendants(it.rootSemanticsNode) }
                    .single { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
                try {
                    render()
                    assertEquals(listOf("Reduce motion"), switch().config[SemanticsProperties.ContentDescription])
                    assertEquals(ToggleableState.Off, switch().config[SemanticsProperties.ToggleableState])
                    assertEquals(true, switch().config[SemanticsActions.OnClick].action?.invoke())
                    render()
                    assertEquals(listOf(true), changes)
                    assertEquals(ToggleableState.On, switch().config[SemanticsProperties.ToggleableState])
                    assertEquals(true, switch().config[SemanticsActions.OnClick].action?.invoke())
                    render()
                    assertEquals(listOf(true, false), changes)
                    assertEquals(ToggleableState.Off, switch().config[SemanticsProperties.ToggleableState])
                } finally {
                    scene.close()
                }
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)
}
