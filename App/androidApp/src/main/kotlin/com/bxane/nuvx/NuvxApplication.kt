package com.bxane.nuvx

import android.app.Application
import com.bxane.nuvx.core.*

class NuvxApplication : Application() {
    val store: KeyValueStore by lazy {
        val preferences = getSharedPreferences("nuvx", MODE_PRIVATE)
        object : KeyValueStore {
            override fun get(key: String): String? = preferences.getString(key, null)
            override fun put(key: String, value: String?) { preferences.edit().putString(key, value).apply() }
        }
    }
    val router by lazy { BackendRouter(defaultBackends()).bindTo(store) }
    val lyrics by lazy { LyricsChain(defaultLyricsProviders(nuvxHttpClient())) }
}
