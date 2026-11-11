package com.bxane.nuvx.ui.screens

import android.media.AudioFormat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.R
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.AutomixPerformanceMode
import com.bxane.nuvx.data.settings.BitPerfectMode
import com.bxane.nuvx.data.settings.OutputPcmMode
import com.bxane.nuvx.playback.AudioOutputStatus
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectOutput
import com.bxane.nuvx.playback.audio.bitperfect.usb.UsbVolume
import com.bxane.nuvx.ui.components.IosSwitch
import com.bxane.nuvx.ui.icons.SfIcons
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSettingsGroup(
    search: SettingsSearch,
    bitPerfectReady: Boolean,
    outputStatus: AudioOutputStatus.Snapshot,
    outputPcmMode: OutputPcmMode,
    preferUsbDac: Boolean,
    bitPerfectMode: BitPerfectMode,
    bitPerfectStatus: BitPerfectOutput.Status,
    dacVolume: UsbVolume.Snapshot?,
    usbAllowFixedVolume: Boolean,
    convertForLimitedDac: Boolean,
    loudnessNormalization: Boolean,
    loudnessOffOnSpeaker: Boolean,
    smartFade: Boolean,
    crossfade: Int,
    automixPerformance: AutomixPerformanceMode,
    skipSilence: Boolean,
    spatialAudio: Boolean,
    playingDolbyAtmos: Boolean,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
    onConfirmFixedVolume: () -> Unit,
    onEqualizer: () -> Unit,
) {
    SearchableSettingsGroup(
        search,
        header = stringResource(R.string.playback),
        footer = stringResource(R.string.bit_perfect_bypassed_footer).takeIf { bitPerfectReady },
    ) {
        val outputPrecisionTitle = stringResource(R.string.output_precision)
        row(outputPrecisionTitle, "pcm", "bit depth", "sample rate", "dac") {
            SettingsRow(
                icon = SfIcons.GraphicEq,
                title = outputPrecisionTitle,
                subtitle = buildString {
                    append(outputStatus.sink)
                    append(" · ")
                    append(outputStatus.deviceName)
                    (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                        ?.let { append(" · ${it / 1000.0} kHz") }
                    append(" · ")
                    append(AudioOutputStatus.encodingLabel(outputStatus))
                },
            )
            SegmentedControl(
                options = OutputPcmMode.entries.map(OutputPcmMode::label),
                selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val preferUsbDacTitle = stringResource(R.string.prefer_usb_dac)
        row(preferUsbDacTitle, "headphone", "output") {
            SettingsSubRow(
                title = preferUsbDacTitle,
                checked = preferUsbDac,
                onCheckedChange = AppSettings::setPreferUsbDac,
                badge = stringResource(R.string.connected).takeIf { outputStatus.isUsb },
            )
        }
        val bitPerfectTitle = stringResource(R.string.bit_perfect_output)
        row(bitPerfectTitle, "usb", "dac", "exclusive", "hi-res", "bitperfect") {
            SettingsRow(
                icon = SfIcons.Usb,
                title = bitPerfectTitle,
                subtitleContent = {
                    Column {
                        Text(
                            text = bitPerfectSubtitle(bitPerfectMode, bitPerfectStatus),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = bitPerfectMode.localizedLabel(),
                            modifier = Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = { onOpenChoice(SettingsChoiceKind.BIT_PERFECT) },
            )
        }
        dacVolume?.let { volume ->
            val dacVolumeTitle = stringResource(R.string.dac_volume)
            row(dacVolumeTitle, "volume", "usb", "dac", divided = false) {
                SliderRow(
                    icon = SfIcons.VolumeUp,
                    title = dacVolumeTitle,
                    subtitle = stringResource(R.string.dac_volume_subtitle),
                    value = "%.1f dB".format(volume.currentDb),
                    sliderValue = volume.currentDb.toFloat(),
                    onSliderValue = { BitPerfectOutput.setHardwareVolume(it.toDouble()) },
                    valueRange = volume.minDb.toFloat()..volume.maxDb.toFloat(),
                    steps = 0,
                )
            }
        }
        if (bitPerfectMode == BitPerfectMode.USB_DIRECT) {
            val fixedVolumeTitle = stringResource(R.string.usb_allow_fixed_volume)
            row(fixedVolumeTitle, "volume", "usb", "dac", divided = false) {
                SettingsSubRow(
                    title = fixedVolumeTitle,
                    subtitle = stringResource(R.string.usb_allow_fixed_volume_subtitle),
                    checked = usbAllowFixedVolume,
                    onCheckedChange = { on ->
                        if (on) onConfirmFixedVolume() else AppSettings.setUsbAllowFixedVolume(false)
                    },
                )
            }
        }
        if (bitPerfectMode != BitPerfectMode.OFF && bitPerfectMode.isSupported) {
            val convertTitle = stringResource(R.string.convert_for_limited_dac)
            row(convertTitle, "convert", "resample", "usb", "dac", "bitperfect", divided = false) {
                SettingsSubRow(
                    title = convertTitle,
                    subtitle = stringResource(R.string.convert_for_limited_dac_subtitle),
                    checked = convertForLimitedDac,
                    onCheckedChange = AppSettings::setConvertForLimitedDac,
                )
            }
        }
        val loudnessTitle = stringResource(R.string.loudness_normalization)
        row(loudnessTitle, "loudness", "volume", "normalize", "replaygain", "lufs") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.VolumeUp,
                    title = loudnessTitle,
                    subtitle = stringResource(R.string.loudness_normalization_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = loudnessNormalization,
                            onCheckedChange = AppSettings::setLoudnessNormalization,
                        )
                    },
                    onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
                )
            }
        }
        val loudnessSpeakerTitle = stringResource(R.string.loudness_off_on_speaker)
        row(loudnessSpeakerTitle, "loudness", "speaker", "normalize") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsSubRow(
                    title = loudnessSpeakerTitle,
                    checked = loudnessOffOnSpeaker,
                    onCheckedChange = AppSettings::setLoudnessOffOnSpeaker,
                )
            }
        }
        if (!smartFade) {
            val crossfadeTitle = stringResource(R.string.crossfade)
            row(crossfadeTitle, "fade", "gapless") {
                BypassedByBitPerfect(bitPerfectReady) {
                    SliderRow(
                        icon = SfIcons.Waves,
                        title = crossfadeTitle,
                        subtitle = stringResource(R.string.crossfade_subtitle),
                        value = if (crossfade == 0) stringResource(R.string.off) else "${crossfade}s",
                        sliderValue = crossfade.toFloat(),
                        onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                        valueRange = 0f..12f,
                        steps = 11,
                    )
                }
            }
        }
        val automixTitle = stringResource(R.string.automix)
        row(automixTitle, "crossfade", "smart fade", "mix") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.AutoAwesome,
                    title = automixTitle,
                    subtitle = if (smartFade) {
                        stringResource(R.string.automix_enabled_subtitle)
                    } else {
                        stringResource(R.string.automix_disabled_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = smartFade,
                            onCheckedChange = AppSettings::setSmartFadeEnabled,
                        )
                    },
                    onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
                )
            }
        }
        val automixPerformanceTitle = stringResource(R.string.automix_performance)
        row(automixPerformanceTitle, "cpu", "battery") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.Tune,
                    title = automixPerformanceTitle,
                    subtitle = stringResource(R.string.automix_performance_subtitle),
                    value = automixPerformance.localizedLabel(),
                    onClick = { onOpenChoice(SettingsChoiceKind.AUTOMIX_PERFORMANCE) },
                )
            }
        }
        val skipSilenceTitle = stringResource(R.string.skip_silence)
        row(skipSilenceTitle, "silence") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.VolumeOff,
                    title = skipSilenceTitle,
                    subtitle = if (outputStatus.actualEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        stringResource(R.string.skip_silence_float_subtitle)
                    } else {
                        stringResource(R.string.skip_silence_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = skipSilence,
                            onCheckedChange = AppSettings::setSkipSilence,
                        )
                    },
                    onClick = { AppSettings.setSkipSilence(!skipSilence) },
                )
            }
        }
        val spatialAudioTitle = stringResource(R.string.spatial_audio)
        row(spatialAudioTitle, "surround", "3d") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.SurroundSound,
                    title = spatialAudioTitle,
                    subtitle = if (playingDolbyAtmos) {
                        stringResource(R.string.spatial_audio_atmos_subtitle)
                    } else {
                        stringResource(R.string.spatial_audio_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = spatialAudio,
                            onCheckedChange = AppSettings::setSpatialAudio,
                        )
                    },
                    onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
                )
            }
        }
        val equalizerTitle = stringResource(R.string.equalizer)
        row(equalizerTitle, "eq", "bass", "treble") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.Tune,
                    title = equalizerTitle,
                    subtitle = stringResource(R.string.equalizer_subtitle),
                    onClick = onEqualizer,
                )
            }
        }
    }
}
