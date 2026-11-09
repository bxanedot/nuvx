package com.bxane.nuvx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState

/**
 * Search's bottom bar, as Convx has it: the tabs folded into one glass circle
 * holding the tab you came from (tap to go back), beside a glass pill with the
 * search [field]. Same material as the tab bar it stands in for.
 */
@Composable
internal fun SearchDock(
    backTab: BottomTab,
    onBack: () -> Unit,
    hazeState: HazeState?,
    field: @Composable () -> Unit,
) {
    val haptics = rememberHaptics()
    val pill = remember { RoundedCornerShape(percent = 50) }
    val tint = if (LocalLiquidGlassEnabled.current && isGlassSupported()) glassContentColor() else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().height(SEARCH_DOCK_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(SEARCH_DOCK_HEIGHT)
                .barSurface(CircleShape, hazeState)
                .clickable { haptics.play(Haptic.Select); onBack() }
                .semantics { contentDescription = backTab.label },
            contentAlignment = Alignment.Center,
        ) {
            TabIcon(backTab, selected = false, tint = tint)
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().barSurface(pill, hazeState).padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            CompositionLocalProvider(LocalSearchFieldOnSurface provides true, content = field)
        }
    }
}

/** Apple's inline bar circles are 48pt; a touch more here so the field's text has room. */
private val SEARCH_DOCK_HEIGHT = 52.dp
