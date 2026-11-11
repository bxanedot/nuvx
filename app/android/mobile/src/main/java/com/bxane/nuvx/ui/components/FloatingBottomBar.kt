package com.bxane.nuvx.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.abs
import kotlin.math.roundToInt
import com.composables.sfsymbols.SfSymbol
import com.composables.sfsymbols.SfWeight
import com.composables.sfsymbols.SfSymbolIcon
import com.composables.sfsymbols.SfSymbolEffect
import com.composables.sfsymbols.SfEffectScope

data class BottomTab(
    val label: String,
    val icon: ImageVector,
    val symbol: SfSymbol? = null,
)

private val TabBounce = SfSymbolEffect.Bounce(scope = SfEffectScope.ByLayer)

internal val tabReselects = androidx.compose.runtime.mutableStateMapOf<String, Int>()

internal fun reselect(tab: BottomTab) {
    tabReselects[tab.label] = (tabReselects[tab.label] ?: 0) + 1
}

@Composable
internal fun TabIcon(tab: BottomTab, selected: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val symbol = tab.symbol
    if (symbol == null) {
        Icon(imageVector = tab.icon, contentDescription = tab.label, tint = tint, modifier = modifier.size(TAB_ICON_SIZE))
        return
    }
    var taps by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !seen) taps++
        seen = selected
    }
    val again = tabReselects[tab.label] ?: 0
    Box(modifier.size(TAB_ICON_SIZE), contentAlignment = Alignment.Center) {
        SfSymbolIcon(
            symbol = symbol,
            size = TAB_SYMBOL_SIZE,
            weight = SfWeight.Semibold,
            tint = tint,
            effect = TabBounce,
            trigger = taps to again,
            contentDescription = tab.label,
        )
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun Modifier.barSurface(shape: CornerBasedShape, hazeState: HazeState?): Modifier {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val container = MaterialTheme.colorScheme.surface
    val surface = when {
        LocalLiquidGlassEnabled.current && isGlassSupported() -> return liquidGlass(shape = shape)
        reduceDynamicBlur || hazeState == null -> Modifier.background(container)
        else -> Modifier.optimizedHazeEffect(state = hazeState, style = HazeMaterials.regular(container))
    }
    return clip(shape).then(surface).border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

internal val TAB_SYMBOL_SIZE = 22.dp

internal val PILL_INSET = 4.dp

internal val NAV_BAR_GUTTER = 19.dp

internal val NAV_BAR_LIFT = 6.dp

internal val floatingBarInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets(bottom = GESTURE_BAR_FLOOR))

private val GESTURE_BAR_FLOOR = 15.dp

internal val TAB_VERTICAL_PADDING = 6.5.dp

internal val TAB_ICON_LABEL_GAP = 1.dp

internal val TAB_ICON_SIZE = 28.dp

@Composable
internal fun tabLabelStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.5.sp)

internal val GlassSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

internal const val STRETCH = 0.16f

internal const val SQUASH = 0.5f

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun FloatingBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    searchDock: (@Composable () -> Unit)? = null,
) {
    if (searchDock != null) {
        Box(modifier.imePadding().windowInsetsPadding(floatingBarInsets).padding(horizontal = NAV_BAR_GUTTER).padding(bottom = NAV_BAR_LIFT)) {
            searchDock()
        }
        return
    }
    val pillShape = RoundedCornerShape(percent = 50)
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val glassSpec: AnimationSpec<Float> = if (reduceAnimation) snap() else GlassSpring

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)

    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 6.dp.toPx() }
    val n = tabs.size

    val tabWidthPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width - gapPx * (n - 1)) / n
    } else 0f
    val tabStepPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width + gapPx) / n
    } else 0f

    val currentTabStepPx by rememberUpdatedState(tabStepPx)
    val currentLastIndex by rememberUpdatedState(tabs.lastIndex)
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)

    val pillTargetPx = if (tabStepPx > 0f) {
        selectedIndex * tabStepPx + dragOffset
    } else 0f

    val animatedPillOffset by animateFloatAsState(
        targetValue = pillTargetPx,
        animationSpec = glassSpec,
        label = "pillOffset",
    )

    val lag = if (tabStepPx > 0f) {
        (abs(pillTargetPx - animatedPillOffset) / tabStepPx).coerceIn(0f, 1f)
    } else {
        0f
    }

    var lastHapticTab by remember { mutableIntStateOf(selectedIndex) }

    LaunchedEffect(selectedIndex) {
        dragOffset = 0f
        lastHapticTab = selectedIndex
    }

    Box(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = NAV_BAR_GUTTER)
            .padding(bottom = NAV_BAR_LIFT)
            .fillMaxWidth()
            .barSurface(pillShape, hazeState)
            .padding(horizontal = PILL_INSET, vertical = PILL_INSET),
    ) {
        if (tabWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .width(with(density) { tabWidthPx.toDp() })
                    .height(with(density) { rowSize.height.toDp() })
                    .graphicsLayer {
                        translationX = animatedPillOffset
                        scaleX = 1f + lag * STRETCH
                        scaleY = 1f - lag * STRETCH * SQUASH
                    }
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowSize = it }
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    var dragStartIndex = currentSelectedIndex
                    detectHorizontalDragGestures(
                        onDragStart = {
                            totalDrag = 0f
                            dragStartIndex = currentSelectedIndex
                        },
                        onDragCancel = { dragOffset = 0f },
                        onDragEnd = {
                            val step = currentTabStepPx
                            if (step > 0f) {
                                val absoluteIndexFloat = (dragStartIndex + totalDrag / step)
                                    .coerceIn(0f, currentLastIndex.toFloat())
                                val newIndex = absoluteIndexFloat.roundToInt()
                                if (newIndex != currentSelectedIndex) {
                                    currentOnTabSelected(newIndex)
                                }
                            }
                            dragOffset = 0f
                        },
                        onHorizontalDrag = { _, delta ->
                            val step = currentTabStepPx
                            if (step <= 0f) return@detectHorizontalDragGestures
                            totalDrag += delta
                            val absoluteIndexFloat = (dragStartIndex + totalDrag / step)
                                .coerceIn(0f, currentLastIndex.toFloat())
                            dragOffset = absoluteIndexFloat * step - currentSelectedIndex * step

                            val approxTab = absoluteIndexFloat.roundToInt()
                            if (approxTab != lastHapticTab) {
                                haptics.play(Haptic.Tick)
                                lastHapticTab = approxTab
                            }
                        },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val glassTint = glassContentColor()
            val adaptiveTint = if (useGlass) glassTint else null
            tabs.forEachIndexed { index, tab ->
                BottomBarItem(
                    tab = tab,
                    selected = index == selectedIndex,
                    glassSpec = glassSpec,
                    selectedTint = adaptiveTint,
                    unselectedTint = adaptiveTint?.copy(alpha = 0.65f),
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    tab: BottomTab,
    selected: Boolean,
    glassSpec: AnimationSpec<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTint: Color? = null,
    unselectedTint: Color? = null,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = glassSpec,
        label = "tabScale",
    )
    val haptics = rememberHaptics()
    val tint by animateColorAsState(
        targetValue = if (selected) {
            selectedTint ?: MaterialTheme.colorScheme.primary
        } else {
            unselectedTint ?: MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "tabTint",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!selected) haptics.play(Haptic.Select) else reselect(tab)
                onClick()
            }
            .padding(vertical = TAB_VERTICAL_PADDING),
    ) {
        TabIcon(
            tab = tab,
            selected = selected,
            tint = tint,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
        Spacer(Modifier.height(TAB_ICON_LABEL_GAP))
        Text(
            text = tab.label,
            style = tabLabelStyle(),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
