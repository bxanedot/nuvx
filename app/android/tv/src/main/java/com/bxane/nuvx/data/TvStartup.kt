package com.bxane.nuvx.data

import android.content.Context
import androidx.startup.Initializer
import com.bxane.nuvx.data.settings.TvSettings

class TvStartup : Initializer<Unit> {
    override fun create(context: Context) {
        TvSettings.init(context)
        TvUsbMusic.install()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
