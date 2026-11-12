package com.bxane.nuvx.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.settings.AppSettings
import androidx.annotation.RequiresApi
import com.bxane.nuvx.data.model.Song
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

object DownloadStore {

    private const val TAG = "nuvx"

    const val FOLDER = "nuvx"

    private val relativePath = "${Environment.DIRECTORY_MUSIC}/$FOLDER"

    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun fileNameFor(song: Song, extension: String): String {
        val id = sanitise(song.videoId).ifEmpty { "track" }
        val artist = sanitise(song.artist)
        val title = sanitise(song.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { id }
        val uniqueStem = if (id.length == YOUTUBE_VIDEO_ID_LENGTH && stem != id) {
            "${stem.take((MAX_STEM_CHARS - id.length - 1).coerceAtLeast(0)).trimEnd()}-$id"
        } else stem
        return "$uniqueStem.$extension"
    }

    private fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')

    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")

    private const val MAX_STEM_CHARS = 120
    private const val YOUTUBE_VIDEO_ID_LENGTH = 11

    class Storable(val extension: String, val mimeType: String)

    fun storable(codec: String?): Storable? = when (codec?.lowercase(Locale.ROOT)?.trim()) {
        "flac", "x-flac" -> Storable("flac", "audio/flac")
        "wav", "x-wav", "wave" -> Storable("wav", "audio/x-wav")
        "alac", "m4a", "mp4", "eac3-joc", "ec3-joc", "dolby-atmos" -> Storable("m4a", "audio/mp4")
        else -> null
    }

    fun existing(context: Context, name: String): Uri? =
        if (!AppSettings.exportDownloads.value) {
            privateFile(context, name).takeIf { it.exists() }?.let(Uri::fromFile)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreEntry(context, name)
        } else {
            legacyFile(name).takeIf { it.exists() }?.let(Uri::fromFile)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreEntry(context: Context, name: String): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND (" +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)",
            arrayOf(name, relativePath, "$relativePath/", "$relativePath/%"),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(cursor.getLong(0).toString())
                .build()
        }
    }.onFailure { Log.w(TAG, "media store lookup failed for $name: ${it.message}") }.getOrNull()

    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                if (file.name == "playlist.m3u8" && file.parentFile?.parentFile?.name == "offline-hls") {
                    file.parentFile?.deleteRecursively() == true
                } else file.delete()
            } == true
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    }.onFailure { Log.w(TAG, "could not delete $uri: ${it.message}") }.getOrDefault(false)

    class Pending internal constructor(
        private val context: Context,
        val uri: Uri,
        val name: String,
        private val part: File?,
        private val target: File?,
    ) {
        val tagUri: Uri get() = part?.let(Uri::fromFile) ?: uri

        fun openStream(): OutputStream =
            part?.outputStream()
                ?: context.contentResolver.openOutputStream(uri)
                ?: error("Could not open $name for writing")

        fun commit(): Uri {
            if (part != null && target != null) {
                if (!part.renameTo(target)) error("Could not finish writing $name")
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(target.absolutePath),
                    null,
                    null,
                )
                return Uri.fromFile(target)
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        }

        fun abort() {
            part?.delete()
            if (part == null) runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    fun begin(context: Context, name: String, mimeType: String): Pending {
        if (!AppSettings.exportDownloads.value) {
            val target = privateFile(context, name)
            val folder = target.parentFile ?: error("No download folder")
            if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
            val part = newPartFile(folder, name)
            return Pending(context, Uri.fromFile(target), name, part = part, target = target)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching {
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrElse { cause ->
                Log.w(TAG, "the media store refused $mimeType for $name: ${cause.message}")
                error("Android won't store ${name.substringAfterLast('.', mimeType)} files in Music")
            } ?: error("Could not create $name in Music")
            return Pending(context, uri, name, part = null, target = null)
        }

        val target = legacyFile(name)
        val folder = target.parentFile ?: error("No Music folder on this device")
        if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
        val part = newPartFile(folder, name)
        return Pending(context, Uri.fromFile(target), name, part = part, target = target)
    }

    private fun newPartFile(folder: File, name: String): File =
        File(folder, ".$name.${UUID.randomUUID()}.part").also {
            if (!it.createNewFile()) error("Could not reserve a temporary file for $name")
        }

    @Suppress("DEPRECATION")
    private fun legacyFile(name: String) = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER),
        name,
    )

    private fun privateFile(context: Context, name: String) =
        File(File(context.filesDir, "downloads"), name)
}
