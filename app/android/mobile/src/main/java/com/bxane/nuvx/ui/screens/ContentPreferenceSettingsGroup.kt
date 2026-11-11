package com.bxane.nuvx.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bxane.nuvx.R
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.icons.SfIcons
import com.bxane.nuvx.ui.components.IosSwitch

@Composable
internal fun ContentPreferenceSettingsGroup(
    search: SettingsSearch,
    preferMusicOnly: Boolean,
    hideRecents: Boolean,
    hideHomeVideos: Boolean,
    hideTopPicks: Boolean,
    hideHomeMoods: Boolean,
    squareVideoThumbnails: Boolean,
) {
    SearchableSettingsGroup(search, header = stringResource(R.string.content_preferences)) {
        val preferMusicOnlyTitle = stringResource(R.string.prefer_music_only)
        row(preferMusicOnlyTitle, "video", "audio", "music video", "convert", "song") {
            SettingsRow(
                icon = SfIcons.Video,
                title = preferMusicOnlyTitle,
                subtitle = stringResource(R.string.prefer_music_only_subtitle),
                trailing = {
                    IosSwitch(
                        checked = preferMusicOnly,
                        onCheckedChange = AppSettings::setPreferMusicOnly,
                    )
                },
                onClick = { AppSettings.setPreferMusicOnly(!preferMusicOnly) },
            )
        }
        val hideRecentsTitle = stringResource(R.string.hide_recents)
        row(hideRecentsTitle, "recents", "recent", "history", "feed", "home") {
            SettingsRow(
                icon = SfIcons.VisibilityOff,
                title = hideRecentsTitle,
                trailing = {
                    IosSwitch(
                        checked = hideRecents,
                        onCheckedChange = AppSettings::setHideRecents,
                    )
                },
                onClick = { AppSettings.setHideRecents(!hideRecents) },
            )
        }
        val hideHomeVideosTitle = stringResource(R.string.hide_home_videos)
        row(hideHomeVideosTitle, "video", "music video", "feed", "home") {
            SettingsRow(
                icon = SfIcons.VisibilityOff,
                title = hideHomeVideosTitle,
                subtitle = stringResource(R.string.hide_home_videos_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideHomeVideos,
                        onCheckedChange = AppSettings::setHideHomeVideos,
                    )
                },
                onClick = { AppSettings.setHideHomeVideos(!hideHomeVideos) },
            )
        }
        val hideTopPicksTitle = stringResource(R.string.hide_top_picks)
        row(hideTopPicksTitle, "top picks", "recommendations", "feed", "home") {
            SettingsRow(
                icon = SfIcons.AutoAwesome,
                title = hideTopPicksTitle,
                trailing = {
                    IosSwitch(
                        checked = hideTopPicks,
                        onCheckedChange = AppSettings::setHideTopPicks,
                    )
                },
                onClick = { AppSettings.setHideTopPicks(!hideTopPicks) },
            )
        }
        val hideHomeMoodsTitle = stringResource(R.string.hide_home_moods)
        row(hideHomeMoodsTitle, "mood", "moods", "find your mood", "feed", "home") {
            SettingsRow(
                icon = SfIcons.Star,
                title = hideHomeMoodsTitle,
                trailing = {
                    IosSwitch(
                        checked = hideHomeMoods,
                        onCheckedChange = AppSettings::setHideHomeMoods,
                    )
                },
                onClick = { AppSettings.setHideHomeMoods(!hideHomeMoods) },
            )
        }
        val squareVideoThumbnailsTitle = stringResource(R.string.square_video_thumbnails)
        row(squareVideoThumbnailsTitle, "video", "thumbnail", "cover", "artwork", "player") {
            SettingsRow(
                icon = SfIcons.Apps,
                title = squareVideoThumbnailsTitle,
                subtitle = stringResource(R.string.square_video_thumbnails_subtitle),
                trailing = {
                    IosSwitch(
                        checked = squareVideoThumbnails,
                        onCheckedChange = AppSettings::setSquareVideoThumbnails,
                    )
                },
                onClick = { AppSettings.setSquareVideoThumbnails(!squareVideoThumbnails) },
            )
        }
    }
}
