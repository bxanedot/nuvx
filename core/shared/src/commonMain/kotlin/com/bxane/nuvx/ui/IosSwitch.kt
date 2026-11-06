package com.bxane.nuvx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Nuvx capsule switch; a null callback leaves interaction to its enclosing settings row. */
@Composable
internal fun IosSwitch(
    checked: Boolean,
    label: String,
    reduceMotion: Boolean,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    val pressed by interaction.collectIsPressedAsState()
    val thumbWidth by animateDpAsState(
        if (pressed && !reduceMotion) 44.dp else 40.dp,
        if (reduceMotion) tween(0) else spring(stiffness = 600f), label = "switchThumbWidth",
    )
    val position by animateDpAsState(
        if (checked) 60.dp - thumbWidth else 0.dp,
        if (reduceMotion) tween(0) else spring(stiffness = 600f), label = "switchPosition",
    )
    val track by animateColorAsState(
        if (checked) NuvxColors.Accent else NuvxColors.ControlTrack,
        tween(if (reduceMotion) 0 else 160), label = "switchTrack",
    )
    val action = if (onCheckedChange == null) Modifier else Modifier
        .toggleable(checked, interactionSource = interaction, indication = null, role = Role.Switch, onValueChange = onCheckedChange)
        .semantics { contentDescription = label }
    Box(Modifier.size(64.dp, 28.dp).then(action).background(track, CircleShape).padding(2.dp)) {
        Box(Modifier.align(Alignment.CenterStart).offset(x = position).size(thumbWidth, 24.dp).background(Color.White, CircleShape))
    }
}
