package com.bxane.nuvx.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.durationMillis
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.download.DownloadStore
import com.bxane.nuvx.download.DownloadTarget
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.download.MediaTagger
import com.bxane.nuvx.playback.AudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

internal class MainDownloadController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModel: MainViewModel,
    private val showQueueNotice: (String) -> Unit,
    private val currentSong: () -> Song?,
    private val currentDurationMs: () -> Long,
    private val pending: () -> List<Pair<List<Song>, DownloadTarget?>>,
    private val setPending: (List<Pair<List<Song>, DownloadTarget?>>) -> Unit,
    private val requestNotifications: () -> Unit,
    private val requestStorage: () -> Unit,
    private val showAddonExclusion: (Pair<String, SourceRegistry.DownloadExclusion>) -> Unit,
) {
    fun startDownloads(batches: List<Pair<List<Song>, DownloadTarget?>>) {
        val saved = Downloads.saved.value
        val todo = batches.map { (requested, from) ->
            requested
                .filter { it.videoId !in saved }
                .map { song ->
                    val cover = from?.thumbnailUrl
                    if (song.thumbnailUrl.isNullOrBlank() && !cover.isNullOrBlank()) {
                        song.copy(thumbnailUrl = cover)
                    } else {
                        song
                    }
                } to from
        }
        val songs = todo.flatMap { it.first }.distinctBy { it.videoId }
        val blocked = songs.isNotEmpty() && !AppSettings.downloadsAllowedNow &&
            songs.none { Downloads.servableFromCache(it.videoId) }
        if (songs.isNotEmpty() && !blocked) {
            val needsStorage = AppSettings.exportDownloads.value && DownloadStore.needsLegacyPermission() &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifications()
            }

            if (needsStorage) {
                val currentPending = pending()
                val ask = currentPending.isEmpty()
                setPending(currentPending + todo.filter { it.first.isNotEmpty() })
                if (ask) requestStorage()
            } else {
                todo.forEach { (batch, from) ->
                    batch.forEach { Downloads.enqueue(context, it, from?.title) }
                    if (from != null) Downloads.markRequested(from.id, batch.map { it.videoId })
                }
            }
            if (!songs.all { Downloads.servableFromCache(it.videoId) }) {
                SourceRegistry.addonsLeftOutOfDownloads()
                    .firstOrNull { (config, why) -> explainedDownloadExclusions.add("${config.id}:$why") }
                    ?.let { (config, why) -> showAddonExclusion(config.displayName to why) }
            }
        }
        if (!blocked) batches.forEach { (requested, from) ->
            if (from == null) return@forEach
            Downloads.rememberCollection(from, requested)
            if (!from.thumbnailUrl.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) {
                    MediaTagger.cacheArtwork(context.applicationContext, from.thumbnailUrl)
                        ?.let { Downloads.rememberCollectionArtwork(from.id, it) }
                }
            }
        }
        when {
            blocked -> Toast.makeText(
                context,
                context.getString(R.string.wifi_only_download_refusal),
                Toast.LENGTH_LONG,
            ).show()
            batches.sumOf { it.first.size } > 1 -> {
                val message = if (songs.isEmpty()) {
                    context.getString(R.string.already_downloaded)
                } else {
                    context.resources.getQuantityString(
                        R.plurals.downloading_song_count,
                        songs.size,
                        songs.size,
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startDownload(requested: List<Song>, from: DownloadTarget?) {
        startDownloads(listOf(requested to from))
    }

    fun downloadCatalog(page: DetailPage) {
        viewModel.collectCatalog(page) { releases ->
            if (releases.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.couldnt_load_tracks), Toast.LENGTH_SHORT).show()
            } else {
                val all = releases.flatMap { it.second }
                Downloads.markRequested(page.browseId, all.map { it.videoId })
                val albums = releases.map { (release, songs) ->
                    songs to DownloadTarget(
                        id = release.browseId!!,
                        title = release.title,
                        subtitle = release.subtitle,
                        thumbnailUrl = release.thumbnailUrl,
                    )
                }
                val artist = DownloadTarget(
                    id = page.browseId,
                    title = page.title,
                    subtitle = page.subtitle,
                    thumbnailUrl = page.thumbnailUrl,
                    artist = true,
                )
                startDownloads(albums + (all to artist))
            }
        }
    }

    fun downloadFromCache(songs: List<Song>) {
        val cleaned = songs.map { it.copy(downloadFormat = null, localDateAddedSeconds = null) }
        Downloads.preferCachedCopy(cleaned.map { it.videoId })
        startDownload(cleaned, null)
    }

    fun removeFromCache(songs: List<Song>, message: String) {
        scope.launch {
            val removed = songs.sumOf { AudioCache.remove(it.videoId) }
            if (removed > 0) showQueueNotice(message)
        }
    }

    fun downloadSong(song: Song) {
        val withDuration = if (song.durationMillis() <= 0L &&
            currentSong()?.videoId == song.videoId && currentDurationMs() > 0L
        ) {
            song.copy(durationText = formatDurationText(currentDurationMs()))
        } else {
            song
        }
        startDownload(listOf(withDuration), null)
    }

    private fun formatDurationText(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }

    private companion object {
        val explainedDownloadExclusions = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }
}
