package com.bxane.nuvx.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.R
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.components.IosSwitch
import com.bxane.nuvx.ui.icons.SfIcons

@Composable
internal fun PerformanceSettingsGroup(
    search: SettingsSearch,
    highPerformanceMode: Boolean,
    selectedPerformanceRefreshRate: Int,
    supportedRefreshRates: List<Int>,
    onEnableRequested: () -> Unit,
) {
    SearchableSettingsGroup(search, header = stringResource(R.string.performance)) {
        val highPerformanceModeTitle = stringResource(R.string.high_performance_mode)
        row(highPerformanceModeTitle, "frame rate", "refresh rate", "hz", "battery", "smooth") {
            SettingsRow(
                icon = SfIcons.Performance,
                title = highPerformanceModeTitle,
                subtitle = if (highPerformanceMode) {
                    stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                } else {
                    stringResource(R.string.high_performance_subtitle)
                },
                trailing = {
                    IosSwitch(
                        checked = highPerformanceMode,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onEnableRequested()
                            } else {
                                AppSettings.setHighPerformanceMode(false)
                            }
                        },
                    )
                },
                onClick = {
                    if (highPerformanceMode) {
                        AppSettings.setHighPerformanceMode(false)
                    } else {
                        onEnableRequested()
                    }
                },
            )
        }
        if (highPerformanceMode) {
            val refreshRateTitle = stringResource(R.string.refresh_rate)
            row(refreshRateTitle, "hz", "frame rate") {
                SettingsRow(
                    icon = SfIcons.FrameRate,
                    title = refreshRateTitle,
                )
                SegmentedControl(
                    options = supportedRefreshRates.map { "$it Hz" },
                    selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                    onSelect = { index ->
                        AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                    },
                    modifier = Modifier.padding(
                        start = TEXT_INSET,
                        end = ROW_INSET,
                        bottom = 14.dp,
                    ),
                )
            }
        }
    }
}
