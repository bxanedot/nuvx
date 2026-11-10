package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.ui.icons.SfIcons
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bxane.nuvx.playback.audio.LosslessOutput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.bxane.nuvx.ui.components.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.R
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.permits
import com.bxane.nuvx.data.smb.SmbRepository
import com.bxane.nuvx.data.webdav.WebDavRepository
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.sources.AddonSource
import com.bxane.nuvx.data.sources.SourceConfig
import com.bxane.nuvx.data.sources.SourceHealth
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.ui.components.AddonEditorAlert
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SourcesScreen(
    contentPadding: PaddingValues,
    onEditSource: (SourceConfig) -> Unit,
    onChooseSourceQuality: (SourceConfig) -> Unit,
    onExplainOutdatedAddon: (SourceConfig) -> Unit,
    onEditWebDav: () -> Unit,
    onEditSmb: () -> Unit,
    onConfirmJioSaavn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val upgradeLengthSlackSeconds by AppSettings.upgradeLengthSlackSeconds.collectAsStateWithLifecycle()
    val webdavUrl by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val webdavUsername by AppSettings.webdavUsername.collectAsStateWithLifecycle()
    val webdavPassword by AppSettings.webdavPassword.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    val smbUsername by AppSettings.smbUsername.collectAsStateWithLifecycle()
    val smbPassword by AppSettings.smbPassword.collectAsStateWithLifecycle()

    val health = remember { mutableStateMapOf<String, SourceHealth>() }
    val losslessOutput by LosslessOutput.state.collectAsStateWithLifecycle()

    val probeKey = configs.filter { it.kind.needsServer }
        .joinToString { "${it.id}@${it.baseUrl}@${it.checkValidLossless}" } + "@${losslessOutput.capable}"
    LaunchedEffect(probeKey) {
        configs.filter { it.kind.needsServer && it.isComplete && !it.awaitsLosslessOutput }.forEach { config ->
            val source = SourceRegistry.instance(config.id) ?: return@forEach
            health[config.id] = withContext(Dispatchers.IO) {
                runCatching { source.health() }
                    .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
            }
            if (source is AddonSource) {
                val named = withContext(Dispatchers.IO) { runCatching { source.manifestName() }.getOrNull() }
                if (named != null && named != config.label) {
                    SourceRegistry.config(config.id)?.let { SourceRegistry.update(it.copy(label = named)) }
                }
            }
        }
    }

    val ceiling = if (metered == true) cellularQuality else wifiQuality

    val cappedByQuality = ceiling != AudioQuality.LOSSLESS
    val anyLosslessSource = configs.any { it.enabled && it.isComplete && it.kind.canServeLossless }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.source))

        SettingsGroup(
            header = stringResource(R.string.sources_order_header),
            footer = buildString {
                append(stringResource(R.string.sources_order_footer))
                if (cappedByQuality) {
                    append("\n\n")
                    append(stringResource(R.string.sources_quality_capped))
                } else if (!anyLosslessSource) {
                    append("\n\n")
                    append(stringResource(R.string.sources_no_lossless))
                }
            },
        ) {
            val addons = configs.filter { it.kind.isUserAdded }
            val fixed = configs.filterNot { it.kind.isUserAdded }
                .sortedBy { it.kind.rank }

            val row: @Composable (Int, SourceConfig, (@Composable () -> Unit)?) -> Unit =
                { position, config, handle ->
                    SourceRow(
                        position = position,
                        config = config,
                        health = health[config.id],
                        skippedByQuality = config.enabled && !ceiling.permits(config.kind),
                        awaitingOutput = if (config.enabled && config.awaitsLosslessOutput) losslessOutput else null,
                        needsUpdate = config.needsAddonUpdate,
                        onMetered = metered == true,
                        ceiling = ceiling,
                        onClick = if (config.kind.needsServer) ({ onEditSource(config) }) else null,
                        onChooseQuality = { onChooseSourceQuality(config) },
                        onExplainOutdated = { onExplainOutdatedAddon(config) },
                        onToggle = if (config.kind == SourceKind.YOUTUBE) {
                            null
                        } else {
                            ({ enabled ->
                                if (config.kind == SourceKind.JIOSAAVN && enabled && !config.enabled) {
                                    onConfirmJioSaavn()
                                } else {
                                    SourceRegistry.setEnabled(config.id, enabled)
                                }
                            })
                        },
                        handle = handle,
                    )
                }

            ReorderableAddons(
                addons = addons,
                onReorder = { SourceRegistry.reorderAddons(it.map(SourceConfig::id)) },
                row = row,
            )

            fixed.forEachIndexed { index, config ->
                if (index > 0) RowDivider()
                row(addons.size + index + 1, config, null)
            }

            val waiting = configs.any { it.enabled && it.awaitsLosslessOutput }
            if (waiting && losslessOutput.bluetoothCodecUnknown) {
                RowDivider()
                BluetoothCodecPermissionRow()
            }

            RowDivider()
            AddSourceRow(
                onClick = { onEditSource(SourceConfig(kind = SourceKind.ADDON)) },
            )
        }

        RemoteLibrarySection(
            icon = SfIcons.Cloud,
            title = stringResource(R.string.webdav),
            description = stringResource(R.string.webdav_description),
            subtitle = if (webdavUrl.isBlank()) {
                stringResource(R.string.webdav_not_configured)
            } else if (webdavUsername.isNotBlank()) {
                "$webdavUrl · $webdavUsername"
            } else {
                webdavUrl
            },
            listSubtitle = stringResource(R.string.webdav_subtitle),
            disconnectTitle = stringResource(R.string.webdav_disconnect),
            configured = webdavUrl.isNotBlank(),
            testFailedRes = R.string.webdav_test_failed,
            onEdit = onEditWebDav,
            onTest = {
                WebDavRepository.testConnection(webdavUrl, webdavUsername, webdavPassword)
            },
            onDisconnect = { AppSettings.clearWebDav() },
        )

        RemoteLibrarySection(
            icon = SfIcons.Storage,
            title = stringResource(R.string.smb),
            description = stringResource(R.string.smb_description),
            subtitle = if (smbHost.isBlank() || smbShare.isBlank()) {
                stringResource(R.string.smb_not_configured)
            } else if (smbUsername.isNotBlank()) {
                "$smbHost · $smbShare · $smbUsername"
            } else {
                "$smbHost · $smbShare"
            },
            listSubtitle = stringResource(R.string.smb_subtitle),
            disconnectTitle = stringResource(R.string.smb_disconnect),
            configured = smbHost.isNotBlank() && smbShare.isNotBlank(),
            testFailedRes = R.string.smb_test_failed,
            onEdit = onEditSmb,
            onTest = {
                SmbRepository.testConnection(
                    smbHost,
                    smbShare,
                    AppSettings.smbBasePath.value,
                    smbUsername,
                    smbPassword,
                )
            },
            onDisconnect = { AppSettings.clearSmb() },
        )

        SettingsGroup(
            header = stringResource(R.string.source_matching),
            footer = stringResource(R.string.upgrade_length_slack_footer),
        ) {
            SliderRow(
                icon = SfIcons.Tune,
                title = stringResource(R.string.upgrade_length_slack),
                subtitle = stringResource(R.string.upgrade_length_slack_subtitle),
                value = stringResource(R.string.seconds_short, upgradeLengthSlackSeconds),
                sliderValue = upgradeLengthSlackSeconds.toFloat(),
                onSliderValue = {
                    AppSettings.setUpgradeLengthSlackSeconds(it.roundToInt())
                },
                valueRange = AppSettings.MIN_UPGRADE_LENGTH_SLACK_SECONDS.toFloat()..
                    AppSettings.MAX_UPGRADE_LENGTH_SLACK_SECONDS.toFloat(),
                steps = AppSettings.MAX_UPGRADE_LENGTH_SLACK_SECONDS -
                    AppSettings.MIN_UPGRADE_LENGTH_SLACK_SECONDS - 1,
            )
        }

        Spacer(Modifier.height(32.dp))
    }

}


@Composable
private fun RemoteLibrarySection(
    icon: ImageVector,
    title: String,
    description: String,
    subtitle: String,
    listSubtitle: String,
    disconnectTitle: String,
    configured: Boolean,
    testFailedRes: Int,
    onEdit: () -> Unit,
    onTest: suspend () -> Result<Unit>,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    SettingsGroup(header = title, footer = description) {
        SettingsRow(icon = icon, title = title, subtitle = subtitle, onClick = onEdit)
        if (configured) {
            RowDivider()
            SettingsRow(
                icon = SfIcons.Dns,
                title = if (testing) {
                    stringResource(R.string.testing)
                } else {
                    testResult ?: stringResource(R.string.test)
                },
                subtitle = listSubtitle,
                onClick = {
                    if (testing) return@SettingsRow
                    testing = true
                    testResult = null
                    scope.launch {
                        onTest().fold(
                            onSuccess = {
                                testResult = context.getString(R.string.connected)
                            },
                            onFailure = {
                                testResult = context.getString(
                                    testFailedRes,
                                    it.message ?: context.getString(R.string.failed),
                                )
                            },
                        )
                        testing = false
                    }
                },
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.DeleteSweep,
                title = disconnectTitle,
                onClick = {
                    onDisconnect()
                    testResult = null
                },
            )
        }
    }
}
@Composable
private fun ReorderableAddons(
    addons: List<SourceConfig>,
    onReorder: (List<SourceConfig>) -> Unit,
    row: @Composable (Int, SourceConfig, (@Composable () -> Unit)?) -> Unit,
) {
    if (addons.isEmpty()) return
    if (addons.size == 1) {
        row(1, addons.first(), null)
        RowDivider()
        return
    }

    var liveOrder by remember(addons) { mutableStateOf(addons) }
    var dragged by remember { mutableStateOf<String?>(null) }

    var totalDrag by remember { mutableFloatStateOf(0f) }

    var startIndex by remember { mutableIntStateOf(0) }

    val heights = remember { mutableStateMapOf<String, Float>() }
    var lockedHeights by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }

    var slotShift by remember { mutableFloatStateOf(0f) }

    liveOrder.forEachIndexed { index, config ->
        key(config.id) {
            val isDragging = config.id == dragged
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .onSizeChanged { heights[config.id] = it.height.toFloat() }
                    .graphicsLayer {
                        translationY = if (isDragging) totalDrag - slotShift else 0f
                    },
            ) {
                row(index + 1, config) {
                    Icon(
                        imageVector = SfIcons.DragHandle,
                        contentDescription = stringResource(R.string.drag_to_reorder),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier
                            .size(20.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragged = config.id
                                        totalDrag = 0f
                                        slotShift = 0f
                                        startIndex = liveOrder.indexOfFirst { it.id == config.id }
                                        lockedHeights = heights.toMap()
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        val height = { id: String -> lockedHeights[id] ?: 0f }
                                        var at = liveOrder.indexOfFirst { it.id == config.id }
                                        if (at < 0 || lockedHeights.isEmpty()) return@detectDragGestures

                                        val others = liveOrder.filterNot { it.id == config.id }
                                        totalDrag = (totalDrag + delta.y).coerceIn(
                                            -others.take(startIndex).sumOf { height(it.id).toDouble() }.toFloat(),
                                            others.drop(startIndex).sumOf { height(it.id).toDouble() }.toFloat(),
                                        )

                                        while (true) {
                                            val offset = totalDrag - slotShift
                                            val below = liveOrder.getOrNull(at + 1)?.let { height(it.id) }
                                            val above = liveOrder.getOrNull(at - 1)?.let { height(it.id) }
                                            if (below != null && below > 0f && offset > below * SWAP_THRESHOLD) {
                                                liveOrder = liveOrder.toMutableList()
                                                    .apply { add(at + 1, removeAt(at)) }
                                                slotShift += below
                                                at++
                                            } else if (above != null && above > 0f && offset < -above * SWAP_THRESHOLD) {
                                                liveOrder = liveOrder.toMutableList()
                                                    .apply { add(at - 1, removeAt(at)) }
                                                slotShift -= above
                                                at--
                                            } else {
                                                break
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        dragged = null
                                        totalDrag = 0f
                                        slotShift = 0f
                                        onReorder(liveOrder)
                                    },
                                    onDragCancel = {
                                        dragged = null
                                        totalDrag = 0f
                                        slotShift = 0f
                                        liveOrder = addons
                                    },
                                )
                            },
                    )
                }
                RowDivider()
            }
        }
    }
}

private const val SWAP_THRESHOLD = 0.6f

@Composable
private fun AddSourceRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(24.dp))
        Icon(
            imageVector = SfIcons.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ICON_SIZE),
        )
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.add_addon),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.add_addon_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun BluetoothCodecPermissionRow() {
    val context = LocalContext.current
    val permission = "android.permission.BLUETOOTH_CONNECT"
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    fun isGranted() =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(isGranted()) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        if (ok) LosslessOutput.refresh()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = isGranted()
        if (now && !granted) LosslessOutput.refresh()
        granted = now
        if (now) denied = false
    }
    LaunchedEffect(Unit) {
        if (!isGranted()) ask.launch(permission)
    }
    if (granted) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (denied) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                } else {
                    ask.launch(permission)
                }
            }
            .heightIn(min = 60.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(24.dp))
        Icon(
            imageVector = SfIcons.Bluetooth,
            contentDescription = null,
            tint = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ICON_SIZE),
        )
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (denied) R.string.lossless_output_permission_denied else R.string.lossless_output_permission,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    if (denied) R.string.lossless_output_permission_denied_detail else R.string.lossless_output_permission_detail,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceRow(
    position: Int,
    config: SourceConfig,
    health: SourceHealth?,
    onClick: (() -> Unit)?,
    onChooseQuality: () -> Unit,
    onExplainOutdated: () -> Unit,
    onToggle: ((Boolean) -> Unit)?,
    skippedByQuality: Boolean = false,
    awaitingOutput: LosslessOutput.State? = null,
    needsUpdate: Boolean = false,
    onMetered: Boolean = false,
    ceiling: AudioQuality = AudioQuality.LOSSLESS,
    handle: (@Composable () -> Unit)? = null,
) {
    val dimmed = !config.enabled || skippedByQuality || awaitingOutput != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .heightIn(min = 60.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$position",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(18.dp)
                .alpha(if (dimmed) 0.4f else 1f),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = when (config.kind) {
                SourceKind.ADDON -> SfIcons.Extension
                SourceKind.CUSTOM_MODULE -> SfIcons.Extension
                SourceKind.MODULE -> SfIcons.Extension
                SourceKind.JIOSAAVN -> SfIcons.GraphicEq
                SourceKind.YOUTUBE -> SfIcons.PlayCircle
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(ICON_SIZE)
                .alpha(if (dimmed) 0.4f else 1f),
        )
        Spacer(Modifier.width(ICON_GAP))
        Column(
            Modifier
                .weight(1f)
                .alpha(if (dimmed) 0.4f else 1f),
        ) {
            Text(
                text = config.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val quality = config.activeQuality
            if (config.kind == SourceKind.ADDON && config.isComplete && quality != null) {
                Row(
                    modifier = Modifier
                        .clickable(
                            onClickLabel = stringResource(R.string.audio_quality),
                            onClick = onChooseQuality,
                        )
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = SfIcons.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = quality.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = SfIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
            Text(
                text = when {
                    awaitingOutput != null -> when {
                        awaitingOutput.bluetoothCodecUnknown ->
                            stringResource(R.string.source_needs_lossless_bt_unknown)
                        awaitingOutput.bluetoothCodec != null ->
                            stringResource(R.string.source_needs_lossless_bt_codec, awaitingOutput.bluetoothCodec)
                        else -> stringResource(R.string.source_needs_lossless_output)
                    }
                    skippedByQuality -> stringResource(
                        R.string.source_skipped_by_quality,
                        stringResource(if (onMetered) R.string.mobile_data else R.string.wifi),
                        ceiling.localizedLabel(),
                    )
                    else -> config.statusLine(health)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    awaitingOutput != null -> MaterialTheme.colorScheme.error
                    skippedByQuality -> MaterialTheme.colorScheme.onSurfaceVariant
                    health is SourceHealth.Rejected -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (config.kind == SourceKind.ADDON && config.isComplete) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (config.allowDownloads) {
                            SfIcons.Download
                        } else {
                            SfIcons.FileDownloadOff
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            if (config.allowDownloads) R.string.source_downloads_on else R.string.source_downloads_off,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (needsUpdate) {
                Row(
                    modifier = Modifier
                        .clickable(
                            onClickLabel = stringResource(R.string.source_outdated),
                            onClick = onExplainOutdated,
                        )
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = SfIcons.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.source_outdated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (onToggle == null) {
            Text(
                text = stringResource(R.string.always_on),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            IosSwitch(
                checked = config.enabled,
                onCheckedChange = onToggle,
            )
        }
        if (handle != null) {
            Spacer(Modifier.width(4.dp))
            handle()
        }
    }
}

@Composable
private fun AudioQuality.localizedLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.low
        AudioQuality.MEDIUM -> R.string.medium
        AudioQuality.HIGH -> R.string.high
        AudioQuality.LOSSLESS -> R.string.lossless
    },
)

@Composable
private fun SourceConfig.statusLine(health: SourceHealth?): String = when {
    !isComplete -> stringResource(R.string.source_setup_required)
    kind == SourceKind.JIOSAAVN -> stringResource(R.string.jiosaavn_mismatch_warning)
    health is SourceHealth.Ok -> listOfNotNull(
        health.detail,
        kind.labels.take(3).joinToString(" · "),
    ).joinToString(" · ")
    health is SourceHealth.Rejected -> health.reason
    health is SourceHealth.Unreachable -> stringResource(R.string.source_unreachable, health.reason)
    kind.needsServer -> stringResource(R.string.checking)
    else -> kind.labels.take(3).joinToString(" · ")
}

@Composable
internal fun SourceEditorAlert(
    hazeState: HazeState,
    config: SourceConfig,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDelete: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val isNew = SourceRegistry.config(config.id) == null
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsGood by remember { mutableStateOf(false) }

    val connected = stringResource(R.string.connected)
    val unreadable = stringResource(R.string.source_unrecognised)
    val alreadyAdded = stringResource(R.string.source_already_added)

    fun run(thenSave: Boolean) {
        busy = true
        status = null
        scope.launch {
            val identified = withContext(Dispatchers.IO) {
                runCatching { SourceRegistry.identify(baseUrl.trim(), config.takeUnless { isNew }) }
                    .getOrElse { Result.failure(it) }
            }
            val found = identified.getOrNull()
            if (found == null) {
                statusIsGood = false
                status = identified.exceptionOrNull()?.message ?: unreadable
                busy = false
                return@launch
            }

            SourceRegistry.duplicateOf(found.baseUrl, exceptId = config.id.takeUnless { isNew })
                ?.let { existing ->
                    statusIsGood = false
                    status = String.format(alreadyAdded, existing.displayName)
                    busy = false
                    return@launch
                }

            val health = withContext(Dispatchers.IO) {
                runCatching { SourceRegistry.probeCandidate(found) }
                    .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
            }
            statusIsGood = health.isOk
            status = when (health) {
                is SourceHealth.Ok -> listOfNotNull(
                    connected,
                    found.kind.label,
                    health.detail,
                ).joinToString(" · ")
                is SourceHealth.Rejected -> health.reason
                is SourceHealth.Unreachable -> health.reason
            }
            busy = false

            if (thenSave) {
                if (isNew) SourceRegistry.add(found) else SourceRegistry.update(found)
                onSaved()
            }
        }
    }

    AddonEditorAlert(
        hazeState = hazeState,
        title = if (isNew) {
            stringResource(R.string.add_addon)
        } else {
            config.displayName
        },
        description = stringResource(R.string.addon_url_description),
        urlValue = baseUrl,
        onUrlChange = { baseUrl = it; status = null },
        urlPlaceholder = "https://my-addon.example.com",
        status = status,
        statusIsGood = statusIsGood,
        testing = busy,
        canSubmit = baseUrl.isNotBlank(),
        onTest = { run(thenSave = false) },
        onSave = { run(thenSave = true) },
        onRemove = if (isNew) null else onDelete,
        onDismiss = onDismiss,
    )
}
