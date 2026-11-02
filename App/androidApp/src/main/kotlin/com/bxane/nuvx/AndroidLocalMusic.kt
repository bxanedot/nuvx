package com.bxane.nuvx

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.bxane.nuvx.core.LocalMusicLibrary
import com.bxane.nuvx.core.LocalMusicState
import com.bxane.nuvx.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AndroidLocalMusic(
    private val activity: ComponentActivity,
    private val launchPermission: (String) -> Unit,
) : LocalMusicLibrary, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LocalMusicState(actionLabel = "Allow audio access"))
    override val state = mutableState.asStateFlow()

    override fun open() = requestAccess()

    override fun requestAccess() {
        if (hasPermission()) scan() else launchPermission(permission())
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) scan() else mutableState.value = mutableState.value.copy(
            loading = false,
            error = "Audio access is needed to browse music stored on this device.",
            actionLabel = "Try again",
        )
    }

    private fun permission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission() = ContextCompat.checkSelfPermission(activity, permission()) == PackageManager.PERMISSION_GRANTED

    private fun scan() {
        mutableState.value = mutableState.value.copy(loading = true, error = null, actionLabel = "Refresh library")
        scope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { queryTracks() }
                mutableState.value = mutableState.value.copy(tracks = tracks, loading = false)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, error = "Couldn't read audio on this device.")
            }
        }
    }

    private fun queryTracks(): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        return activity.contentResolver.query(
            collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            buildList {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleColumn)?.takeIf(String::isNotBlank) ?: continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)).toString()
                    val id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
                    val artist = cursor.getString(artistColumn)?.takeUnless { it.isBlank() || it == "<unknown>" }
                    val album = cursor.getString(albumColumn)?.takeUnless { it.isBlank() || it == "<unknown>" }
                    add(Track("local:$id", title, listOfNotNull(artist), album,
                        cursor.getLong(durationColumn).takeIf { it > 0 }, localUri = uri))
                }
            }
        }.orEmpty()
    }

    override fun close() = scope.cancel()
}
