package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.ui.components.IosSwitch
import com.bxane.nuvx.ui.components.PillSegmentedControl
import com.bxane.nuvx.ui.components.RevealIcon
import com.bxane.nuvx.ui.components.bounceOnReveal
import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

internal val GroupShape = RoundedCornerShape(14.dp)
internal val GROUP_INSET = 16.dp
internal val ROW_INSET = 16.dp
internal val ICON_SIZE = 22.dp
internal val ICON_GAP = 14.dp

internal val TEXT_INSET = ROW_INSET + ICON_SIZE + ICON_GAP

@Composable
internal fun SettingsPageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
    )
}

@Composable
internal fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    topSpacing: Dp = 26.dp,
    content: @Composable () -> Unit,
) {
    if (header != null) {
        Text(
            text = header.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = 26.dp,
                bottom = 8.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(topSpacing))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET + 4.dp,
                top = 8.dp,
            ),
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = TEXT_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    iconPainter: Painter? = null,
    iconContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconContent != null) {
            iconContent()
        } else if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE).bounceOnReveal(),
            )
        } else if (icon != null) {
            RevealIcon(icon, MaterialTheme.colorScheme.onBackground, ICON_SIZE)
        }
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitleContent != null) {
                subtitleContent()
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            Chevron()
        }
    }
}

@Composable
internal fun SettingsSubRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(start = ROW_INSET, end = ROW_INSET, top = 0.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        IosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
internal fun Badge(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Chevron() {
    Icon(
        SfIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(20.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
) {
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outline,
    )
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RevealIcon(icon, MaterialTheme.colorScheme.onBackground, ICON_SIZE)
            Spacer(Modifier.width(ICON_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onSliderValue,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = colors,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
            modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP),
        )
    }
}

@Composable
internal fun DestructiveRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PillSegmentedControl(options, selectedIndex, onSelect, modifier, enabled)
}
