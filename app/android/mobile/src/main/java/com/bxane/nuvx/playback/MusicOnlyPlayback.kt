package com.bxane.nuvx.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

object MusicOnlyPlayback {

    private const val TAG = "nuvx"

    const val ANSWER_TIMEOUT_MS = 15_000L

    private const val HOLD_LIMIT_MS = ANSWER_TIMEOUT_MS + 5_000L

    private val kept = MutableStateFlow<Set<String>>(emptySet())

    private val settled = MutableStateFlow<Set<String>>(emptySet())

    fun isActive(): Boolean =
        AppSettings.preferMusicOnly.value && !ListenTogether.state.value.inParty

    fun showsPicture(videoId: String): Boolean = !isActive() || videoId in kept.value

    fun holds(uri: Uri): Boolean {
        if (uri.scheme != "nuvx" || uri.authority != "watch") return false
        if (uri.getQueryParameter("m") != "1") return false
        if (uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1") return false
        val videoId = mediaIdIn(uri) ?: return false
        return isActive() && videoId !in kept.value && videoId !in settled.value
    }

    fun wants(item: MediaItem): Boolean = item.localConfiguration?.uri?.let(::holds) == true

    fun settle(videoId: String) = settled.update { it + videoId }

    fun forgetExcept(videoIds: Set<String>) = settled.update { it intersect videoIds }

    fun keep(videoId: String) = kept.update { it + videoId }

    fun letGo(videoId: String) = kept.update { it - videoId }

    fun reset() {
        kept.value = emptySet()
        settled.value = emptySet()
    }

    private suspend fun awaitAnswer(videoId: String) {
        withTimeoutOrNull(HOLD_LIMIT_MS) {
            combine(
                AppSettings.preferMusicOnly,
                ListenTogether.state,
                kept,
                settled,
            ) { on, party, keptNow, settledNow ->
                !on || party.inParty || videoId in keptNow || videoId in settledNow
            }.first { it }
        }
    }

    @OptIn(UnstableApi::class)
    fun holdingFactory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val inner = upstream.createDataSource()
        object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) =
                inner.addTransferListener(transferListener)

            override fun open(dataSpec: DataSpec): Long {
                if (holds(dataSpec.uri)) {
                    mediaIdIn(dataSpec.uri)?.let { videoId ->
                        TrackLog.d(TAG, "holding music video for its audio version", about = videoId)
                        runBlocking { awaitAnswer(videoId) }
                    }
                }
                return inner.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                inner.read(buffer, offset, length)

            override fun getUri(): Uri? = inner.uri

            override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

            override fun close() = inner.close()
        }
    }
}

fun Song.asMusicOnlyOf(video: Song): Song = copy(
    isVideoOrigin = false,
    queueTier = video.queueTier,
    queueEntryId = video.queueEntryId,
    radioName = video.radioName,
    playbackSource = video.playbackSource,
    playbackSourceType = video.playbackSourceType,
    playbackSourceId = video.playbackSourceId,
)
