package com.bxane.nuvx.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.R
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.lyrics.translationLanguageName
import com.bxane.nuvx.data.settings.AppFont
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.PlayerTheme
import com.bxane.nuvx.data.settings.ThemeMode
import com.bxane.nuvx.ui.components.IosSwitch
import com.bxane.nuvx.ui.icons.SfIcons
import com.bxane.nuvx.ui.player.fullBleedArtworkAvailable
import java.util.Locale

@Composable
internal fun AppearanceSettingsGroup(
    search: SettingsSearch,
    windowWidth: Dp,
    theme: ThemeMode,
    appFont: AppFont,
    reduceAnimation: Boolean,
    reduceDynamicBlur: Boolean,
    disableBlendEffects: Boolean,
    liquidGlass: Boolean,
    liquidGlassSupported: Boolean,
    glassPerformance: Boolean,
    progressiveBlur: Boolean,
    fullBleedArtwork: Boolean,
    fullBleedDetailArtwork: Boolean,
    playerTheme: PlayerTheme,
    animatedCanvas: Boolean,
    canvasOverCellular: Boolean,
    signatureApiEnabled: Boolean,
    signatureApiUrl: String,
    syncedLyrics: Boolean,
    lyricsBlur: Boolean,
    lyricsSources: Set<LyricsSource>,
    translationLanguage: String,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onEditSignatureApi: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
) {
    SearchableSettingsGroup(search, header = stringResource(R.string.appearance)) {
        val themeTitle = stringResource(R.string.theme)
        row(themeTitle, "dark mode", "light mode") {
            SettingsRow(icon = SfIcons.Brightness4, title = themeTitle)
            SegmentedControl(
                options = ThemeMode.entries.map { it.localizedLabel() },
                selectedIndex = ThemeMode.entries.indexOf(theme),
                onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val fontTitle = stringResource(R.string.font)
        row(fontTitle, "typeface", "sf pro", "legacy") {
            SettingsRow(
                icon = SfIcons.Font,
                title = fontTitle,
                subtitle = appFont.description(),
                value = appFont.localizedLabel(),
                onClick = { onOpenChoice(SettingsChoiceKind.FONT) },
            )
        }
        val reduceAnimationTitle = stringResource(R.string.reduce_animation)
        row(reduceAnimationTitle, "motion") {
            SettingsRow(
                icon = SfIcons.MotionPhotosOff,
                title = reduceAnimationTitle,
                subtitle = stringResource(R.string.reduce_animation_subtitle),
                trailing = {
                    IosSwitch(
                        checked = reduceAnimation,
                        onCheckedChange = AppSettings::setReduceAnimation,
                    )
                },
                onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
            )
        }
        val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
        row(reduceDynamicBlurTitle, "blur", "performance") {
            SettingsRow(
                icon = SfIcons.BlurOff,
                title = reduceDynamicBlurTitle,
                subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                trailing = {
                    IosSwitch(
                        checked = reduceDynamicBlur,
                        onCheckedChange = AppSettings::setReduceDynamicBlur,
                    )
                },
                onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
            )
        }
        val disableBlendEffectsTitle = stringResource(R.string.disable_blend_effects)
        row(disableBlendEffectsTitle, "blend", "blending", "colour", "color", "tint", "player", "menu") {
            SettingsRow(
                icon = SfIcons.Gradient,
                title = disableBlendEffectsTitle,
                subtitle = stringResource(R.string.disable_blend_effects_subtitle),
                trailing = {
                    IosSwitch(
                        checked = disableBlendEffects,
                        onCheckedChange = AppSettings::setDisableBlendEffects,
                    )
                },
                onClick = { AppSettings.setDisableBlendEffects(!disableBlendEffects) },
            )
        }
        val liquidGlassTitle = stringResource(R.string.liquid_glass)
        row(liquidGlassTitle, "glass", "blur") {
            SettingsRow(
                icon = SfIcons.AutoAwesome,
                title = liquidGlassTitle,
                subtitle = stringResource(
                    if (liquidGlassSupported) {
                        R.string.liquid_glass_subtitle
                    } else {
                        R.string.liquid_glass_unavailable
                    },
                ),
                enabled = liquidGlassSupported,
                trailing = {
                    IosSwitch(
                        checked = liquidGlass,
                        onCheckedChange = AppSettings::setLiquidGlass,
                        enabled = liquidGlassSupported,
                    )
                },
                onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
            )
        }
        if (liquidGlass && liquidGlassSupported) {
            val glassPerformanceTitle = stringResource(R.string.glass_performance)
            row(glassPerformanceTitle, "glass", "performance", "refraction", "frosted", "lag", divided = false) {
                SettingsSubRow(
                    title = glassPerformanceTitle,
                    checked = glassPerformance,
                    onCheckedChange = AppSettings::setGlassPerformance,
                )
            }
            val progressiveBlurTitle = stringResource(R.string.progressive_blur)
            row(progressiveBlurTitle, "blur", "status bar", "top", divided = false) {
                SettingsSubRow(
                    title = progressiveBlurTitle,
                    checked = progressiveBlur,
                    onCheckedChange = AppSettings::setProgressiveBlur,
                )
            }
        }
        if (fullBleedArtworkAvailable(windowWidth)) {
            val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
            row(fullScreenCoverArtTitle, "artwork", "player") {
                SettingsRow(
                    icon = SfIcons.Fullscreen,
                    title = fullScreenCoverArtTitle,
                    subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = fullBleedArtwork,
                            onCheckedChange = AppSettings::setFullBleedArtwork,
                        )
                    },
                    onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                )
            }
        }
        val fullScreenDetailCoverTitle = stringResource(R.string.full_screen_detail_cover)
        row(fullScreenDetailCoverTitle, "artwork", "album", "playlist", "cover") {
            SettingsRow(
                icon = SfIcons.Fullscreen,
                title = fullScreenDetailCoverTitle,
                subtitle = stringResource(R.string.full_screen_detail_cover_subtitle),
                trailing = {
                    IosSwitch(
                        checked = fullBleedDetailArtwork,
                        onCheckedChange = AppSettings::setFullBleedDetailArtwork,
                    )
                },
                onClick = { AppSettings.setFullBleedDetailArtwork(!fullBleedDetailArtwork) },
            )
        }
        val playerThemeTitle = stringResource(R.string.player_theme)
        row(playerThemeTitle, "background", "player", "mesh", "gradient", "legacy", "simple", "blur") {
            SettingsRow(
                icon = SfIcons.Gradient,
                title = playerThemeTitle,
                subtitle = playerTheme.description(),
            )
            SegmentedControl(
                options = PlayerTheme.entries.map { it.localizedLabel() },
                selectedIndex = PlayerTheme.entries.indexOf(playerTheme),
                onSelect = { AppSettings.setPlayerTheme(PlayerTheme.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val animatedCoverArtTitle = stringResource(R.string.animated_cover_art)
        row(animatedCoverArtTitle, "canvas", "video", "spotify") {
            SettingsRow(
                icon = SfIcons.Animation,
                title = animatedCoverArtTitle,
                subtitle = stringResource(R.string.animated_cover_art_subtitle),
                trailing = {
                    IosSwitch(
                        checked = animatedCanvas,
                        onCheckedChange = AppSettings::setAnimatedCanvas,
                    )
                },
                onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
            )
        }
        if (animatedCanvas) {
            val coverCellularTitle = stringResource(R.string.animated_cover_cellular)
            row(coverCellularTitle, "canvas", "cellular", divided = false) {
                SettingsSubRow(
                    title = coverCellularTitle,
                    checked = canvasOverCellular,
                    onCheckedChange = AppSettings::setCanvasOverCellular,
                )
            }
            val spotifyCanvasTitle = stringResource(R.string.integrate_spotify_canvas)
            row(spotifyCanvasTitle, "spotify", "canvas", divided = false) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSpotifyCanvasAuth)
                        .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = spotifyCanvasTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Chevron()
                }
            }
        }
        val artistSignaturesTitle = stringResource(R.string.artist_signatures)
        row(artistSignaturesTitle, "signature", "artist", "logo") {
            SettingsRow(
                icon = SfIcons.Draw,
                title = artistSignaturesTitle,
                subtitle = stringResource(R.string.artist_signatures_subtitle),
                trailing = {
                    IosSwitch(
                        checked = signatureApiEnabled,
                        onCheckedChange = AppSettings::setSignatureApiEnabled,
                    )
                },
                onClick = { AppSettings.setSignatureApiEnabled(!signatureApiEnabled) },
            )
        }
        if (signatureApiEnabled) {
            val signatureUrlTitle = stringResource(R.string.artist_signatures_url)
            row(signatureUrlTitle, "signature", "api", "url", divided = false) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEditSignatureApi)
                        .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = signatureApiUrl.ifBlank {
                            stringResource(R.string.artist_signatures_unconfigured)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Chevron()
                }
            }
        }
        val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
        row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch") {
            SettingsRow(
                icon = SfIcons.Notes,
                title = syncedLyricsTitle,
                subtitle = stringResource(R.string.synced_lyrics_subtitle),
                trailing = {
                    IosSwitch(
                        checked = syncedLyrics,
                        onCheckedChange = AppSettings::setSyncedLyrics,
                    )
                },
                onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
            )
        }
        if (syncedLyrics) {
            val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
            row(lyricsBlurTitle, "lyrics", "blur") {
                SettingsRow(
                    icon = SfIcons.BlurOn,
                    title = lyricsBlurTitle,
                    subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = lyricsBlur,
                            onCheckedChange = AppSettings::setLyricsBlur,
                        )
                    },
                    onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                )
            }
            val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
            row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch") {
                SettingsRow(
                    icon = SfIcons.Language,
                    title = lyricsSourcesTitle,
                    subtitle = lyricsSources
                        .sortedBy { it.ordinal }
                        .joinToString(", ") { it.label }
                        .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                    trailing = { Chevron() },
                    onClick = onLyricsSources,
                )
            }
            val translationLanguageTitle = stringResource(R.string.translation_language)
            row(translationLanguageTitle, "lyrics", "translate") {
                SettingsRow(
                    icon = SfIcons.Translate,
                    title = translationLanguageTitle,
                    subtitle = if (translationLanguage.isBlank()) {
                        stringResource(R.string.translation_language_subtitle)
                    } else {
                        translationLanguageName(
                            translationLanguage,
                            AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
                        )
                    },
                    trailing = { Chevron() },
                    onClick = onTranslationLanguage,
                )
            }
        }
    }
}
