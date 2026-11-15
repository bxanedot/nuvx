package com.bxane.nuvx.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaylistArtworkStore {
    private const val PREFS = "nuvx_playlist_artwork"
    private const val KEY_OVERRIDES = "overrides"
    private val _version = MutableStateFlow(0)
    val version = _version.asStateFlow()

    @Synchronized
    fun get(context: Context, playlistId: String): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OVERRIDES, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optString(playlistId)
            ?.takeIf(String::isNotBlank)

    @Synchronized
    fun set(context: Context, playlistId: String, source: Uri): String {
        val app = context.applicationContext
        require(app.contentResolver.getType(source)?.startsWith("image/") == true) { "Choose an image file." }
        val directory = File(app.filesDir, "playlist-artwork").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-256")
            .digest(playlistId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val suffix = java.util.UUID.randomUUID().toString()
        val destination = File(directory, "$name-$suffix.img")
        val temp = File(directory, "$name-$suffix.tmp")
        try {
            val input = requireNotNull(app.contentResolver.openInputStream(source)) { "The selected image could not be opened." }
            input.use { stream ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 20L * 1024 * 1024) { "Images must be 20 MB or smaller." }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
        if (!temp.renameTo(destination)) {
            temp.delete()
            error("The selected image could not be saved.")
        }
        val current = read(app)
        val previous = current[playlistId]?.let { runCatching { Uri.parse(it).path?.let(::File) }.getOrNull() }
        val updated = JSONObject().apply {
            current.forEach { (id, uri) -> put(id, uri) }
            put(playlistId, Uri.fromFile(destination).toString())
        }
        if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_OVERRIDES, updated.toString()).commit()) {
            destination.delete()
            error("The selected image could not be saved.")
        }
        previous?.takeIf { it.parentFile == directory }?.delete()
        _version.value++
        return Uri.fromFile(destination).toString()
    }

    private fun read(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OVERRIDES, null)
            ?.let { raw -> runCatching {
                val json = JSONObject(raw)
                json.keys().asSequence().mapNotNull { key -> json.optString(key).takeIf(String::isNotBlank)?.let { key to it } }.toMap()
            }.getOrNull() }
            .orEmpty()
}
