package com.bxane.nuvx.ui.components

import com.bxane.nuvx.ui.icons.BrokenHeart
import com.bxane.nuvx.ui.icons.SfIcons
import com.bxane.nuvx.ui.components.trackArtSize
import androidx.compose.ui.layout.ContentScale
import com.bxane.nuvx.R

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bxane.nuvx.ui.player.ThinSlider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.ROW_ART_PX
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.data.webdav.WebDavUploads
import com.bxane.nuvx.download.DownloadState
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.playback.SleepTimer
import com.bxane.nuvx.ui.components.thumbnailBorder
import com.bxane.nuvx.ui.theme.ArtworkPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SongActionsMenu(
    song: Song,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: () -> Unit,
    onDownload: () -> Unit,
    onUploadToWebDav: (() -> Unit)? = null,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onRemoveFromCache: (() -> Unit)? = null,
    onRemoveFromHistory: (() -> Unit)? = null,
    showSleepTimer: Boolean = false,
    onRollbackToOriginal: (() -> Unit)? = null,
    onUpgradeQuality: (() -> Unit)? = null,
    upgradeQualityInProgress: Boolean = false,
    onToggleAudioVersion: (() -> Unit)? = null,
    isAudioVersion: Boolean = false,
    audioVersionMissing: Boolean = false,
    onShare: (() -> Unit)? = null,
    onCopyLog: (() -> Unit)? = null,
    onLyricsOffset: (() -> Unit)? = null,
    onAutomixOverview: (() -> Unit)? = null,
    resolvingLinks: Boolean = false,
) {
    var showingSongInfo by remember(song.videoId) { mutableStateOf(false) }
    var pickingSleepTimer by remember { mutableStateOf(false) }
    if (showingSongInfo) SongInfoDialog(song) { showingSongInfo = false }
    val palette = neutralMenuPalette()
    val liked = likeStatus == LikeStatus.LIKE
    val disliked = likeStatus == LikeStatus.DISLIKE
    val isOffline = song.localUri != null

    val rows: @Composable ColumnScope.() -> Unit = {
        var drawnAny = false
        fun startsGroup(): Boolean = drawnAny.also { drawnAny = true }
        val canRate = isOffline || signedIn
        val shareAction = onShare?.takeIf { !isOffline }

        if (canRate || shareAction != null) {
            if (startsGroup()) IosMenuSeparator()
            IosMenuQuickActions {
                if (canRate) {
                    IosMenuQuickAction(
                        icon = if (liked) SfIcons.StarFill else SfIcons.Star,
                        label = stringResource(R.string.like),
                        tint = if (liked) palette.accent else null,
                        onClick = onToggleLike,
                    )
                }
                if (signedIn && !isOffline) {
                    IosMenuQuickAction(
                        icon = if (disliked) BrokenHeart.Fill else BrokenHeart.Outline,
                        label = stringResource(R.string.dislike),
                        tint = if (disliked) palette.accent else null,
                        onClick = onToggleDislike,
                    )
                }
                shareAction?.let {
                    IosMenuQuickAction(icon = SfIcons.Share, label = stringResource(R.string.share), onClick = it)
                }
            }
        }

        if (onRollbackToOriginal != null || onUpgradeQuality != null || onToggleAudioVersion != null) {
            if (startsGroup()) IosMenuSeparator()
            (onRollbackToOriginal ?: onUpgradeQuality)?.let {
                ActionRow(
                    icon = if (onRollbackToOriginal != null) SfIcons.Revert else SfIcons.Upgrade,
                    label = stringResource(
                        if (onRollbackToOriginal != null) {
                            R.string.revert_to_original
                        } else {
                            R.string.upgrade_quality
                        },
                    ),
                    accent = palette.accent,
                    enabled = onRollbackToOriginal != null || !upgradeQualityInProgress,
                    onClick = it,
                )
            }
            onToggleAudioVersion?.let {
                ActionRow(
                    icon = if (isAudioVersion) SfIcons.Video else SfIcons.MusicNote,
                    label = stringResource(
                        when {
                            audioVersionMissing && isAudioVersion -> R.string.no_video_version
                            audioVersionMissing -> R.string.no_audio_version
                            isAudioVersion -> R.string.convert_to_video
                            else -> R.string.convert_to_audio
                        },
                    ),
                    accent = palette.accent,
                    enabled = !audioVersionMissing,
                    onClick = it,
                )
            }
        }

        if (canRate) {
            if (startsGroup()) IosMenuSeparator()
            ActionRow(
                icon = SfIcons.AddToPlaylist,
                label = stringResource(R.string.add_to_playlist),
                accent = palette.accent,
                onClick = onAddToPlaylist,
            )
            onRemoveFromPlaylist?.let {
                ActionRow(
                    icon = SfIcons.RemoveFromPlaylist,
                    label = stringResource(R.string.remove_from_playlist),
                    accent = palette.accent,
                    onClick = it,
                )
            }
        }

        if (startsGroup()) IosMenuSeparator()
        ActionRow(
            icon = SfIcons.Shuffle,
            label = stringResource(R.string.start_radio),
            accent = palette.accent,
            onClick = onStartRadio,
        )
        ActionRow(
            icon = SfIcons.PlayNext,
            label = stringResource(R.string.play_next),
            accent = palette.accent,
            onClick = onPlayNext,
        )
        ActionRow(
            icon = SfIcons.PlayLast,
            label = stringResource(R.string.add_to_queue),
            accent = palette.accent,
            onClick = onAddToQueue,
        )
        DownloadRow(song, palette, isOffline, onDownload)
        onRemoveFromCache?.let {
            ActionRow(
                icon = SfIcons.Trash,
                label = stringResource(R.string.remove_from_cache),
                accent = palette.accent,
                onClick = it,
            )
        }
        WebDavUploadRow(song, palette, isOffline, onUploadToWebDav)
        onRemoveFromHistory?.let {
            ActionRow(
                icon = SfIcons.History,
                label = stringResource(R.string.remove_from_history),
                accent = palette.accent,
                onClick = it,
            )
        }

        if (startsGroup()) IosMenuSeparator()
        when (val id = song.albumId) {
            null -> if (resolvingLinks) {
                LoadingActionRow(SfIcons.Album, stringResource(R.string.open_album), palette)
            }
            else -> ActionRow(
                SfIcons.Album,
                stringResource(R.string.open_album),
                accent = palette.accent,
                description = song.albumName,
            ) { onOpenAlbum(id) }
        }
        when (val id = song.artistId) {
            null -> if (resolvingLinks) {
                LoadingActionRow(SfIcons.Artist, stringResource(R.string.open_artist), palette)
            }
            else -> ActionRow(
                SfIcons.Artist,
                stringResource(R.string.open_artist),
                accent = palette.accent,
                description = song.artist.takeIf { it.isNotBlank() },
            ) { onOpenArtist(id) }
        }
        ActionRow(SfIcons.Info, stringResource(R.string.song_info), accent = palette.accent) {
            showingSongInfo = true
        }

        if (showSleepTimer || onLyricsOffset != null || onAutomixOverview != null || onCopyLog != null) {
            if (startsGroup()) IosMenuSeparator()
            if (showSleepTimer) {
                ActionRow(
                    icon = SfIcons.SleepTimer,
                    label = stringResource(R.string.sleep_timer),
                    value = sleepTimerStatus(),
                    accent = palette.accent,
                ) { pickingSleepTimer = true }
            }
            onAutomixOverview?.let {
                ActionRow(
                    icon = SfIcons.GraphicEq,
                    label = stringResource(R.string.automix_overview),
                    accent = palette.accent,
                    onClick = it,
                )
            }
            onLyricsOffset?.let {
                ActionRow(
                    icon = SfIcons.Sliders,
                    label = stringResource(R.string.lyrics_offset),
                    accent = palette.accent,
                    onClick = it,
                )
            }
            onCopyLog?.let {
                ActionRow(SfIcons.Bug, stringResource(R.string.copy_log), accent = palette.accent, onClick = it)
            }
        }
    }

    CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (pickingSleepTimer) {
                SleepTimerPicker(palette = palette, onBack = { pickingSleepTimer = false })
            } else {
                rows()
            }
        }
    }
}

internal enum class ActionRowStyle { Sheet, Menu }

internal val LocalActionRowStyle = staticCompositionLocalOf { ActionRowStyle.Sheet }

@Composable
private fun neutralMenuPalette(): ArtworkPalette {
    val accent = MaterialTheme.colorScheme.primary
    val colors = iosMenuColors()
    return remember(accent, colors) {
        ArtworkPalette(
            background = colors.fill,
            wash = colors.fill,
            elevated = colors.fill,
            accent = accent,
            onBackground = colors.label,
            onBackgroundVariant = colors.secondaryLabel,
            divider = colors.separator,
        )
    }
}

@Composable
private fun DownloadRow(song: Song, palette: ArtworkPalette, isOffline: Boolean, onDownload: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()

    val recorded = saved[song.videoId]
    val file by produceState(recorded?.let(Uri::parse), song.videoId, recorded) {
        value = Downloads.savedUri(context, song.videoId)
    }

    DisposableEffect(song.videoId) {
        onDispose { Downloads.dismissFailure(song.videoId) }
    }

    when (val state = active[song.videoId]) {
        is DownloadState.Queued -> ActionRow(
            icon = SfIcons.Download,
            label = stringResource(R.string.queued),
            value = stringResource(R.string.cancel),
            accent = palette.accent,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Running -> ActionRow(
            icon = SfIcons.Download,
            label = stringResource(R.string.download_notification_title),
            value = if (state.fraction > 0f) "${(state.fraction * 100).toInt()}%" else null,
            tint = palette.accent,
            accent = palette.accent,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Failed -> ActionRow(
            icon = SfIcons.ErrorOutline,
            label = state.reason,
            value = stringResource(R.string.retry),
            tint = MaterialTheme.colorScheme.error,
            accent = MaterialTheme.colorScheme.error,
            onClick = onDownload,
        )

        null -> if (file != null) {
            ActionRow(
                icon = SfIcons.Downloaded,
                label = stringResource(R.string.saved_to_downloads),
                value = stringResource(R.string.delete),
                tint = palette.accent,
                accent = palette.accent,
            ) { scope.launch { Downloads.delete(context, song.videoId) } }
        } else if (!isOffline) {
            ActionRow(
                icon = SfIcons.Download,
                label = stringResource(R.string.download),
                accent = palette.accent,
                onClick = onDownload,
            )
        }
    }
}

@Composable
private fun WebDavUploadRow(
    song: Song,
    palette: ArtworkPalette,
    isOffline: Boolean,
    onUpload: (() -> Unit)?,
) {
    onUpload ?: return
    if (!isOffline || !WebDavUploads.isUploadable(song)) return
    val active by WebDavUploads.active.collectAsStateWithLifecycle()
    when (val state = active[song.videoId]) {
        is WebDavUploads.TrackState.Running -> ActionRow(
            icon = SfIcons.Upload,
            label = stringResource(R.string.upload_to_webdav),
            value = "${(state.fraction * 100).toInt()}%",
            tint = palette.accent,
            accent = palette.accent,
            onClick = {},
        )
        is WebDavUploads.TrackState.Failed -> ActionRow(
            icon = SfIcons.Upload,
            label = state.reason,
            value = stringResource(R.string.try_again),
            tint = MaterialTheme.colorScheme.error,
            accent = MaterialTheme.colorScheme.error,
            onClick = onUpload,
        )
        else -> ActionRow(
            icon = SfIcons.Upload,
            label = stringResource(R.string.upload_to_webdav),
            accent = palette.accent,
            onClick = onUpload,
        )
    }
}

@Composable
private fun SleepTimerPicker(palette: ArtworkPalette, onBack: () -> Unit) {
    val chosen by SleepTimer.minutes.collectAsStateWithLifecycle()
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    val countdown = sleepTimerCountdown()
    val custom = chosen?.takeIf { it !in SleepTimer.PRESETS }
    var pickingCustom by remember { mutableStateOf(false) }

    if (pickingCustom) {
        CustomSleepTimerPage(
            palette = palette,
            initialMinutes = custom ?: 90,
            onBack = { pickingCustom = false },
            onStart = { minutes ->
                SleepTimer.start(minutes)
                onBack()
            },
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        SleepTimerHeader(
            title = stringResource(R.string.sleep_timer),
            subtitle = when {
                countdown != null -> stringResource(R.string.pause_countdown, countdown)
                afterTrack -> stringResource(R.string.pause_after_song)
                else -> stringResource(R.string.pause_after_a_while)
            },
            onBack = onBack,
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        SleepOption(
            label = stringResource(R.string.after_this_song),
            selected = afterTrack,
            accent = palette.accent,
        ) {
            SleepTimer.startAfterTrack()
            onBack()
        }
        SleepTimer.PRESETS.forEach { minutes ->
            SleepOption(
                label = if (minutes == 60) {
                    stringResource(R.string.one_hour)
                } else {
                    pluralStringResource(R.plurals.minute_count, minutes, minutes)
                },
                selected = minutes == chosen,
                accent = palette.accent,
            ) {
                SleepTimer.start(minutes)
                onBack()
            }
        }
        SleepOption(
            label = custom?.let {
                stringResource(R.string.sleep_timer_custom) + " · " + sleepDuration(it)
            } ?: stringResource(R.string.sleep_timer_custom),
            selected = custom != null,
            accent = palette.accent,
        ) { pickingCustom = true }
        if (chosen != null || afterTrack) {
            ActionRow(SfIcons.Close, stringResource(R.string.turn_off_timer), accent = palette.accent) {
                SleepTimer.cancel()
                onBack()
            }
        }
    }
}

@Composable
private fun SleepTimerHeader(title: String, subtitle: String, onBack: () -> Unit) {
    val colors = iosMenuColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                SfIcons.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = colors.label,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    letterSpacing = (-0.3).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.label,
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                color = colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun CustomSleepTimerPage(
    palette: ArtworkPalette,
    initialMinutes: Int,
    onBack: () -> Unit,
    onStart: (Int) -> Unit,
) {
    var minutes by remember { mutableIntStateOf(initialMinutes) }
    val foreground = MaterialTheme.colorScheme.onBackground
    val readout = iosMenuColors().label

    Column(Modifier.fillMaxWidth()) {
        SleepTimerHeader(
            title = stringResource(R.string.sleep_timer_custom),
            subtitle = stringResource(R.string.pause_after_a_while),
            onBack = onBack,
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        Text(
            text = sleepDuration(minutes),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = readout,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepButton(
                label = "−",
                contentDescription = stringResource(R.string.less),
                enabled = minutes > SleepTimer.STEP_MINUTES,
            ) { minutes -= SleepTimer.STEP_MINUTES }
            ThinSlider(
                value = SleepTimer.fractionOf(minutes),
                onValueChange = { minutes = SleepTimer.minutesAt(it) },
                idleHeight = 6.dp,
                activeHeight = 10.dp,
                activeColor = foreground.copy(alpha = 0.92f),
                inactiveColor = foreground.copy(alpha = 0.26f),
                modifier = Modifier.weight(1f),
            )
            StepButton(
                label = "+",
                contentDescription = stringResource(R.string.more),
                enabled = minutes < SleepTimer.MAX_MINUTES,
            ) { minutes += SleepTimer.STEP_MINUTES }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)
        ActionRow(SfIcons.SleepTimer, stringResource(R.string.start_sleep_timer), accent = palette.accent) {
            onStart(minutes)
        }
    }
}

@Composable
private fun StepButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) iosMenuColors().label else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
        )
    }
}

@Composable
private fun sleepDuration(minutes: Int): String = if (minutes >= 60) {
    stringResource(R.string.hours_minutes_short, minutes / 60, minutes % 60)
} else {
    stringResource(R.string.minutes_short, minutes)
}

@Composable
private fun SleepOption(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    IosMenuRow(
        label = label,
        icon = if (selected) SfIcons.Check else null,
        onClick = onClick,
        iconTint = accent,
    )
}

@Composable
private fun sleepTimerStatus(): String? {
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    return sleepTimerCountdown() ?: stringResource(R.string.after_this_song).takeIf { afterTrack }
}

@Composable
private fun sleepTimerCountdown(): String? {
    val deadline by SleepTimer.deadline.collectAsStateWithLifecycle()
    val remaining by produceState<Long?>(initialValue = SleepTimer.remainingMs(), deadline) {
        while (deadline != null) {
            value = SleepTimer.remainingMs()
            delay(1_000)
        }
        value = null
    }
    return remaining?.let {
        val seconds = it / 1000
        "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }
}

@Composable
internal fun ActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    tint: Color? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    description: String? = null,
    onClick: () -> Unit,
) {
    if (LocalActionRowStyle.current == ActionRowStyle.Menu) {
        IosMenuRow(
            label = label,
            icon = icon,
            onClick = onClick,
            description = description,
            value = value,
            iconTint = tint,
            valueColor = accent,
            enabled = enabled,
        )
        return
    }
    val foreground = MaterialTheme.colorScheme.onBackground
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = (tint ?: foreground).copy(alpha = alpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = foreground.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = accent.copy(alpha = alpha),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LoadingActionRow(icon: ImageVector, label: String, palette: ArtworkPalette) {
    IosMenuRow(label = label, icon = icon, onClick = null, enabled = false) {
        CircularProgressIndicator(
            color = palette.onBackgroundVariant,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun SheetTrackHeader(
    song: Song,
    modifier: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .trackArtSize(52.dp, song.isVideo)
                .clip(RoundedCornerShape(8.dp))
                .thumbnailBorder(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SheetHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
    )
}
