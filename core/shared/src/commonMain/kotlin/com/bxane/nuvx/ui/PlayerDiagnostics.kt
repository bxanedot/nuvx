package com.bxane.nuvx.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.core.AudioQualityPreference
import com.bxane.nuvx.core.BackendHealth
import com.bxane.nuvx.core.BackendId
import com.bxane.nuvx.core.PlayerState
import com.bxane.nuvx.core.RepeatMode
import com.bxane.nuvx.core.VideoQualityPreference

internal data class PlaybackDiagnosticLine(val label: String, val value: String)

internal fun playbackDiagnostics(
    selected: BackendId,
    available: Set<BackendId>,
    fallback: Boolean,
    audioQuality: AudioQualityPreference,
    videoQuality: VideoQualityPreference,
    health: Map<BackendId, BackendHealth>,
    playerState: PlayerState?,
): List<PlaybackDiagnosticLine> = buildList {
    val state = playerState
    val current = state?.current
    add(PlaybackDiagnosticLine("Playback", when {
        state == null -> "Unavailable"
        state.error != null -> "Playback issue"
        state.buffering -> "Buffering"
        state.isPlaying -> "Playing"
        current == null -> "Stopped"
        else -> "Paused"
    }))
    state?.nativePlaybackState?.let { add(PlaybackDiagnosticLine("Native player state", it)) }
    add(PlaybackDiagnosticLine("Current track", current?.let {
        listOf(it.title, it.artistLine).filter(String::isNotBlank).joinToString(" · ")
    }?.ifBlank { null } ?: "None"))
    val queueSize = state?.queue?.size ?: 0
    add(PlaybackDiagnosticLine("Queue", "$queueSize ${if (queueSize == 1) "track" else "tracks"}"))
    add(PlaybackDiagnosticLine("Position", state?.let {
        if (it.durationMs > 0) "${formatDuration(it.positionMs)} / ${formatDuration(it.durationMs)}" else formatDuration(it.positionMs)
    } ?: "Unavailable"))
    add(PlaybackDiagnosticLine("Repeat", when (state?.repeat) {
        RepeatMode.ALL -> "All"
        RepeatMode.ONE -> "One"
        RepeatMode.OFF -> "Off"
        null -> "Unavailable"
    }))
    add(PlaybackDiagnosticLine("Selected engine", selected.label))
    add(PlaybackDiagnosticLine("Automatic fallback", if (fallback) "On" else "Off"))
    add(PlaybackDiagnosticLine("Audio quality", audioQuality.label))
    val format = state?.audioFormat
    add(PlaybackDiagnosticLine("Audio codec", safeCodec(format?.codecs)))
    add(PlaybackDiagnosticLine("Audio format", safeMimeType(format?.mimeType)))
    add(PlaybackDiagnosticLine("Audio bitrate", format?.bitrateKbps?.takeIf { it > 0 }?.let { "$it kbps" } ?: "Not reported"))
    add(PlaybackDiagnosticLine("Sample rate", format?.sampleRateHz?.takeIf { it > 0 }?.let { "$it Hz" } ?: "Not reported"))
    add(PlaybackDiagnosticLine("Current video resolution", state?.videoResolution?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width} × ${it.height}" } ?: "Not reported"))
    add(PlaybackDiagnosticLine("Video quality", videoQuality.label))
    available.sortedBy { it.ordinal }.forEach { backend ->
        val result = health[backend]
        add(PlaybackDiagnosticLine(backend.label, result?.let {
            "${if (it.ok) "Working" else "Unavailable"} · ${safeOperation(it.operation)} · ${it.latencyMs.coerceAtLeast(0)} ms"
        } ?: "Not used yet this session"))
    }
}

private val AUDIO_MIME_TYPE = Regex("audio/[A-Za-z0-9][A-Za-z0-9.+-]{0,31}", RegexOption.IGNORE_CASE)
private val AUDIO_CODEC = Regex("[A-Za-z0-9][A-Za-z0-9 .,+_-]{0,79}")

private fun safeMimeType(value: String?): String = value?.substringBefore(';')?.trim()
    ?.takeIf(AUDIO_MIME_TYPE::matches) ?: "Not reported"

private fun safeCodec(value: String?): String = value?.trim()
    ?.takeIf(AUDIO_CODEC::matches) ?: "Not reported"

private fun safeOperation(operation: String): String = when (operation) {
    "search" -> "Search"
    "album" -> "Album"
    "artist" -> "Artist"
    "radio" -> "Radio"
    "stream" -> "Stream"
    else -> "Request"
}

private fun formatDuration(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    val minutes = seconds / 60 % 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$remainder" else "${seconds / 60}:$remainder"
}

@Composable
internal fun PlaybackDiagnosticsDialog(lines: List<PlaybackDiagnosticLine>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Player diagnostics", color = NuvxColors.Text) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                lines.forEach { line ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(line.label, color = NuvxColors.Secondary, fontSize = 12.sp)
                        Text(line.value, color = NuvxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = NuvxColors.Accent) } },
        containerColor = NuvxColors.Card,
    )
}
