package com.bxane.nuvx.data

import android.content.Context
import androidx.startup.Initializer
import com.bxane.nuvx.data.settings.TvSettings
import com.bxane.nuvx.ui.player.CanvasPlaybackLimits
import com.bxane.nuvx.ui.tv.DeviceType

class TvStartup : Initializer<Unit> {
    override fun create(context: Context) {
        TvSettings.init(context)
        TvUsbMusic.install()
        if (DeviceType.isTv(context)) CanvasPlaybackLimits.maxFrameRate = 24
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
