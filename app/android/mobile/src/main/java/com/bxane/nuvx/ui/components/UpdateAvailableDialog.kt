package com.bxane.nuvx.ui.components

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.bxane.nuvx.R
import com.bxane.nuvx.data.AppUpdateChecker
import com.bxane.nuvx.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

internal val ALERT_WIDTH = 270.dp
internal val ALERT_CORNER = 14.dp
internal val ACTION_HEIGHT = 44.dp

internal val SCRIM_COLOR = Color.Black.copy(alpha = 0.28f)

private val DOWNLOAD_ROW_HEIGHT = 4.dp

private val NOTES_MAX_HEIGHT = 220.dp

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun UpdateAvailableDialog(
    version: String,
    notes: String?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleasePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val state by AppUpdateChecker.download.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(ALERT_CORNER)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.software_update),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = when (state) {
                        is AppUpdateChecker.DownloadState.Downloading ->
                            stringResource(R.string.update_downloading_body, version)
                        is AppUpdateChecker.DownloadState.Ready ->
                            stringResource(R.string.update_ready_body, version)
                        is AppUpdateChecker.DownloadState.Failed ->
                            stringResource(R.string.update_failed_body, version)
                        else ->
                            stringResource(R.string.update_available_body, version)
                    },
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                val downloading = state as? AppUpdateChecker.DownloadState.Downloading
                if (downloading != null || state is AppUpdateChecker.DownloadState.Failed) {
                    Box(
                        Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .height(DOWNLOAD_ROW_HEIGHT)
                            .clip(RoundedCornerShape(DOWNLOAD_ROW_HEIGHT / 2))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                    ) {
                        if (downloading != null && downloading.fraction > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(downloading.fraction)
                                    .height(DOWNLOAD_ROW_HEIGHT)
                                    .clip(RoundedCornerShape(DOWNLOAD_ROW_HEIGHT / 2))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
                if (state is AppUpdateChecker.DownloadState.Failed) {
                    Text(
                        text = (state as AppUpdateChecker.DownloadState.Failed).message,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                if (!notes.isNullOrBlank()) {
                    AlertRule(modifier = Modifier.padding(top = 12.dp))
                    Text(
                        text = stringResource(R.string.whats_new),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W600,
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Start,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .heightIn(max = NOTES_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            RichText(style = RichTextStyle.Default) {
                                Markdown(githubMarkdown(notes))
                            }
                        }
                    }
                }
            }

            AlertRule()
            when (state) {
                is AppUpdateChecker.DownloadState.Downloading -> {
                    AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onCancelDownload)
                }
                is AppUpdateChecker.DownloadState.Ready -> {
                    AlertAction(label = stringResource(R.string.install_now), emphasised = true, onClick = onInstall)
                    AlertRule()
                    AlertAction(label = stringResource(R.string.later), emphasised = false, onClick = onDismiss)
                }
                is AppUpdateChecker.DownloadState.Failed -> {
                    AlertAction(label = stringResource(R.string.try_again), emphasised = true, onClick = onDownload)
                    AlertRule()
                    AlertAction(label = stringResource(R.string.open_releases_page), emphasised = false, onClick = onOpenReleasePage)
                }
                else -> {
                    AlertAction(label = stringResource(R.string.download_now), emphasised = true, onClick = onDownload)
                    AlertRule()
                    AlertAction(label = stringResource(R.string.remind_me_later), emphasised = false, onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
internal fun AlertAction(
    label: String,
    emphasised: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                fontWeight = if (emphasised) FontWeight.W600 else FontWeight.W400,
            ),
            color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                .copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

@Composable
internal fun AlertRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
    )
}

internal fun githubMarkdown(notes: String): String {
    val img = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    fun attr(tag: String, name: String) =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1).orEmpty()
    val html = notes
        .replace(img) { " ![${attr(it.value, "alt")}](${attr(it.value, "src")}) " }
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</?(p|div|center|picture)\b[^>]*>""", RegexOption.IGNORE_CASE), "")
    var fenced = false
    return html.lines().joinToString("\n") { line ->
        if (line.trimStart().startsWith("```")) fenced = !fenced
        if (fenced || line.isBlank()) line else line.trimEnd() + "  "
    }
}
