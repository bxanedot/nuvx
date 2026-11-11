package com.bxane.nuvx.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.CARD_ART_PX
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.ui.components.FadingText
import com.bxane.nuvx.ui.components.continuous
import com.bxane.nuvx.ui.components.longPressMenuClickable
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import com.bxane.nuvx.ui.icons.SfIcons
import com.bxane.nuvx.ui.theme.ArtworkPalette
internal fun List<HomeShelf>.topRelease(): ShelfItem? =
    asSequence()
        .flatMap { it.items.asSequence() }
        .filter { it.browseId?.startsWith("MPRE") == true }
        .withIndex()
        .maxWithOrNull(
            compareBy<IndexedValue<ShelfItem>> { it.value.releaseYear() ?: 0 }
                .thenByDescending { it.index },
        )?.value

private val RELEASE_YEAR = Regex("""\b(19|20)\d{2}\b""")

private fun ShelfItem.releaseYear(): Int? =
    RELEASE_YEAR.find(subtitle)?.value?.toIntOrNull()

@androidx.annotation.StringRes
private fun ShelfItem.recentLabel(): Int {
    val kind = subtitle.lowercase()
    return when {
        kind.contains("single") -> R.string.recent_single
        Regex("""\bep\b""").containsMatchIn(kind) -> R.string.recent_ep
        else -> R.string.recent_album
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TopReleaseCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    saved: Boolean?,
    onToggleSaved: (() -> Unit)?,
) {
    val shape = remember { RoundedCornerShape(TOP_RELEASE_CORNER).continuous() }
    val coverShape = remember { RoundedCornerShape(TOP_RELEASE_CORNER - TOP_RELEASE_INSET).continuous() }
    val haptics = rememberHaptics()
    val hasButton = onToggleSaved != null && saved != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ARTIST_CONTENT_GUTTER)
            .padding(bottom = 22.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = LIGHT_FILL_ALPHA), shape)
            .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_EDGE_ALPHA), shape)
            .clip(shape)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(
                start = TOP_RELEASE_INSET,
                top = TOP_RELEASE_INSET,
                bottom = TOP_RELEASE_INSET,
                end = if (hasButton) 19.dp else TOP_RELEASE_INSET,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .clip(coverShape)
                .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_COVER_EDGE_ALPHA), coverShape)
                .background(palette.elevated),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val secondary = palette.onBackground.copy(alpha = TOP_RELEASE_SECONDARY_ALPHA)
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FadingText(
                text = item.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    lineHeight = TOP_RELEASE_TITLE_LINE_HEIGHT,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                ),
                color = palette.onBackground,
                maxLines = 2,
            )
            Text(
                text = stringResource(item.recentLabel()),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
            )
        }
        if (onToggleSaved != null && saved != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = LIGHT_FILL_ALPHA), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_EDGE_ALPHA), CircleShape)
                    .clickable {
                        haptics.play(if (saved) Haptic.ToggleOff else Haptic.ToggleOn)
                        onToggleSaved()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (saved) SfIcons.Check else SfIcons.Add,
                    contentDescription = stringResource(
                        if (saved) R.string.remove_from_library else R.string.add_to_library,
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

private val TOP_RELEASE_CORNER = 24.dp
private val TOP_RELEASE_INSET = 12.dp

private const val TOP_RELEASE_SECONDARY_ALPHA = 0.55f

private val TOP_RELEASE_TITLE_LINE_HEIGHT = 22.sp
private const val TOP_RELEASE_COVER_EDGE_ALPHA = 0.215f
