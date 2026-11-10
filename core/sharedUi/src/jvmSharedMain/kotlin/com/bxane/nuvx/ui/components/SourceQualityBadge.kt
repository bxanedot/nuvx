package com.bxane.nuvx.ui.components

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.sharedui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

enum class SourceQuality { DOLBY_ATMOS, HI_RES_LOSSLESS, LOSSLESS, HIGH_QUALITY }

fun Song.sourceQualityBadge(): SourceQuality? = when (sourceQuality?.uppercase()) {
    "DOLBY" -> SourceQuality.DOLBY_ATMOS
    "HI-RES", "HIRES" -> SourceQuality.HI_RES_LOSSLESS
    "LOSSLESS", "FLAC", "ALAC", "WAV" ->
        if (sourceHiRes) SourceQuality.HI_RES_LOSSLESS else SourceQuality.LOSSLESS
    "HIGH", "320" -> SourceQuality.HIGH_QUALITY
    else -> null
}

@Composable
fun SourceQualityBadge(
    quality: SourceQuality,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (quality) {
            SourceQuality.DOLBY_ATMOS -> Icon(painterResource(Res.drawable.ic_dolby_atmos), null, tint = color)
            SourceQuality.HI_RES_LOSSLESS, SourceQuality.LOSSLESS ->
                Icon(painterResource(Res.drawable.ic_lossless), null, tint = color)
            SourceQuality.HIGH_QUALITY -> Icon(
                rememberVectorPainter(SfIcons.Headphones),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = when (quality) {
                SourceQuality.DOLBY_ATMOS -> "Dolby Atmos"
                SourceQuality.HI_RES_LOSSLESS -> stringResource(Res.string.hi_res_lossless)
                SourceQuality.LOSSLESS -> stringResource(Res.string.lossless)
                SourceQuality.HIGH_QUALITY -> stringResource(Res.string.high_quality)
            },
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}
