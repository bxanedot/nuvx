package com.bxane.nuvx.ui.tv

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceType {

    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                pm.hasSystemFeature("android.hardware.type.television")
    }

    fun hasTouchScreen(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun isLowRam(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isLowRamDevice
    }

    fun hasMicrophone(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
}
