
package com.bxane.nuvx.ui.components.backdrop.effects

import androidx.compose.ui.graphics.RenderEffect
import com.bxane.nuvx.ui.components.backdrop.BackdropEffectScope
import com.bxane.nuvx.ui.components.backdrop.internal.chain
import com.bxane.nuvx.ui.components.backdrop.isRenderEffectSupported

fun BackdropEffectScope.effect(effect: RenderEffect) {
    if (!isRenderEffectSupported()) return

    renderEffect = renderEffect.chain(effect)
}
