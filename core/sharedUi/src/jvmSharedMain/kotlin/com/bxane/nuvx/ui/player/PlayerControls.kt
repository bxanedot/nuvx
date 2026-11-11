package com.bxane.nuvx.ui.player

import com.bxane.nuvx.sharedui.resources.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.data.model.Song

@Composable
internal fun playbackOriginText(song: Song, playedBy: String?): String =
    playedBy?.let {
        stringResource(Res.string.played_by, it)
    } ?: song.radioName?.let {
        stringResource(Res.string.playing_radio, it)
    } ?: stringResource(
        Res.string.playing_from,
        song.playbackSource ?: song.albumName ?: stringResource(Res.string.queue),
    )

@Composable
internal fun PlaybackOriginCaption(
    text: String,
    onClick: () -> Unit,
    textAlign: TextAlign,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalPlayerChrome.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.55f),
                offset = Offset(0f, 1f),
                blurRadius = 4f,
            ),
        ),
        color = Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier
            .additiveChrome(chrome)
            .clickable(onClick = onClick)
            .padding(contentPadding),
    )
}

internal fun Modifier.opensPage(browseId: String?, onOpen: (String) -> Unit): Modifier =
    if (browseId == null) {
        this
    } else {
        clip(LinkPressShape).clickable { onOpen(browseId) }
    }

internal val LinkPressShape = RoundedCornerShape(6.dp)

internal fun Modifier.bleedHorizontally(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = constraints.maxWidth + extra,
        )
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) {
        placeable.place(-(placeable.width - width) / 2, 0)
    }
}
