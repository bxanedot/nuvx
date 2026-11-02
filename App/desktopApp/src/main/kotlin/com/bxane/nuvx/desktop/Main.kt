package com.bxane.nuvx.desktop

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.bxane.nuvx.core.*
import com.bxane.nuvx.ui.NuvxApp
import java.util.prefs.Preferences

fun main() = application {
    val store = remember {
        val preferences = Preferences.userRoot().node("com/bxane/nuvx")
        object : KeyValueStore {
            override fun get(key: String): String? = preferences.get(key, null)
            override fun put(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences.put(key, value) }
        }
    }
    val router = remember { BackendRouter(defaultBackends()).bindTo(store) }
    val player = remember { runCatching { DesktopPlayer(router) }.getOrNull() }
    val http = remember { nuvxHttpClient() }
    val lyrics = remember { LrcLib(http) }
    DisposableEffect(Unit) { onDispose { player?.close(); http.close() } }
    Window(onCloseRequest = ::exitApplication, title = "nuvx", state = rememberWindowState(width = 1180.dp, height = 780.dp)) {
        if (player != null) NuvxApp(router, store, player, lyrics, watch = false)
        else MaterialTheme { Surface { Text("nuvx needs VLC 3 to play audio. Install VLC for your system, or set NUVX_VLC_HOME to its portable folder, then reopen nuvx.") } }
    }
}
