package com.bxane.nuvx.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.data.model.MoodGenre
import com.bxane.nuvx.sharedui.resources.Res
import com.bxane.nuvx.sharedui.resources.browse_categories
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.browseCategories(categories: List<MoodGenre>, onClick: (MoodGenre) -> Unit) {
    item(key = "search:browse-header") {
        Text(
            text = stringResource(Res.string.browse_categories),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        )
    }
    categories.chunked(2).forEachIndexed { row, pair ->
        item(key = "search:browse:$row") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                pair.forEach { CategoryTile(it, Modifier.weight(1f)) { onClick(it) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun CategoryTile(item: MoodGenre, modifier: Modifier, onClick: () -> Unit) {
    val ground = item.stripeColor?.let { Color(it).copy(alpha = 1f) } ?: MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier
            .aspectRatio(181f / 105f)
            .clip(shape)
            .background(ground)
            .border(0.25.dp, Color.Black.copy(alpha = 0.1f), shape)
            .clickable(onClick = onClick),
    ) {
        if (item.thumbnailUrl != null) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.72f)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        blendMode = BlendMode.Multiply
                    },
            )
            Canvas(Modifier.fillMaxSize()) { drawRect(ground.copy(alpha = 0.25f), blendMode = BlendMode.Screen) }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.28f))))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}
