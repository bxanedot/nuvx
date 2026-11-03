package com.bxane.nuvx.desktop

import com.bxane.nuvx.core.KeyValueStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.prefs.Preferences

/**
 * Desktop settings and history in one properties file. java.util.prefs refuses values over 8 KB, and the play history
 * outgrows that after about 21 songs. Values saved before this file existed are copied from [legacy] once.
 * Each write goes to a temporary file that then replaces the old one, so a crash mid-write keeps the previous data.
 */
internal class FileStore(private val file: File, legacy: Preferences? = null) : KeyValueStore {
    private val values = Properties()

    init {
        if (file.isFile) file.reader(Charsets.UTF_8).use(values::load)
        else if (legacy != null) {
            runCatching { legacy.keys().forEach { key -> legacy.get(key, null)?.let { values.setProperty(key, it) } } }
            if (!values.isEmpty) save()
        }
    }

    @Synchronized override fun get(key: String): String? = values.getProperty(key)

    @Synchronized override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values.setProperty(key, value)
        save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writer(Charsets.UTF_8).use { values.store(it, null) }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** %APPDATA%\nuvx on Windows, ~/.nuvx elsewhere. */
        fun default(): FileStore = FileStore(
            File(System.getenv("APPDATA")?.let { File(it, "nuvx") } ?: File(System.getProperty("user.home"), ".nuvx"), "settings.properties"),
            Preferences.userRoot().node("com/bxane/nuvx"),
        )
    }
}
