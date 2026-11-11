package com.bxane.nuvx.ui.screens

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bxane.nuvx.AppIcon
import com.bxane.nuvx.PodcastsSymbol
import com.bxane.nuvx.R
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.components.IosSwitch
import com.bxane.nuvx.ui.icons.SfIcons

internal fun hidePodcastsTabIcon(): ImageVector = PodcastsSymbol.toImageVector()

@Composable
internal fun MiscellaneousSettingsGroup(
    context: Context,
    search: SettingsSearch,
    classicSearch: Boolean,
    carMode: Boolean,
    hideRadioTab: Boolean,
    hidePodcastsTab: Boolean,
    swipeToPlayNext: Boolean,
    dontRepeatSuggestions: Boolean,
    stopOnTaskRemoved: Boolean,
    hideVolumeBar: Boolean,
    listenTogetherButton: Boolean,
    hideStatusBarInPlayer: Boolean,
    showQueueHistory: Boolean,
    hideSongStatus: Boolean,
    smartVersionAlignment: Boolean,
    nerdStats: Boolean,
) {
    SearchableSettingsGroup(search, header = stringResource(R.string.miscellaneous)) {
        val classicSearchTitle = stringResource(R.string.classic_search)
        row(classicSearchTitle, "search", "classic", "categories", "browse") {
            SettingsRow(
                icon = SfIcons.Search,
                title = classicSearchTitle,
                subtitle = stringResource(R.string.classic_search_subtitle),
                trailing = {
                    IosSwitch(
                        checked = classicSearch,
                        onCheckedChange = AppSettings::setClassicSearch,
                    )
                },
                onClick = { AppSettings.setClassicSearch(!classicSearch) },
            )
        }
        val carModeTitle = stringResource(R.string.car_mode)
        row(carModeTitle, "car", "auto", "head unit", "stereo", "landscape", "dashboard") {
            SettingsRow(
                icon = SfIcons.Car,
                title = carModeTitle,
                subtitle = stringResource(R.string.car_mode_subtitle),
                trailing = {
                    IosSwitch(
                        checked = carMode,
                        onCheckedChange = AppSettings::setCarMode,
                    )
                },
                onClick = { AppSettings.setCarMode(!carMode) },
            )
        }
        val hideRadioTabTitle = stringResource(R.string.hide_radio_tab)
        row(hideRadioTabTitle, "radio", "tab", "stations", "nav") {
            SettingsRow(
                icon = SfIcons.Radio,
                title = hideRadioTabTitle,
                subtitle = stringResource(R.string.hide_radio_tab_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideRadioTab,
                        onCheckedChange = AppSettings::setHideRadioTab,
                    )
                },
                onClick = { AppSettings.setHideRadioTab(!hideRadioTab) },
            )
        }
        val hidePodcastsTabTitle = stringResource(R.string.hide_podcasts_tab)
        row(hidePodcastsTabTitle, "podcasts", "podcast", "episodes", "tab", "nav") {
            SettingsRow(
                icon = hidePodcastsTabIcon(),
                title = hidePodcastsTabTitle,
                subtitle = stringResource(R.string.hide_podcasts_tab_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hidePodcastsTab,
                        onCheckedChange = AppSettings::setHidePodcastsTab,
                    )
                },
                onClick = { AppSettings.setHidePodcastsTab(!hidePodcastsTab) },
            )
        }
        val playNextOnSwipeTitle = stringResource(R.string.play_next_on_swipe)
        row(playNextOnSwipeTitle, "swipe", "queue") {
            SettingsRow(
                icon = SfIcons.PlaylistPlay,
                title = playNextOnSwipeTitle,
                subtitle = if (swipeToPlayNext) {
                    stringResource(R.string.swipe_plays_next)
                } else {
                    stringResource(R.string.swipe_adds_to_queue)
                },
                trailing = {
                    IosSwitch(
                        checked = swipeToPlayNext,
                        onCheckedChange = AppSettings::setSwipeToPlayNext,
                    )
                },
                onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
            )
        }
        val dontRepeatSongsTitle = stringResource(R.string.dont_repeat_songs)
        row(dontRepeatSongsTitle, "radio", "suggestions") {
            SettingsRow(
                icon = SfIcons.History,
                title = dontRepeatSongsTitle,
                subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                trailing = {
                    IosSwitch(
                        checked = dontRepeatSuggestions,
                        onCheckedChange = AppSettings::setDontRepeatSuggestions,
                    )
                },
                onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
            )
        }
        val stopMusicOnCloseTitle = stringResource(R.string.stop_music_on_close)
        row(stopMusicOnCloseTitle, "notification", "background") {
            SettingsRow(
                icon = SfIcons.MusicOff,
                title = stopMusicOnCloseTitle,
                subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                trailing = {
                    IosSwitch(
                        checked = stopOnTaskRemoved,
                        onCheckedChange = AppSettings::setStopOnTaskRemoved,
                    )
                },
                onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
            )
        }
        val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
        row(hideVolumeBarTitle, "volume", "player") {
            SettingsRow(
                icon = SfIcons.VolumeOff,
                title = hideVolumeBarTitle,
                subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideVolumeBar,
                        onCheckedChange = AppSettings::setHideVolumeBar,
                    )
                },
                onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
            )
        }
        val listenTogetherButtonTitle = stringResource(R.string.show_listen_together_button)
        row(listenTogetherButtonTitle, "party", "jam", "output", "headphones", "player") {
            SettingsRow(
                icon = SfIcons.Groups,
                title = listenTogetherButtonTitle,
                subtitle = stringResource(R.string.show_listen_together_button_subtitle),
                trailing = {
                    IosSwitch(
                        checked = listenTogetherButton,
                        onCheckedChange = AppSettings::setShowListenTogetherButton,
                    )
                },
                onClick = { AppSettings.setShowListenTogetherButton(!listenTogetherButton) },
            )
        }
        val hideStatusBarTitle = stringResource(R.string.hide_status_bar_in_player)
        row(hideStatusBarTitle, "status bar", "immersive", "fullscreen", "player") {
            SettingsRow(
                icon = SfIcons.WebAssetOff,
                title = hideStatusBarTitle,
                subtitle = stringResource(R.string.hide_status_bar_in_player_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideStatusBarInPlayer,
                        onCheckedChange = AppSettings::setHideStatusBarInPlayer,
                    )
                },
                onClick = { AppSettings.setHideStatusBarInPlayer(!hideStatusBarInPlayer) },
            )
        }
        val showQueueHistoryTitle = stringResource(R.string.show_queue_history)
        row(showQueueHistoryTitle, "player", "queue", "history", "previously played") {
            SettingsRow(
                icon = SfIcons.History,
                title = showQueueHistoryTitle,
                subtitle = stringResource(R.string.show_queue_history_subtitle),
                trailing = {
                    IosSwitch(
                        checked = showQueueHistory,
                        onCheckedChange = AppSettings::setShowQueueHistory,
                    )
                },
                onClick = { AppSettings.setShowQueueHistory(!showQueueHistory) },
            )
        }
        val hideSongStatusTitle = stringResource(R.string.hide_song_status)
        row(hideSongStatusTitle, "player", "playing from", "played by") {
            SettingsRow(
                icon = SfIcons.VisibilityOff,
                title = hideSongStatusTitle,
                subtitle = stringResource(R.string.hide_song_status_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideSongStatus,
                        onCheckedChange = AppSettings::setHideSongStatus,
                    )
                },
                onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
            )
        }
        val smartAlignmentTitle = stringResource(R.string.smart_version_alignment)
        row(smartAlignmentTitle, "alignment", "sync", "waveform", "video", "audio", "skit", "intro") {
            SettingsRow(
                icon = SfIcons.GraphicEq,
                title = smartAlignmentTitle,
                subtitle = stringResource(R.string.smart_version_alignment_subtitle),
                trailing = {
                    IosSwitch(
                        checked = smartVersionAlignment,
                        onCheckedChange = AppSettings::setSmartVersionAlignment,
                    )
                },
                onClick = { AppSettings.setSmartVersionAlignment(!smartVersionAlignment) },
            )
        }
        val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
        row(showNerdStatsTitle, "debug", "bitrate", "codec") {
            SettingsRow(
                icon = SfIcons.BarChart,
                title = showNerdStatsTitle,
                subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                trailing = {
                    IosSwitch(
                        checked = nerdStats,
                        onCheckedChange = AppSettings::setShowNerdStats,
                    )
                },
                onClick = { AppSettings.setShowNerdStats(!nerdStats) },
            )
        }
        val appIconTitle = stringResource(R.string.app_icon)
        row(appIconTitle, "icon", "launcher", "logo") {
            var selectedIcon by remember { mutableStateOf(AppIcon.current(context)) }
            SettingsRow(icon = SfIcons.Apps, title = appIconTitle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon.entries.forEach { option ->
                    val selected = option == selectedIcon
                    Column(
                        modifier = Modifier
                            .width(84.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                selectedIcon = option
                                AppIcon.apply(context, option)
                            }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val preview = option.previewRes
                        if (preview == null) {
                            AndroidView(
                                factory = { ctx ->
                                    android.widget.ImageView(ctx).apply { setImageResource(option.iconRes) }
                                },
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(22.dp)),
                            )
                        } else {
                            Image(
                                painter = painterResource(preview),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(68.dp)
                                    .scale(1.5f),
                            )
                        }
                        Text(
                            text = stringResource(option.labelRes),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    
}
}
