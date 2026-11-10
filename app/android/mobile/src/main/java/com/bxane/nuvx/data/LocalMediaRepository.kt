package com.bxane.nuvx.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.bxane.nuvx.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.download.DownloadStore
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object LocalMediaRepository {

    private const val TAG = "nuvx"
    private const val MIN_LOCAL_MUSIC_DURATION_MS = 30_000L

    private val localMusicExtensions = setOf(
        "mp3", "m4a", "mp4", "flac", "ogg", "opus", "aac", "webm", "dsf", "dff",
    )

    private val nonMusicPathSegments = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/podcasts/",
        "/audiobooks/",
        "/recordings/",
        "/voice recorder/",
        "/sound_recorder/",
        "/call_rec/",
        "/whatsapp voice notes/",
    )

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val appDownloads = Downloads.getDownloadedSongs(context)
        val knownUris = appDownloads.mapNotNull { it.localUri }.toSet()
        val extraSongs = mutableListOf<Song>()

        val scanned = mutableMapOf<String, ScannedTags>()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                )
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${DownloadStore.FOLDER}%")

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val albumId = cursor.getLong(albumIdCol)
                        val tags = ScannedTags(
                            albumName = cursor.getString(albumCol).cleanTag(),
                            artworkUrl = if (albumId > 0) {
                                ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                            } else {
                                null
                            },
                            dateAddedSeconds = cursor.getLong(dateAddedCol),
                            dateModifiedSeconds = cursor.getLong(dateModifiedCol),
                        )
                        scanned[contentUri] = tags
                        if (contentUri !in knownUris && isAudioFileName(name)) {
                            extraSongs.add(buildSongFromUri(context, contentUri, name, tags))
                        }
                    }
                }
            } else {
                val folder = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC,
                    ),
                    DownloadStore.FOLDER,
                )
                if (folder.exists() && folder.isDirectory) {
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile && isAudioFileName(file.name)) {
                            val uriStr = Uri.fromFile(file).toString()
                            if (uriStr !in knownUris) {
                                val modified = file.lastModified().takeIf { it > 0 }?.div(1_000)
                                extraSongs.add(
                                    buildSongFromUri(
                                        context,
                                        uriStr,
                                        file.name,
                                        ScannedTags(null, null, modified, modified),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning Music/nuvx directory: ${it.message}") }

        val filled = appDownloads.map { song ->
            val uri = song.localUri ?: return@map song
            val tags = scanned[uri] ?: return@map song
            song.copy(
                albumName = song.albumName ?: tags.albumName,
                localDateAddedSeconds = tags.dateAddedSeconds,
                localDateModifiedSeconds = tags.dateModifiedSeconds,
            )
        }

        (filled + extraSongs).distinctBy { it.localUri ?: it.videoId }
    }

    private class ScannedTags(
        val albumName: String?,
        val artworkUrl: String?,
        val dateAddedSeconds: Long?,
        val dateModifiedSeconds: Long?,
    )

    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it == "<unknown>" }

    @Volatile
    var extraLocalMusic: (suspend (Context, List<Song>) -> List<Song>)? = null

    suspend fun getLocalMusic(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) {
            return@withContext extraLocalMusic?.invoke(context, emptyList()) ?: emptyList()
        }

        val songs = mutableListOf<Song>()
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }

        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val selection = if (filterNonMusic) {
            buildString {
                append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
                append(
                    " AND (${MediaStore.Audio.Media.DURATION} >= ?" +
                        " OR ${MediaStore.Audio.Media.DURATION} <= 0" +
                        " OR ${MediaStore.Audio.Media.DURATION} IS NULL)",
                )
                append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    append(" AND ${MediaStore.Audio.Media.IS_PODCAST} = 0")
                }
            }
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }
        val selectionArgs = if (filterNonMusic) {
            arrayOf(MIN_LOCAL_MUSIC_DURATION_MS.toString())
        } else {
            null
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                AUDIO_PROJECTION,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val columns = AudioColumns(cursor)
                val treeUri = AppSettings.localMusicFolderUri.value
                val chosen = if (treeUri.isBlank()) null else selectedFolder(treeUri)
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(columns.displayName).orEmpty()
                    val durationMs = cursor.getLong(columns.duration)
                    val path = cursor.getString(columns.data)

                    if (filterNonMusic && !isEligibleLocalMusic(durationMs, displayName, path)) continue
                    if (treeUri.isNotBlank() && !isInFolder(path, chosen)) continue

                    songs.add(columns.song(cursor, videoIdByUri))
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning device local music: ${it.message}") }

        extraLocalMusic?.invoke(context, songs) ?: songs
    }

    private val AUDIO_PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.TRACK,
    )

    private class AudioColumns(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val dateModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val track = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)

        fun song(cursor: Cursor, videoIdByUri: Map<String, String>): Song {
            val rowId = cursor.getLong(id)
            val name = cursor.getString(displayName).orEmpty()
            val artId = cursor.getLong(albumId)
            val durationMs = cursor.getLong(duration)
            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, rowId).toString()
            val songTitle = cursor.getString(title).cleanTag()?.removeAudioExtension()
                ?: name.substringBeforeLast('.').takeIf { it.isNotBlank() }
                ?: "Track $rowId"
            val trackNumber = if (track >= 0 && !cursor.isNull(track)) {
                trackNumberFromMediaStore(cursor.getInt(track))
            } else {
                null
            }
            return Song(
                videoId = videoIdByUri[contentUri] ?: contentUri,
                title = songTitle,
                artist = cursor.getString(artist).cleanTag() ?: "Unknown Artist",
                thumbnailUrl = if (artId > 0) ContentUris.withAppendedId(ALBUM_ART_URI, artId).toString() else null,
                durationText = durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
                albumName = cursor.getString(album).cleanTag(),
                localUri = contentUri,
                localPath = cursor.getString(data),
                localDateAddedSeconds = cursor.getLong(dateAdded),
                localDateModifiedSeconds = cursor.getLong(dateModified),
                trackNumber = trackNumber,
            )
        }
    }

    private val ALBUM_ART_URI: Uri by lazy { Uri.parse("content://media/external/audio/albumart") }

    suspend fun present(context: Context, songs: List<Song>): List<Song> = withContext(Dispatchers.IO) {
        val mediaIds = songs.mapNotNull { it.localUri?.mediaStoreAudioId() }
        val existing = HashSet<Long>()
        val canAsk = mediaIds.isNotEmpty() && runCatching {
            mediaIds.distinct().chunked(500).forEach { chunk ->
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",")})",
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) existing += cursor.getLong(0)
                }
            }
        }.isSuccess
        val trustMissing = canAsk && hasStoragePermission(context)
        songs.filter { song ->
            val uri = song.localUri ?: return@filter false
            val id = uri.mediaStoreAudioId()
            when {
                id != null -> id in existing || !trustMissing
                uri.startsWith("file://") -> Uri.parse(uri).path?.let { File(it).exists() } != false
                else -> true
            }
        }
    }

    private fun String.mediaStoreAudioId(): Long? {
        if (!startsWith("content://media/")) return null
        val uri = Uri.parse(this)
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[segments.size - 2] != "media" || "audio" !in segments) return null
        return segments.last().toLongOrNull()
    }

    suspend fun songFromUri(context: Context, uri: Uri): Song = withContext(Dispatchers.IO) {
        mediaStoreRow(context, uri)?.let { return@withContext it }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
        buildSongFromUri(context, uri.toString(), name)
    }

    private fun mediaStoreRow(context: Context, uri: Uri): Song? = runCatching {
        val resolver = context.contentResolver
        val (target, selection, args) = when {
            uri.scheme == "file" -> {
                val path = uri.path ?: return@runCatching null
                Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Audio.Media.DATA} = ?", arrayOf(path))
            }
            uri.authority == MediaStore.AUTHORITY -> Triple(uri, null, null)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DocumentsContract.isDocumentUri(context, uri) ->
                Triple(MediaStore.getMediaUri(context, uri) ?: return@runCatching null, null, null)
            else -> return@runCatching null
        }
        resolver.query(target, AUDIO_PROJECTION, selection, args, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val videoIdByUri = Downloads.saved.value.entries.associate { (id, saved) -> saved to id }
            AudioColumns(cursor).song(cursor, videoIdByUri)
        }
    }.getOrNull()

    fun selectedFolderLabel(treeUri: String): String? = selectedFolder(treeUri)?.label

    internal fun isInSelectedFolder(path: String?, treeUri: String): Boolean {
        if (treeUri.isBlank()) return true
        return isInFolder(path, selectedFolder(treeUri))
    }

    private fun isInFolder(path: String?, folder: SelectedFolder?): Boolean {
        if (folder == null) return false
        val candidate = path?.normalizedPath() ?: return false
        return candidate == folder.absolutePath || candidate.startsWith("${folder.absolutePath}/")
    }

    private data class SelectedFolder(val absolutePath: String, val label: String)

    private fun selectedFolder(treeUri: String): SelectedFolder? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        if (documentId.startsWith("raw:")) {
            val path = documentId.removePrefix("raw:").normalizedPath()
            return@runCatching SelectedFolder(path, path.substringAfterLast('/'))
        }
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        SelectedFolder(
            absolutePath = listOf(volumeRoot, relative).filter { it.isNotBlank() }.joinToString("/").normalizedPath(),
            label = relative.ifBlank { volume },
        )
    }.getOrNull()

    private fun String.normalizedPath(): String =
        replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

    internal fun isEligibleLocalMusic(durationMs: Long, displayName: String, path: String?): Boolean {
        if (durationMs in 1 until MIN_LOCAL_MUSIC_DURATION_MS) return false

        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: displayName
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false

        val normalizedPath = path
            ?.replace('\\', '/')
            ?.lowercase(Locale.ROOT)
            ?: return true
        return nonMusicPathSegments.none(normalizedPath::contains)
    }

    private fun String.removeAudioExtension(): String {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in localMusicExtensions || extension == "wav") {
            substringBeforeLast('.').ifBlank { this }
        } else {
            this
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
            lower.endsWith(".flac") || lower.endsWith(".wav") ||
            lower.endsWith(".ogg") || lower.endsWith(".opus") ||
            lower.endsWith(".aac") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp")
    }

    private fun buildSongFromUri(
        context: Context,
        uriStr: String,
        fileName: String,
        scanned: ScannedTags? = null,
    ): Song {
        var title = fileName.substringBeforeLast(".")
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null
        var trackNumber: Int? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uriStr))
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val metaTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)

                if (!metaTitle.isNullOrBlank()) title = metaTitle
                if (!metaArtist.isNullOrBlank()) artist = metaArtist
                albumName = metaAlbum.cleanTag()
                if (metaDur != null && metaDur > 0) durationText = formatDuration(metaDur)
                trackNumber = trackNumberFromTag(metaTrack)
            } finally {
                retriever.release()
            }
        }

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = scanned?.artworkUrl,
            durationText = durationText,
            albumName = albumName ?: scanned?.albumName,
            localUri = uriStr,
            localDateAddedSeconds = scanned?.dateAddedSeconds,
            localDateModifiedSeconds = scanned?.dateModifiedSeconds,
            trackNumber = trackNumber,
        )
    }

    internal fun trackNumberFromMediaStore(raw: Int?): Int? {
        if (raw == null || raw <= 0) return null
        val track = if (raw >= 1000) raw % 1000 else raw
        return track.takeIf { it in 1..999 }
    }

    internal fun trackNumberFromTag(raw: String?): Int? {
        val head = raw?.substringBefore('/')?.trim() ?: return null
        return head.toIntOrNull()?.takeIf { it in 1..999 }
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
