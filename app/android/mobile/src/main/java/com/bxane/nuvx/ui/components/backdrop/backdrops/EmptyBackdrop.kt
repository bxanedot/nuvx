// SPDX-License-Identifier: Apache-2.0
package com.bxane.nuvx.ui.components.backdrop.backdrops

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.bxane.nuvx.ui.components.backdrop.Backdrop

@Immutable
private object EmptyBackdrop : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
    }
}
