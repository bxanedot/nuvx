package com.bxane.nuvx.data.presence

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.UUID

object AndroidPresence {
    private const val PREFS = "presence"
    private const val KEY_INSTALL_ID = "install_id"

    fun install(app: Application) {
        val presence = Presence(PresencePlatform.Android, installId(app))
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = presence.setOpen(true)
            override fun onStop(owner: LifecycleOwner) = presence.setOpen(false)
        })
    }

    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }
}
