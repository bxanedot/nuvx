package com.bxane.nuvx.data.webdav

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

object WebDavUploads {

    sealed interface TrackState {
        data object Queued : TrackState
        data class Running(val fraction: Float) : TrackState
        data object Done : TrackState
        data class Failed(val reason: String) : TrackState
        data object Skipped : TrackState
    }

    enum class Choice { OVERWRITE, KEEP_BOTH, SKIP }

    data class Resolution(val choice: Choice, val applyToAll: Boolean)

    data class ConflictRequest(
        val song: Song,
        val fileName: String,
        val remaining: Int,
        val answer: CompletableDeferred<Resolution>,
    )

    data class Summary(val uploaded: Int, val skipped: Int, val failed: Int) {
        val total: Int get() = uploaded + skipped + failed
    }

    private val _active = MutableStateFlow<Map<String, TrackState>>(emptyMap())
    val active: StateFlow<Map<String, TrackState>> = _active.asStateFlow()

    private val _conflict = MutableStateFlow<ConflictRequest?>(null)
    val conflict: StateFlow<ConflictRequest?> = _conflict.asStateFlow()

    fun isUploadable(song: Song): Boolean {
        if (WebDavConfig.isWebDavId(song.videoId)) return false
        val uri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return uri.scheme == "content" || uri.scheme == "file"
    }

    suspend fun upload(context: Context, songs: List<Song>): Summary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val url = com.bxane.nuvx.data.settings.AppSettings.webdavUrl.value
        val username = com.bxane.nuvx.data.settings.AppSettings.webdavUsername.value
        val password = com.bxane.nuvx.data.settings.AppSettings.webdavPassword.value
        val root = WebDavConfig.normalizeUrl(url)
        require(WebDavConfig.isConfigured(root)) { "WebDAV is not configured" }

        val queue = songs.filter { isUploadable(it) }
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var standing: Resolution? = null
        val taken = HashSet<String>()

        if (queue.isEmpty()) {
            WebDavUploadNotifications.cancel(appContext)
            return@withContext Summary(0, 0, 0)
        }
        setAll(queue.map { it.videoId }, null)
        WebDavUploadNotifications.postProgress(appContext, null, 0, queue.size, null)
        for ((index, song) in queue.withIndex()) {
            set(song.videoId, TrackState.Queued)
            val remaining = queue.size - index - 1
            WebDavUploadNotifications.postProgress(appContext, song, 1, remaining, null)
            runCatching {
                uploadOne(appContext, song, root, username, password, taken, remaining) { req ->
                    standing?.let { return@uploadOne it }
                    _conflict.value = req
                    try {
                        req.answer.await().also { if (it.applyToAll) standing = it }
                    } finally {
                        _conflict.value = null
                    }
                }
            }.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        Outcome.Uploaded -> {
                            set(song.videoId, TrackState.Done)
                            uploaded++
                        }
                        Outcome.Skipped -> {
                            set(song.videoId, TrackState.Skipped)
                            skipped++
                        }
                    }
                },
                onFailure = {
                    set(song.videoId, TrackState.Failed(it.message ?: "Upload failed"))
                    failed++
                },
            )
        }
        val summary = Summary(uploaded, skipped, failed)
        WebDavUploadNotifications.postDone(appContext, summary)
        summary
    }

    private enum class Outcome { Uploaded, Skipped }

    private suspend fun uploadOne(
        context: Context,
        song: Song,
        root: String,
        username: String,
        password: String,
        taken: MutableSet<String>,
        remaining: Int,
        resolve: suspend (ConflictRequest) -> Resolution,
    ): Outcome {
        val ext = extensionFor(context, song)
        val sendable = prepareUpload(context, song, ext)
        try {
            return sendBytes(context, song, sendable.source, ext, root, username, password, taken, remaining, resolve)
        } finally {
            sendable.discard()
        }
    }

    private data class Sendable(val source: UploadSource, val discard: () -> Unit)

    private suspend fun prepareUpload(context: Context, song: Song, ext: String): Sendable {
        val direct = { source: UploadSource -> Sendable(source) {} }
        val original = openSource(context, song) ?: throw WebDavException("Could not read this file")
        if (!com.bxane.nuvx.download.MediaTagger.carriesTags(ext)) return direct(original)
        if (hasEmbeddedPicture(context, song)) return direct(original)
        if (com.bxane.nuvx.download.Downloads.verifiedSavedUri(song.videoId) == null) return direct(original)
        val artwork = runCatching {
            com.bxane.nuvx.download.MediaTagger.artworkFor(context, song)
        }.getOrNull() ?: return direct(original)
        val staged = runCatching {
            val tmp = File.createTempFile("webdav-upload", ".tmp", context.cacheDir)
            original.stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            com.bxane.nuvx.download.MediaTagger.embed(
                context,
                Uri.fromFile(tmp),
                song,
                ext,
                artwork = artwork,
            )
            tmp
        }.getOrNull() ?: return direct(openSource(context, song) ?: throw WebDavException("Could not read this file"))
        val stream = runCatching { staged.inputStream() }.getOrNull()
            ?: run { staged.delete(); return direct(openSource(context, song) ?: throw WebDavException("Could not read this file")) }
        return Sendable(
            UploadSource(stream, staged.length().takeIf { it > 0 } ?: -1),
        ) {
            runCatching { stream.close() }
            staged.delete()
        }
    }

    private fun hasEmbeddedPicture(context: Context, song: Song): Boolean {
        val uri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        val retriever = android.media.MediaMetadataRetriever()
        return runCatching {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.embeddedPicture != null
        }.getOrDefault(false).also { runCatching { retriever.release() } }
    }

    private suspend fun sendBytes(
        context: Context,
        song: Song,
        source: UploadSource,
        ext: String,
        root: String,
        username: String,
        password: String,
        taken: MutableSet<String>,
        remaining: Int,
        resolve: suspend (ConflictRequest) -> Resolution,
    ): Outcome {
        val base = WebDavClient.uploadFileName(song.title, song.artist, ext)
            .removeSuffix(".$ext")
        var fileName = WebDavClient.resolveNumberedName(base, ext, taken)
        taken.add(fileName.lowercase(Locale.ROOT))

        var target = WebDavClient.joinUrl(root, fileName)
        var overwrite = false
        val alreadyThere = WebDavClient.exists(target, username, password).getOrThrow()
        if (alreadyThere) {
            when (resolve(ConflictRequest(song, fileName, remaining, CompletableDeferred())).choice) {
                Choice.SKIP -> return Outcome.Skipped
                Choice.OVERWRITE -> overwrite = true
                Choice.KEEP_BOTH -> {
                    var attempts = 0
                    while (attempts++ < 25) {
                        taken.add(fileName.lowercase(Locale.ROOT))
                        fileName = WebDavClient.resolveNumberedName(base, ext, taken)
                        target = WebDavClient.joinUrl(root, fileName)
                        if (!WebDavClient.exists(target, username, password).getOrThrow()) break
                    }
                    taken.add(fileName.lowercase(Locale.ROOT))
                }
            }
        }
        val (stream, length) = source
        var lastPostedPercent = -1
        when (
            WebDavClient.putFile(
                fileUrl = target,
                stream = stream,
                contentLength = length,
                mimeType = mimeFor(ext),
                username = username,
                password = password,
                overwrite = overwrite,
                onProgress = { written ->
                    if (length <= 0) return@putFile
                    val fraction = (written.toFloat() / length).coerceIn(0f, 1f)
                    set(song.videoId, TrackState.Running(fraction))
                    val percent = (fraction * 100).toInt()
                    if (percent != lastPostedPercent) {
                        lastPostedPercent = percent
                        WebDavUploadNotifications.postProgress(context, song, 1, remaining, fraction)
                    }
                },
            ).getOrThrow()
        ) {
            WebDavClient.PutResult.AlreadyExists -> return Outcome.Skipped
            WebDavClient.PutResult.Uploaded -> return Outcome.Uploaded
        }
    }

    private data class UploadSource(val stream: InputStream, val length: Long)

    private fun openSource(context: Context, song: Song): UploadSource? {
        val uri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        return runCatching {
            when (uri.scheme) {
                "file" -> {
                    val file = File(uri.path ?: return null)
                    if (!file.isFile || !file.canRead()) return null
                    UploadSource(file.inputStream(), file.length().takeIf { it > 0 } ?: -1)
                }
                "content" -> {
                    val resolver = context.contentResolver
                    val stream = resolver.openInputStream(uri) ?: return null
                    UploadSource(stream, querySize(resolver, uri))
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val size = cursor.getLong(0)
                if (size > 0) return size
            }
        }
        return -1
    }

    internal fun extensionFor(context: Context, song: Song): String {
        val fromPath = song.localPath
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in WebDavConfig.audioExtensions }
        if (fromPath != null) return fromPath
        val displayName = song.localUri
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "content" }
            ?.let { uri ->
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }
        return displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in WebDavConfig.audioExtensions }
            ?: "mp3"
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    private fun set(videoId: String, state: TrackState?) {
        _active.value = _active.value.let { current ->
            if (state == null) current - videoId else current + (videoId to state)
        }
    }

    private fun setAll(videoIds: List<String>, state: TrackState?) {
        _active.value = _active.value.let { current ->
            var next = current
            for (id in videoIds) next = if (state == null) next - id else next + (id to state)
            next
        }
    }

    class WebDavException(message: String) : Exception(message)
}
