package com.bxane.nuvx.desktop

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
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import javax.swing.JFileChooser

internal class DesktopLocalMusic : LocalMusicLibrary, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(LocalMusicState(actionLabel = "Choose a music folder"))
    override val state = mutableState.asStateFlow()

    override fun open() = Unit

    override fun requestAccess() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose a music folder"
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) scan(chooser.selectedFile)
    }

    private fun scan(directory: File) {
        mutableState.value = mutableState.value.copy(loading = true, error = null, actionLabel = "Choose a different folder")
        scope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    scanLocalMusic(directory)
                }
                mutableState.value = mutableState.value.copy(tracks = tracks, loading = false)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, error = "Couldn't read audio from that folder.")
            }
        }
    }

    override fun close() = scope.cancel()

}

internal fun scanLocalMusic(directory: File): List<Track> = Files.walk(directory.toPath()).use { paths ->
    val files = paths.filter {
        Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.toString().substringAfterLast('.', "").lowercase() in audioExtensions
    }.iterator()
    buildList {
        while (files.hasNext()) {
            val file = files.next().toFile()
            val uri = file.toURI().toString()
            val id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
            add(Track("local:$id", file.nameWithoutExtension, localUri = uri))
        }
    }.sortedBy { it.title.lowercase() }
}

private val audioExtensions = setOf("aac", "aiff", "alac", "flac", "m4a", "mp3", "ogg", "opus", "wav", "wma")
