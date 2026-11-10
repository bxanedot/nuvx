package com.bxane.nuvx.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.bxane.nuvx.wear.R
import com.bxane.nuvx.wear.data.NowPlaying

@Immutable
data class Ambient(
    val active: Boolean = false,
    val burnInProtection: Boolean = false,
    val lowBit: Boolean = false,
    val tick: Int = 0,
)

val LocalAmbient = compositionLocalOf { Ambient() }

@Composable
fun AmbientPlayer(np: NowPlaying, ambient: Ambient) {
    val primary = if (ambient.lowBit) Color.White else Color.White.copy(alpha = 0.9f)
    val secondary = if (ambient.lowBit) Color.White else Color.White.copy(alpha = 0.55f)
    val shift = remember(ambient.tick, ambient.burnInProtection) {
        if (!ambient.burnInProtection) {
            Offset.Zero
        } else {
            val step = ambient.tick % 8
            val dx = intArrayOf(0, 4, 6, 4, 0, -4, -6, -4)[step]
            val dy = intArrayOf(-6, -4, 0, 4, 6, 4, 0, -4)[step]
            Offset(dx.toFloat(), dy.toFloat())
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val w = maxWidth
        val h = maxHeight
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(shift.x.dp, shift.y.dp),
        ) {
            Text(
                text = np.title,
                style = WearType.header,
                color = primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = w * 0.76f),
            )
            Text(
                text = np.artist,
                style = WearType.subtitle,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = w * 0.68f),
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            tint = secondary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(shift.x.dp, shift.y.dp)
                .padding(bottom = h * 0.09f)
                .size(width = h * 0.11f * LOGO_ASPECT, height = h * 0.11f),
        )
    }
}

private const val LOGO_ASPECT = 880f / 360f
