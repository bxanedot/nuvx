package com.bxane.nuvx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.core.PlayerState
import com.bxane.nuvx.core.hiResArtwork
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

internal data class NuvxPalette(
    val dark: Boolean,
    val background: Color,
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
    val card: Color,
    val divider: Color,
    val controlTrack: Color,
) {
    companion object {
        val Light = NuvxPalette(false, Color(0xFFF6F6F8), Color(0xFF19191C), Color(0xFF626268), Color(0xFF626268),
            Color.White, Color(0x1A000000), Color(0x0F000000))
        val Dark = NuvxPalette(true, Color.Black, Color.White, Color.White.copy(0.62f), Color.White.copy(0.58f),
            Color(0xFF1A1A1C), Color.White.copy(0.12f), Color.White.copy(0.08f))
    }
}

internal val LocalNuvxPalette = compositionLocalOf { NuvxPalette.Dark }

/** Android can provide a captured backdrop renderer; other targets keep the shared fallback. */
typealias NuvxGlassRenderer = @Composable (Modifier, Shape, Boolean, Boolean, NuvxGlassSpec) -> Modifier
typealias NuvxGlassContentCapture = @Composable (content: @Composable () -> Unit) -> Unit
val LocalNuvxGlassRenderer = compositionLocalOf<NuvxGlassRenderer?> { null }
val LocalNuvxGlassContentCapture = compositionLocalOf<NuvxGlassContentCapture> { { it() } }

@Composable
internal fun NuvxGlassCaptureContent(content: @Composable () -> Unit) = LocalNuvxGlassContentCapture.current(content)

/** Effect sizes for the three glass surfaces used by Nuvx. */
data class NuvxGlassSpec(
    val blurRadius: Dp = 2.dp,
    val refractionHeight: Dp = 12.dp,
    val refractionAmount: Dp = 24.dp,
    val saturation: Float = 1.5f,
    val depthEffect: Boolean = false,
    val chromaticAberration: Boolean = false,
) {
    companion object {
        val Chrome = NuvxGlassSpec()
        val Control = NuvxGlassSpec(refractionHeight = 16.dp, refractionAmount = 28.dp)
        val Sheet = NuvxGlassSpec(blurRadius = 8.dp, refractionHeight = 20.dp, refractionAmount = 32.dp)
    }
}

object NuvxColors {
    val Background: Color @Composable get() = LocalNuvxPalette.current.background
    val Text: Color @Composable get() = LocalNuvxPalette.current.text
    val Secondary: Color @Composable get() = LocalNuvxPalette.current.secondary
    val Tertiary: Color @Composable get() = LocalNuvxPalette.current.tertiary
    val Card: Color @Composable get() = LocalNuvxPalette.current.card
    val Divider: Color @Composable get() = LocalNuvxPalette.current.divider
    val ControlTrack: Color @Composable get() = LocalNuvxPalette.current.controlTrack
    val Accent: Color @Composable get()
        {
            val color = LocalUserAccent.current
            return if (!LocalNuvxPalette.current.dark && color.luminance() > 0.82f) Color(0xFF222226) else color
        }
    val OnAccent: Color @Composable get() = if (Accent.luminance() > 0.5f) Color.Black else Color.White
}

val LocalDesktopLayout = compositionLocalOf { false }
internal val LocalGlassMotionReduced = compositionLocalOf { false }
internal val LocalUserAccent = compositionLocalOf { Color(0xFF67ACFF) }
val BottomChromePadding = 170.dp

@Composable
fun desktopAwareBottomPadding(): Dp = if (LocalDesktopLayout.current) 24.dp else BottomChromePadding

/**
 * Frosted-glass look: translucent dark fill plus a hairline light border. Compose can't blur what's *behind* a
 * layer portably, so the fill is dense enough to stay legible over artwork.
 */
fun Modifier.glass(shape: Shape = CircleShape) = composed {
    val dark = LocalNuvxPalette.current.dark
    val renderer = LocalNuvxGlassRenderer.current
    if (renderer != null) renderer(this@glass, shape, false, dark, NuvxGlassSpec.Chrome)
    else clip(shape)
        .background(if (dark) Color(0xFF26262A).copy(alpha = 0.86f) else Color.White.copy(alpha = 0.88f), shape)
        .border(0.7.dp, (if (dark) Color.White else Color.Black).copy(alpha = 0.14f), shape)
}

/** Cross-platform floating glass treatment with a pointer-following specular highlight. */
// shortcut: shared Compose cannot sample the live backdrop; upgrade when a portable backdrop effect exists.
fun Modifier.liquidGlass(shape: Shape = CircleShape, spec: NuvxGlassSpec = NuvxGlassSpec.Chrome) = composed {
    val reduceMotion = LocalGlassMotionReduced.current
    val accent = LocalUserAccent.current
    val dark = LocalNuvxPalette.current.dark
    val renderer = LocalNuvxGlassRenderer.current
    if (renderer != null) return@composed renderer(this@liquidGlass, shape, true, dark, spec)
    var pointerPosition by remember { mutableStateOf(Offset.Zero) }
    var pointerActive by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(
        if (pointerActive && !reduceMotion) 10.dp else 4.dp,
        animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessLow),
        label = "liquidGlassLift",
    )
    this
        .pointerInput(reduceMotion) {
            if (reduceMotion) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    when (event.type) {
                        PointerEventType.Enter, PointerEventType.Move, PointerEventType.Press -> {
                            pointerPosition = change.position
                            pointerActive = true
                        }
                        PointerEventType.Exit, PointerEventType.Release -> pointerActive = false
                    }
                }
            }
        }
        .shadow(elevation, shape, clip = false)
        .clip(shape)
        .background((if (dark) Color(0xFF17171B) else Color.White).copy(alpha = if (dark) 0.70f else 0.82f), shape)
        .background(Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.17f else 0.62f),
            Color.White.copy(alpha = if (dark) 0.045f else 0.18f), Color.Transparent,
            Color.Black.copy(alpha = if (dark) 0.16f else 0.035f),
        )), shape)
        .drawWithCache {
            val responding = pointerActive && !reduceMotion
            val sheen = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = if (responding) 0.24f else 0.12f), accent.copy(alpha = 0.08f), Color.Transparent),
                center = if (responding) pointerPosition else Offset(size.width * 0.18f, size.height * 0.08f),
                radius = size.width.coerceAtLeast(size.height) * 0.9f,
            )
            onDrawBehind { drawRect(sheen) }
        }
        .border(0.8.dp, Brush.verticalGradient(listOf(
            (if (dark) Color.White else Color.Black).copy(alpha = 0.34f),
            (if (dark) Color.White else Color.Black).copy(alpha = 0.08f),
            (if (dark) Color.White else Color.Black).copy(alpha = 0.18f),
        )), shape)
}

@Composable fun LargeTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(horizontal = 20.dp), color = NuvxColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)

@Composable fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) =
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = NuvxColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        action?.invoke()
    }

/** Visible keyboard/D-pad focus: a white ring, so TV remotes and keyboards can see where they are. Must precede the clickable. */
@Composable fun Modifier.focusRing(shape: Shape = CircleShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }.border(if (focused) 2.5.dp else 0.dp, if (focused) NuvxColors.Text else Color.Transparent, shape)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCircleButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    onLongClick: (() -> Unit)? = null,
    liquid: Boolean = true,
    motionEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val animate = motionEnabled && !LocalGlassMotionReduced.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (animate && pressed) 0.94f else 1f,
        animationSpec = if (animate) spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium) else snap(),
        label = "glassCirclePress",
    )
    val elevation by animateDpAsState(
        if (animate && pressed) 3.dp else 10.dp,
        animationSpec = if (animate) spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "glassCircleLift",
    )
    val click = if (onLongClick == null) {
        Modifier.clickable(interactionSource = interaction, onClick = onClick, role = Role.Button)
    } else {
        Modifier.combinedClickable(
            interactionSource = interaction,
            onClick = onClick,
            onLongClick = onLongClick,
            role = Role.Button,
        )
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .shadow(elevation, CircleShape, clip = false)
            .then(if (liquid) Modifier.liquidGlass(CircleShape, NuvxGlassSpec.Control) else Modifier.glass(CircleShape))
            .clip(CircleShape)
            .focusRing()
            .then(click)
            .semantics { contentDescription = label; role = Role.Button },
        content = content,
    )
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    label: String,
    size: Dp = 48.dp,
    onClick: () -> Unit,
    symbolName: String? = null,
    selected: Boolean = false,
    motionEnabled: Boolean = true,
    liquid: Boolean = true,
) = GlassCircleButton(label, onClick, size = size, liquid = liquid, motionEnabled = motionEnabled) {
    if (symbolName == null) Icon(icon, null, Modifier.size(size * 0.46f), tint = NuvxColors.Text)
    else NuvxGlyphIcon(symbolName, Modifier.size(size * 0.46f), tint = NuvxColors.Text, selected = selected, motionEnabled = motionEnabled)
}

@Composable fun PlainIconButton(icon: ImageVector, label: String, size: Dp, iconSize: Dp = size * 0.62f, enabled: Boolean = true, onClick: () -> Unit) =
    Box(Modifier.size(size).clip(CircleShape).focusRing().clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(iconSize), tint = if (enabled) NuvxColors.Text else NuvxColors.Tertiary.copy(alpha = 0.5f))
    }

@Composable fun PlayPauseIconButton(isPlaying: Boolean, onClick: () -> Unit, size: Dp, iconSize: Dp = size * 0.62f, enabled: Boolean = true, reduceMotion: Boolean = false) =
    Box(Modifier.size(size).clip(CircleShape).focusRing().clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = if (isPlaying) "Pause" else "Play"; role = Role.Button }, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                else (scaleIn(initialScale = 0.82f, animationSpec = tween(150)) + fadeIn(tween(120))) togetherWith
                    (scaleOut(targetScale = 0.82f, animationSpec = tween(110)) + fadeOut(tween(90)))
            },
            label = "playPauseIcon",
        ) { playing ->
            Icon(if (playing) NuvxIcons.Pause else NuvxIcons.Play, null, Modifier.size(iconSize),
                tint = if (enabled) NuvxColors.Text else NuvxColors.Tertiary.copy(alpha = 0.5f))
        }
    }

internal const val MINI_PLAYER_HEIGHT_DP = 56

/** Floating pill: artwork · title/artist · play/pause · next. Tapping the text opens the full player. */
@Composable fun MiniPlayerPill(state: PlayerState, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier, onPrevious: () -> Unit = {}, desktop: Boolean = false, androidUi: Boolean = false, reduceMotion: Boolean = false) {
    val track = state.current ?: return
    val progress = miniPlayerProgressFraction(state.positionMs, state.durationMs)
    val height = (if (androidUi) 64 else MINI_PLAYER_HEIGHT_DP).dp
    val swipe = if (desktop) Modifier.miniPlayerGestures(onNext, onPrevious, onOpen, reduceMotion) else Modifier.trackSwipe(onNext = onNext, onPrevious = onPrevious, onUp = onOpen, reduceMotion = reduceMotion)
    Box(modifier.fillMaxWidth().then(if (desktop) Modifier.height(height) else Modifier.heightIn(min = height)).then(swipe)
        .liquidGlass(RoundedCornerShape(height / 2)).clickable(onClick = onOpen)) {
        Row((if (desktop) Modifier.fillMaxWidth().height(height).padding(horizontal = 12.dp, vertical = 8.dp)
            else Modifier.fillMaxWidth().heightIn(min = height).padding(start = 10.dp, end = 8.dp, bottom = 4.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            // While the full player is open its cover stands in for this one; the two morph into each other (see ArtMorph).
            val morph = LocalArtMorph.current
            val artworkSize = if (desktop) 40.dp else if (androidUi) 48.dp else 52.dp
            Box(Modifier.size(width = artworkSize, height = if (desktop || androidUi) artworkSize else 44.dp)) {
                if (morph == null) ArtworkTile(track, Modifier.fillMaxSize())
                else androidx.compose.animation.AnimatedVisibility(visible = !morph.open, enter = fadeIn(), exit = fadeOut()) { ArtworkTile(track, Modifier.fillMaxSize().morphArt(this)) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, color = NuvxColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.buffering) "Loading…" else track.artistLine, color = NuvxColors.Secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val slot = if (desktop) 40.dp else 44.dp
            val glyph = if (desktop) 32.dp else slot * 0.62f
            PlayPauseIconButton(state.isPlaying, onToggle, slot, iconSize = glyph, reduceMotion = reduceMotion)
            if (desktop) Spacer(Modifier.width(8.dp))
            PlainIconButton(NuvxIcons.Forward, "Next", slot, iconSize = glyph, enabled = state.index < state.queue.lastIndex, onClick = onNext)
        }
        if (state.durationMs > 0) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp).height(2.dp)
            .clip(CircleShape).semantics {
                contentDescription = "Playback progress"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            }) {
            Box(Modifier.fillMaxSize().background(NuvxColors.ControlTrack))
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(NuvxColors.Accent))
        }
    }
}

internal fun miniPlayerProgressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

internal enum class MiniPlayerGestureAction { NONE, PREVIOUS, NEXT, OPEN }

internal fun miniPlayerGestureAction(dx: Float, dy: Float, threshold: Float): MiniPlayerGestureAction = when {
    kotlin.math.abs(dx) >= threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) ->
        if (dx < 0f) MiniPlayerGestureAction.NEXT else MiniPlayerGestureAction.PREVIOUS
    -dy >= threshold && -dy > kotlin.math.abs(dx) * 1.5f -> MiniPlayerGestureAction.OPEN
    else -> MiniPlayerGestureAction.NONE
}

@Composable
private fun Modifier.miniPlayerGestures(onNext: () -> Unit, onPrevious: () -> Unit, onOpen: () -> Unit, reduceMotion: Boolean): Modifier {
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val x by animateFloatAsState(if (dragging) dx * 0.35f else 0f,
        if (reduceMotion || dragging) snap() else spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), label = "miniPlayerX")
    val y by animateFloatAsState(if (dragging) minOf(0f, dy) * 0.5f else 0f,
        if (reduceMotion || dragging) snap() else spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), label = "miniPlayerY")
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    return graphicsLayer { translationX = x; translationY = y }.pointerInput(onNext, onPrevious, onOpen, threshold) {
        detectDragGestures(
            onDragStart = { dragging = true },
            onDragEnd = {
                val action = miniPlayerGestureAction(dx, dy, threshold)
                dx = 0f; dy = 0f; dragging = false
                when (action) {
                    MiniPlayerGestureAction.NEXT -> onNext()
                    MiniPlayerGestureAction.PREVIOUS -> onPrevious()
                    MiniPlayerGestureAction.OPEN -> onOpen()
                    MiniPlayerGestureAction.NONE -> Unit
                }
            },
            onDragCancel = { dx = 0f; dy = 0f; dragging = false },
        ) { change, drag -> change.consume(); dx += drag.x; dy += drag.y }
    }
}

data class Tab(val label: String, val icon: ImageVector, val symbolName: String? = null)

internal data class NavigationMagnetValues(val offsetDp: Float, val scale: Float, val rotationDegrees: Float, val distance: Int)

internal fun navigationMagnetValues(itemIndex: Int, selectedIndex: Int, iconSizeDp: Float = 24f): NavigationMagnetValues {
    val delta = itemIndex - selectedIndex
    val distance = abs(delta)
    val pull = 0.55f
    val grow = 1.16f + 0.22f * pull
    if (distance == 0) return NavigationMagnetValues(0f, grow, 0f, 0)

    val falloff = exp(-((distance - 1) / 1.8f).toDouble()).toFloat()
    val room = iconSizeDp * (grow - 1f) / 2f
    val aura = 3f + 9f * pull
    val direction = if (delta < 0) -1f else 1f
    return NavigationMagnetValues(
        offsetDp = direction * (room + aura * falloff),
        scale = 1f,
        rotationDegrees = direction * 3f * falloff,
        distance = distance,
    )
}

@Composable
internal fun MagneticNavigationIcon(
    icon: ImageVector,
    itemIndex: Int,
    selectedIndex: Int,
    size: Dp,
    tint: Color,
    vertical: Boolean = false,
    symbolName: String? = null,
    selected: Boolean = false,
    motionEnabled: Boolean = true,
    trigger: Any? = selectedIndex,
) {
    val field = navigationMagnetValues(itemIndex, selectedIndex, size.value)
    val offset = remember(itemIndex) { Animatable(0f) }
    val scaleXAnimation = remember(itemIndex) { Animatable(1f) }
    val scaleYAnimation = remember(itemIndex) { Animatable(1f) }
    val rotation = remember(itemIndex) { Animatable(0f) }

    LaunchedEffect(itemIndex, selectedIndex) {
        delay(field.distance * 22L)
        coroutineScope {
            launch { offset.animateTo(field.offsetDp, spring(dampingRatio = 0.72f, stiffness = 460f)) }
            launch { scaleXAnimation.animateTo(field.scale, spring(dampingRatio = 0.72f, stiffness = 460f * 1.24f)) }
            launch { scaleYAnimation.animateTo(field.scale, spring(dampingRatio = 0.72f, stiffness = 460f * 0.86f)) }
            launch { rotation.animateTo(field.rotationDegrees, spring(dampingRatio = 0.72f, stiffness = 460f)) }
        }
    }

    val modifier = Modifier.size(size).graphicsLayer {
        if (vertical) translationY = offset.value * density else translationX = offset.value * density
        scaleX = scaleXAnimation.value
        scaleY = scaleYAnimation.value
        rotationZ = rotation.value
    }
    if (symbolName == null) Icon(icon, null, modifier, tint = tint)
    else NuvxGlyphIcon(symbolName, modifier, size, tint, selected, trigger, animateOnTrigger = selected, motionEnabled = motionEnabled)
}

/** Glass capsule of tabs with a spring-moving selection pill, plus a separate round search button. */
@Composable fun TabBar(tabs: List<Tab>, selectedIndex: Int, searchSelected: Boolean, onSelect: (Int) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier, androidUi: Boolean = false, reduceMotion: Boolean = false, magneticEnabled: Boolean = false) {
    val barHeight = if (androidUi) 80.dp else 66.dp
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(barHeight).liquidGlass(RoundedCornerShape(barHeight / 2)).padding(5.dp)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val slotWidth = if (tabs.isEmpty()) 0.dp else maxWidth / tabs.size
                val targetOffset = slotWidth * selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
                val offset = remember { Animatable(targetOffset.value) }
                LaunchedEffect(targetOffset, reduceMotion) {
                    if (reduceMotion) offset.snapTo(targetOffset.value)
                    else offset.animateTo(targetOffset.value, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
                }
                val stretch = if (reduceMotion || slotWidth.value <= 0f) 0f
                    else (abs(offset.velocity) / slotWidth.value * 0.014f).coerceIn(0f, 0.12f)
                val indicatorAlpha by androidx.compose.animation.core.animateFloatAsState(
                    if (searchSelected) 0f else 1f,
                    if (reduceMotion) snap() else tween(100),
                    label = "tabSelectionVisibility",
                )
                Box(Modifier.offset(x = offset.value.dp).width(slotWidth).fillMaxHeight().graphicsLayer {
                    scaleX = 1f + stretch
                    scaleY = 1f - stretch * 0.36f
                    rotationZ = when {
                        offset.velocity > 0f -> stretch * 18f
                        offset.velocity < 0f -> -stretch * 18f
                        else -> 0f
                    }
                }.liquidGlass(RoundedCornerShape(barHeight / 2 - 5.dp))
                    .background(Color.White.copy(alpha = 0.10f * indicatorAlpha), RoundedCornerShape(barHeight / 2 - 5.dp))
                    .graphicsLayer { alpha = indicatorAlpha })
                Row(Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { i, tab ->
                        val active = i == selectedIndex && !searchSelected
                        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(barHeight / 2 - 5.dp)).clickable { onSelect(i) }
                            .semantics { selected = active; role = Role.Tab; contentDescription = tab.label },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            if (magneticEnabled && !reduceMotion) MagneticNavigationIcon(
                                tab.icon, i, if (searchSelected) tabs.size else selectedIndex, 24.dp,
                                if (active) NuvxColors.Accent else NuvxColors.Secondary,
                                symbolName = tab.symbolName, selected = active, motionEnabled = true,
                            ) else if (tab.symbolName != null) NuvxGlyphIcon(
                                tab.symbolName, Modifier.size(24.dp), tint = if (active) NuvxColors.Accent else NuvxColors.Secondary,
                                selected = active, trigger = active, animateOnTrigger = active, motionEnabled = !reduceMotion,
                            ) else Icon(tab.icon, null, Modifier.size(24.dp), tint = if (active) NuvxColors.Accent else NuvxColors.Secondary)
                            Text(tab.label, fontSize = 11.sp, color = if (active) NuvxColors.Text else NuvxColors.Secondary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        Box(Modifier.size(barHeight).liquidGlass().background(if (searchSelected) Color.White.copy(alpha = 0.10f) else Color.Transparent).clickable(onClick = onSearch)
            .semantics { contentDescription = "Search"; role = Role.Tab; selected = searchSelected }, contentAlignment = Alignment.Center) {
            if (magneticEnabled && !reduceMotion) MagneticNavigationIcon(NuvxIcons.Search, tabs.size, if (searchSelected) tabs.size else selectedIndex,
                28.dp, NuvxColors.Text, symbolName = "magnifyingglass", selected = searchSelected, motionEnabled = true)
            else NuvxGlyphIcon("magnifyingglass", Modifier.size(28.dp), tint = if (searchSelected) NuvxColors.Accent else NuvxColors.Text,
                selected = searchSelected, trigger = searchSelected, animateOnTrigger = searchSelected, motionEnabled = !reduceMotion)
        }
    }
}

/** Leading icon · label · chevron row, as used in Library and Settings lists. */
@Composable fun NavRow(icon: ImageVector, label: String, detail: String? = null, onClick: () -> Unit, symbolName: String? = null, motionEnabled: Boolean = true) {
    var taps by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth().clickable(onClick = {
        if (symbolName != null && motionEnabled) taps++
        onClick()
    }).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (symbolName == null) Icon(icon, null, Modifier.size(24.dp), tint = NuvxColors.Text)
        else NuvxGlyphIcon(symbolName, Modifier.size(24.dp), tint = NuvxColors.Text, trigger = taps, animateOnTrigger = true, motionEnabled = motionEnabled)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(label, color = NuvxColors.Text, fontSize = 17.sp)
            detail?.let { Text(it, color = NuvxColors.Secondary, fontSize = 13.sp) }
        }
        Icon(NuvxIcons.Chevron, null, Modifier.size(18.dp), tint = NuvxColors.Tertiary)
    }
}

/**
 * Swipe left for the next track, right for the previous one, and optionally up (e.g. to open the player).
 * The content follows the finger a little as feedback; buttons stay available for people who don't swipe.
 */
@Composable fun Modifier.trackSwipe(onNext: () -> Unit, onPrevious: () -> Unit, onUp: (() -> Unit)? = null, reduceMotion: Boolean = false): Modifier {
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    val x by animateFloatAsState(if (dragging) dx * 0.35f else 0f,
        if (reduceMotion || dragging) snap() else spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), label = "miniPlayerSwipeX")
    val y by animateFloatAsState(if (dragging) minOf(0f, dy) * 0.2f else 0f,
        if (reduceMotion || dragging) snap() else spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), label = "miniPlayerSwipeY")
    return this
        .graphicsLayer { translationX = x; translationY = y }
        .pointerInput(onNext, onPrevious, onUp, threshold) {
            // Without a swipe-up action only sideways drags are taken, so vertical drags reach the parent (e.g. pull the player down to close).
            if (onUp == null) detectHorizontalDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = {
                    if (dx < -threshold) onNext() else if (dx > threshold) onPrevious()
                    dx = 0f; dy = 0f; dragging = false
                },
                onDragCancel = { dx = 0f; dy = 0f; dragging = false },
            ) { change, drag -> change.consume(); dx += drag }
            else detectDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = {
                    when {
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx < -threshold -> onNext()
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx > threshold -> onPrevious()
                        -dy > threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> onUp()
                    }
                    dx = 0f; dy = 0f; dragging = false
                },
                onDragCancel = { dx = 0f; dy = 0f; dragging = false },
            ) { change, drag -> change.consume(); dx += drag.x; dy += drag.y }
        }
}

/**
 * Long-press a row and drag it to a new place in a list; screen readers get Move up / Move down actions instead.
 * The row lifts and follows the finger; the move happens on release, by whole row heights.
 */
@Composable fun Modifier.reorderable(index: Int, lastIndex: Int, onMove: (from: Int, to: Int) -> Unit): Modifier {
    var dy by remember { mutableStateOf(0f) }
    var height by remember { mutableStateOf(1) }
    val at by rememberUpdatedState(index)
    val last by rememberUpdatedState(lastIndex)
    val move by rememberUpdatedState(onMove)
    return this
        .onSizeChanged { height = it.height.coerceAtLeast(1) }
        .zIndex(if (dy != 0f) 1f else 0f)
        .graphicsLayer { translationY = dy; if (dy != 0f) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f } }
        .background(if (dy != 0f) NuvxColors.Card else Color.Transparent, RoundedCornerShape(12.dp))
        .semantics {
            customActions = listOfNotNull(
                if (index > 0) CustomAccessibilityAction("Move up") { onMove(index, index - 1); true } else null,
                if (index < lastIndex) CustomAccessibilityAction("Move down") { onMove(index, index + 1); true } else null,
            )
        }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragEnd = {
                    val to = (at + kotlin.math.round(dy / height).toInt()).coerceIn(0, last)
                    if (to != at) move(at, to)
                    dy = 0f
                },
                onDragCancel = { dy = 0f },
            ) { change, drag -> change.consume(); dy += drag.y }
        }
}

/** Artwork at about [size] px; if that larger variant does not exist it falls back to the original URL. */
@Composable fun SharpArtwork(url: String?, description: String?, modifier: Modifier, size: Int = 226) {
    var failed by remember(url) { mutableStateOf(false) }
    coil3.compose.AsyncImage(if (failed) url else hiResArtwork(url, size), description, modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop, onError = { failed = true })
}

/** Set by NuvxApp on phones (not with reduced motion): the mini player's cover grows into the full player's cover and back. */
@OptIn(ExperimentalSharedTransitionApi::class)
class ArtMorph(val scope: SharedTransitionScope, val open: Boolean)
val LocalArtMorph = compositionLocalOf<ArtMorph?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable fun Modifier.morphArt(visibility: AnimatedVisibilityScope?): Modifier {
    val morph = LocalArtMorph.current
    if (morph == null || visibility == null) return this
    return with(morph.scope) { this@morphArt.sharedElement(rememberSharedContentState("nowPlayingArt"), visibility) }
}
