package com.bxane.nuvx.ui.components

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.ui.layout.ContentScale
import com.bxane.nuvx.ui.LocalPullToRefreshEnabled
import com.bxane.nuvx.ui.AppUiHost
import com.bxane.nuvx.ui.AppUi
import com.bxane.nuvx.sharedui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.bxane.nuvx.ui.player.MESH_CYCLE_MILLIS
import com.bxane.nuvx.ui.player.MeshGradientBackground
import com.bxane.nuvx.ui.player.WelcomeMeshPalettes
import com.bxane.nuvx.ui.player.cyclingMeshPalette
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bxane.nuvx.data.model.ROW_ART_PX
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.isMatchPending
import com.bxane.nuvx.data.model.isMatchMissing
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.CircularProgressIndicator
import com.bxane.nuvx.data.model.artworkAt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.border

val PAGE_GUTTER = 16.dp

val BAR_GUTTER = PAGE_GUTTER

val ROW_DIVIDER_INSET = PAGE_GUTTER + 68.dp

val TRACK_NUMBER_WIDTH = 24.dp

val NUMBERED_ROW_DIVIDER_INSET = PAGE_GUTTER + TRACK_NUMBER_WIDTH + PAGE_GUTTER

val FLOATING_BAR_MAX_WIDTH = 440.dp

val SHELF_CARD_WIDTH = 150.dp

fun Modifier.trackArtSize(height: Dp, isVideo: Boolean): Modifier =
    if (isVideo && !VideoThumbnails.square) size(width = height * (16f / 9f), height = height) else size(height)

object VideoThumbnails {
    var square by mutableStateOf(false)
}

private const val TRACK_COLUMN_FRACTION = 0.88f

private val TRACK_COLUMN_MAX_WIDTH = 400.dp

fun trackColumnWidth(available: Dp): Dp =
    minOf(available * TRACK_COLUMN_FRACTION, TRACK_COLUMN_MAX_WIDTH)

data class LibraryGridSpec(val columns: Int, val cardWidth: Dp)

private val LIBRARY_GRID_MIN_CARD_WIDTH = 140.dp

val LIBRARY_GRID_SPACING = 12.dp

private const val LIBRARY_GRID_MIN_COLUMNS = 2

private const val LIBRARY_GRID_MAX_COLUMNS = 5

fun libraryGrid(available: Dp): LibraryGridSpec {
    val raw = ((available + LIBRARY_GRID_SPACING) / (LIBRARY_GRID_MIN_CARD_WIDTH + LIBRARY_GRID_SPACING))
        .toInt()
    val columns = raw.coerceIn(LIBRARY_GRID_MIN_COLUMNS, LIBRARY_GRID_MAX_COLUMNS)
    val cardWidth = (available - LIBRARY_GRID_SPACING * (columns - 1)) / columns
    return LibraryGridSpec(columns, cardWidth)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onSwipeToQueue: (() -> Unit)? = null,
    rowBackground: Color = MaterialTheme.colorScheme.background,
    trackNumber: Int? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    downloadedTint: Color? = MaterialTheme.colorScheme.primary,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    activeTint: Color = MaterialTheme.colorScheme.primary,
    searchPlayingStyle: Boolean = false,
    selected: Boolean = false,
    showSourceQuality: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    SwipeToQueueBox(
        onSwipeToQueue = onSwipeToQueue,
        modifier = modifier,
    ) {
        SongRowContent(
            song = song,
            onClick = onClick,
            onLongPress = onLongPress,
            onMore = onMore ?: onLongPress,
            modifier = if (onSwipeToQueue != null) Modifier.background(rowBackground) else modifier,
            trackNumber = trackNumber,
            subtitleColor = subtitleColor,
            downloadedTint = downloadedTint,
            isCurrent = isCurrent,
            isPlaying = isPlaying,
            activeTint = activeTint,
            searchPlayingStyle = searchPlayingStyle,
            selected = selected,
            showSourceQuality = showSourceQuality,
            trailing = trailing,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToQueueBox(
    onSwipeToQueue: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (onSwipeToQueue == null) {
        content()
        return
    }

    val haptics = rememberHaptics()
    val swipeStateHolder = remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
    var boxWidth by remember { mutableFloatStateOf(0f) }

    val currentOnSwipeToQueue by rememberUpdatedState(onSwipeToQueue)

    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && currentOnSwipeToQueue != null) {
                val offset = try { swipeStateHolder.value?.requireOffset() ?: 0f } catch (e: Exception) { 0f }
                if (abs(offset) >= boxWidth * 0.45f) {
                    haptics.play(Haptic.Select)
                    currentOnSwipeToQueue?.invoke()
                }
            }
            false
        },
        positionalThreshold = { distance -> distance * 0.5f },
    )
    swipeStateHolder.value = swipeState

    LaunchedEffect(swipeState, boxWidth) {
        if (boxWidth <= 0f) return@LaunchedEffect
        val armAt = boxWidth * 0.45f
        var armed = false
        snapshotFlow { try { swipeState.requireOffset() } catch (e: Exception) { 0f } }
            .collect { offset ->
                val travelled = abs(offset)
                when {
                    !armed && travelled >= armAt -> {
                        armed = true
                        haptics.play(Haptic.Tick)
                    }
                    armed && travelled < armAt * 0.8f -> armed = false
                }
            }
    }

    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier.onSizeChanged { boxWidth = it.width.toFloat() },
        backgroundContent = { QueueSwipeBackground(swipeState) },
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSwipeBackground(swipeState: SwipeToDismissBoxState) {
    val playNext by AppUi.host.swipeToPlayNext.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                val offset = try { swipeState.requireOffset() } catch (e: Exception) { 0f }
                if (offset > 0f) {
                    clipRect(left = 0f, top = 0f, right = offset, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                } else if (offset < 0f) {
                    clipRect(left = size.width + offset, top = 0f, right = size.width, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .padding(horizontal = PAGE_GUTTER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        QueueSwipeLabel(playNext)
        QueueSwipeLabel(playNext)
    }
}

@Composable
private fun QueueSwipeLabel(playNext: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (playNext) SfIcons.PlaylistPlay else SfIcons.PlaylistAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(if (playNext) Res.string.play_next else Res.string.queue),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRowContent(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    downloadedTint: Color? = MaterialTheme.colorScheme.primary,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    activeTint: Color = MaterialTheme.colorScheme.primary,
    searchPlayingStyle: Boolean = false,
    selected: Boolean = false,
    showSourceQuality: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent && !searchPlayingStyle) activeTint else MaterialTheme.colorScheme.onBackground,
        label = "song row title",
    )
    val activeBackground by animateColorAsState(
        targetValue = if ((isCurrent && !searchPlayingStyle) || selected) activeTint.copy(alpha = 0.14f) else Color.Transparent,
        label = "song row background",
    )
    val matching = song.isMatchPending
    val unavailable = song.isMatchMissing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (unavailable) 0.45f else 1f)
            .background(activeBackground)
            .longPressMenuClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                liftPadding = PaddingValues(horizontal = PAGE_GUTTER),
            )
            .contextClick(onMore ?: onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (trackNumber != null) {
            Box(
                Modifier.width(TRACK_NUMBER_WIDTH).height(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (searchPlayingStyle && isCurrent && isPlaying) {
                    SearchPlayingBars()
                } else if (isCurrent) {
                    Icon(
                        imageVector = if (isPlaying) SfIcons.GraphicEq else SfIcons.PlayArrow,
                        contentDescription = stringResource(Res.string.now_playing),
                        tint = activeTint,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        text = "$trackNumber",
                        style = MaterialTheme.typography.bodyLarge,
                        color = subtitleColor,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Box(Modifier.trackArtSize(52.dp, song.isVideo)) {
                AsyncImage(
                    model = com.bxane.nuvx.ui.player.rememberRemoteArtworkUrl(song)?.artworkAt(ROW_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .thumbnailBorder(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                if (searchPlayingStyle && isCurrent && isPlaying) {
                    SearchPlayingBars(Modifier.align(Alignment.Center))
                }
            }
        }
        Spacer(Modifier.width(if (trackNumber == null) 14.dp else PAGE_GUTTER))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
            )
            Spacer(Modifier.height(2.dp))
            val subtitle = listOfNotNull(
                song.artist.takeIf { it.isNotBlank() },
                song.downloadFormat,
            ).joinToString(" · ")
            val quality = if (showSourceQuality) song.sourceQualityBadge() else null
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (quality != null) {
                    SourceQualityBadge(
                        quality = quality,
                        color = subtitleColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = if (quality != null && subtitle.isNotEmpty()) " · $subtitle" else subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (downloadedTint != null) {
            DownloadedBadge(song.videoId, downloadedTint)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                SfIcons.CheckCircle,
                contentDescription = stringResource(Res.string.selected),
                tint = activeTint,
                modifier = Modifier.size(20.dp),
            )
        }
        if (isCurrent && trackNumber == null && !searchPlayingStyle) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (isPlaying) SfIcons.GraphicEq else SfIcons.PlayArrow,
                contentDescription = stringResource(Res.string.now_playing),
                tint = activeTint,
                modifier = Modifier.size(20.dp),
            )
        }
        if (matching) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = subtitleColor,
            )
        } else {
            song.durationText?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = subtitleColor,
                )
            }
        }
        if (trailing != null && !matching && !unavailable) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
        if (onMore != null && !matching && !unavailable) {
            RowMoreButton(onClick = onMore, tint = subtitleColor)
        }
    }
}

@Composable
fun RowMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .leftOutOfHeldPicture()
            .hangIntoGutter(ROW_MORE_OUTSET)
            .size(36.dp)
            .menuAnchor()
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            SfIcons.MoreHoriz,
            contentDescription = stringResource(Res.string.more),
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

val ROW_MORE_OUTSET = 14.dp

fun Modifier.hangIntoGutter(outset: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val width = (placeable.width - outset.roundToPx()).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(0, 0) }
}


const val MOTION_FRAME_GAP_MS = 25L

val LocalPlayingBarsMotion = compositionLocalOf { true }

private val PLAYING_BAR_MINIMUMS = floatArrayOf(0.38f, 0.78f, 0.52f)

private fun playingBarHeight(index: Int, minimum: Float, ms: Long): Float {
    val duration = 520 + index * 130
    val peak = 260 + index * 50
    var t = (ms % (2L * duration)).toInt()
    if (t > duration) t = 2 * duration - t
    return if (t <= peak) {
        minimum + (1f - minimum) * t / peak
    } else {
        1f - (1f - minimum) * (t - peak) / (duration - peak)
    }
}

@Composable
fun SearchPlayingBars(modifier: Modifier = Modifier) {
    val elapsedNanos = remember { mutableLongStateOf(0L) }
    val running = LocalPlayingBarsMotion.current
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = Long.MIN_VALUE
        while (true) {
            withFrameNanos { now ->
                if (last != Long.MIN_VALUE) elapsedNanos.longValue += now - last
                last = now
            }
            delay(MOTION_FRAME_GAP_MS)
        }
    }
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.52f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 13.dp, height = 14.dp)) {
            val barWidth = 3.dp.toPx()
            val step = barWidth + 2.dp.toPx()
            val corner = CornerRadius(minOf(2.dp.toPx(), barWidth / 2f))
            val ms = elapsedNanos.longValue / 1_000_000L
            PLAYING_BAR_MINIMUMS.forEachIndexed { index, minimum ->
                val height = size.height * playingBarHeight(index, minimum, ms)
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(index * step, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = corner,
                )
            }
        }
    }
}

@Composable
fun DownloadedBadge(videoId: String, tint: Color, modifier: Modifier = Modifier) {
    if (videoId !in AppUi.host.downloadedIds()) return
    Spacer(Modifier.width(8.dp))
    Icon(
        SfIcons.DownloadDone,
        contentDescription = stringResource(Res.string.downloaded),
        tint = tint,
        modifier = modifier.size(16.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    state: PullToRefreshState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!LocalPullToRefreshEnabled.current) {
        Box(modifier.fillMaxSize()) { content() }
        return
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {},
    ) {
        content()
    }
}

@Composable
fun SignInBanner(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    motion: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(stiffness = 900f), label = "signInScale")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onSignIn),
    ) {
        MeshGradientBackground(
            palette = cyclingMeshPalette(WelcomeMeshPalettes, running = motion),
            modifier = Modifier.matchParentSize(),
            continuous = true,
            paused = !motion,
            driftMillis = 6_000,
            blurRadius = 36.dp,
            colorFadeMillis = MESH_CYCLE_MILLIS,
        )
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
            Text(
                text = stringResource(Res.string.sign_in_youtube_music),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.W800,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(Res.drawable.ic_google_g),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.sign_in_with_google),
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W700,
                    color = Color.Black,
                )
            }
        }
    }
}

@Composable
fun MessageState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER + 6.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(container, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
