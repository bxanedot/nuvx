package com.bxane.nuvx.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.SubscriptionState
import com.bxane.nuvx.download.DownloadState
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.ui.components.liquidGlassRim
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import com.bxane.nuvx.ui.icons.SfIcons
import kotlinx.coroutines.launch

@Composable
internal fun ActionRow(
    onPlay: () -> Unit,
    artistId: String,
    onDownloadCatalog: () -> Unit,
    catalogLoading: Boolean,
    bottomSpace: Dp = 22.dp,
    subscription: SubscriptionState? = null,
    onToggleSubscription: (() -> Unit)? = null,
    playColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HEADER_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(ARTIST_ACTION_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogDownloadButton(
            artistId = artistId,
            loading = catalogLoading,
            onClick = onDownloadCatalog,
            size = ARTIST_SIDE_BUTTON,
        )

        PlayCircle(
            onClick = onPlay,
            size = ARTIST_PLAY_BUTTON,
            iconScale = ARTIST_PLAY_ICON_SCALE,
            containerColor = playColor ?: Color.White,
        )

        if (subscription != null) {
            CircleIconButton(
                icon = if (subscription.subscribed) SfIcons.StarFill else SfIcons.Star,
                contentDescription = stringResource(
                    if (subscription.subscribed) R.string.unsubscribe else R.string.subscribe,
                ),
                onClick = { onToggleSubscription?.invoke() },
                haptic = if (subscription.subscribed) Haptic.ToggleOff else Haptic.ToggleOn,
                size = ARTIST_SIDE_BUTTON,
            )
        } else {
            CircleIconButton(
                icon = SfIcons.Star,
                contentDescription = stringResource(R.string.subscribe),
                onClick = {},
                size = ARTIST_SIDE_BUTTON,
                enabled = false,
            )
        }
    }
    Spacer(Modifier.height(bottomSpace))
}

@Composable
internal fun ActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
) {
    val haptics = rememberHaptics()
    val content = if (filled) Color.Black else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .height(size)
            .clip(CircleShape)
            .then(
                if (filled) {
                    Modifier.background(Color.White)
                } else {
                    Modifier
                        .background(Color.White.copy(alpha = LIGHT_FILL_ALPHA), CircleShape)
                        .liquidGlassRim(CircleShape)
                        .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_EDGE_ALPHA), CircleShape)
                },
            )
            .clickable {
                haptics.play(Haptic.Resume)
                onClick()
            }
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlayCircle(
    onClick: () -> Unit,
    size: Dp,
    iconScale: Float,
    containerColor: Color,
) {
    val haptics = rememberHaptics()
    val description = stringResource(R.string.play)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable {
                haptics.play(Haptic.Resume)
                onClick()
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawCircle(containerColor)
                val box = this.size.minDimension * iconScale
                val unit = box / 24f
                val origin = Offset((this.size.width - box) / 2f, (this.size.height - box) / 2f)
                val triangle = androidx.compose.ui.graphics.Path().apply {
                    moveTo(origin.x + 6.8f * unit, origin.y + 4.8f * unit)
                    lineTo(origin.x + 19.2f * unit, origin.y + 12f * unit)
                    lineTo(origin.x + 6.8f * unit, origin.y + 19.2f * unit)
                    close()
                }
                drawPath(triangle, Color.Black, blendMode = BlendMode.Clear)
                drawPath(
                    triangle,
                    Color.Black,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2f * unit,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                    blendMode = BlendMode.Clear,
                )
            }
            .semantics { contentDescription = description },
    )
}

@Composable
internal fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    haptic: Haptic = Haptic.Tap,
    size: Dp = 50.dp,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else DISABLED_ACTION_ALPHA)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = LIGHT_FILL_ALPHA), CircleShape)
            .liquidGlassRim(CircleShape)
            .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_EDGE_ALPHA), CircleShape)
            .clickable(enabled = enabled) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

private enum class CatalogPhase { Idle, Fetching, Downloading, Done }

@Composable
private fun CatalogDownloadButton(
    artistId: String,
    loading: Boolean,
    onClick: () -> Unit,
    size: Dp,
) {
    val active by Downloads.active.collectAsStateWithLifecycle()
    val requested by Downloads.requested.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    val ids = requested[artistId].orEmpty()
    val running = ids.mapNotNull { active[it] }.filter { it is DownloadState.Queued || it is DownloadState.Running }
    val savedCount = ids.count { it in saved }
    val phase = when {
        loading -> CatalogPhase.Fetching
        running.isNotEmpty() -> CatalogPhase.Downloading
        ids.isNotEmpty() && savedCount == ids.size -> CatalogPhase.Done
        else -> CatalogPhase.Idle
    }
    val partial = running.sumOf { ((it as? DownloadState.Running)?.fraction ?: 0f).toDouble() }.toFloat()
    val fraction = if (ids.isEmpty()) 0f else (savedCount + partial) / ids.size
    val progress by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "catalog_progress")
    val ringAlpha by animateFloatAsState(
        if (phase == CatalogPhase.Fetching || phase == CatalogPhase.Downloading) 1f else 0f,
        tween(400),
        label = "catalog_ring",
    )

    val motion = rememberInfiniteTransition(label = "catalog_motion")
    val spin by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "catalog_spin",
    )
    val bob by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "catalog_bob",
    )

    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val ringColor = MaterialTheme.colorScheme.onSurface
    val label = stringResource(
        when (phase) {
            CatalogPhase.Done -> R.string.downloaded
            CatalogPhase.Fetching, CatalogPhase.Downloading -> R.string.downloading
            CatalogPhase.Idle -> R.string.download_catalog
        },
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
            }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = LIGHT_FILL_ALPHA), CircleShape)
            .liquidGlassRim(CircleShape)
            .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_EDGE_ALPHA), CircleShape)
            .drawWithContent {
                drawContent()
                if (ringAlpha == 0f) return@drawWithContent
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2 + 1.dp.toPx()
                val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                if (phase == CatalogPhase.Fetching) {
                    drawArc(
                        ringColor.copy(alpha = ringAlpha), spin - 90f, 80f, false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                } else {
                    drawArc(
                        ringColor.copy(alpha = 0.18f * ringAlpha), 0f, 360f, false, topLeft, arcSize,
                        style = Stroke(stroke),
                    )
                    drawArc(
                        ringColor.copy(alpha = ringAlpha), -90f, 360f * progress, false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            .clickable(enabled = phase != CatalogPhase.Fetching, onClickLabel = label) {
                haptics.play(Haptic.Tap)
                scope.launch {
                    press.animateTo(0.86f, tween(90))
                    press.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                }
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = phase == CatalogPhase.Done,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f) + fadeIn())
                    .togetherWith(scaleOut(targetScale = 0.4f) + fadeOut())
            },
            label = "catalog_icon",
        ) { done ->
            Icon(
                imageVector = if (done) SfIcons.Downloaded else SfIcons.Download,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(size * 0.44f)
                    .graphicsLayer {
                        when (phase) {
                            CatalogPhase.Downloading -> translationY = bob * 3.dp.toPx()
                            CatalogPhase.Fetching -> alpha = 0.55f + 0.45f * bob
                            else -> Unit
                        }
                    },
            )
        }
    }
}
