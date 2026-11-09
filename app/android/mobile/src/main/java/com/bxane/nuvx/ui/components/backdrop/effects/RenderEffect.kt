// SPDX-License-Identifier: Apache-2.0
/*
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.bxane.nuvx.ui.components.backdrop.effects

import androidx.compose.ui.graphics.RenderEffect
import com.bxane.nuvx.ui.components.backdrop.BackdropEffectScope
import com.bxane.nuvx.ui.components.backdrop.internal.chain
import com.bxane.nuvx.ui.components.backdrop.isRenderEffectSupported

fun BackdropEffectScope.effect(effect: RenderEffect) {
    if (!isRenderEffectSupported()) return

    renderEffect = renderEffect.chain(effect)
}
