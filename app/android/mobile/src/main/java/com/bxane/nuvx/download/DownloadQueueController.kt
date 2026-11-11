package com.bxane.nuvx.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.LinkedHashMap

internal class DownloadQueueController(
    private val active: MutableStateFlow<Map<String, DownloadState>>,
    private val downloadsAllowedNow: () -> Boolean,
    private val servableFromCache: (String) -> Boolean,
    private val run: suspend (Context, Song) -> Unit,
    private val clear: (String) -> Unit,
    private val fail: (String, String) -> Unit,
) {
    private val pending = LinkedHashMap<String, Song>()
    private val lock = Any()
    private val running = LinkedHashMap<String, Job?>()

    fun enqueue(context: Context, song: Song, from: String?) {
        val id = song.videoId
        if (!downloadsAllowedNow() && !servableFromCache(id)) {
            val inFlight = active.value[id]
            if (inFlight !is DownloadState.Queued && inFlight !is DownloadState.Running) {
                DownloadSession.queued(song, from)
                fail(id, Downloads.WIFI_ONLY_REFUSAL)
            }
            return
        }
        synchronized(lock) {
            if (id in pending || id in running) return
            pending[id] = song
        }
        active.update { it + (id to DownloadState.Queued) }
        DownloadSession.queued(song, from)

        val app = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        }.onFailure {
            Log.w(TAG, "could not start the download service: ${it.message}")
            synchronized(lock) { pending.remove(id) }
            fail(id, "Downloads can't start right now")
        }
    }

    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            if (videoId !in running) return@synchronized null
            running.remove(videoId)
        }
        job?.cancel()
        clear(videoId)
        DownloadSession.forget(videoId)
    }

    suspend fun downloadNow(context: Context, songs: List<Song>, from: String?): Set<String> {
        val handled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val claimed = synchronized(lock) {
            songs.filter { song ->
                val id = song.videoId
                if (id in pending || id in running) {
                    handled += id
                    false
                } else {
                    running[id] = null
                    true
                }
            }
        }
        claimed.forEach { song ->
            active.update { it + (song.videoId to DownloadState.Queued) }
            DownloadSession.queued(song, from)
        }
        val slots = Semaphore(BATCH_WORKERS)
        try {
            coroutineScope {
                claimed.forEach { song ->
                    launch {
                        slots.withPermit {
                            val id = song.videoId
                            if (synchronized(lock) { id !in running }) {
                                handled += id
                                return@withPermit
                            }
                            if (!downloadsAllowedNow()) return@withPermit
                            val job = launch { run(context, song) }
                            onRunning(id, job)
                            job.join()
                            val calledOff = synchronized(lock) { id !in running }
                            if (calledOff || id in Downloads.saved.value) handled += id
                        }
                    }
                }
            }
        } finally {
            claimed.forEach { song ->
                val id = song.videoId
                onIdle(id)
                val state = active.value[id]
                if (state is DownloadState.Queued || state is DownloadState.Running) {
                    clear(id)
                    DownloadSession.forget(id)
                }
            }
        }
        return handled
    }

    fun takeNext(): Song? = synchronized(lock) {
        val entry = pending.entries.firstOrNull() ?: return null
        pending.remove(entry.key)
        running[entry.key] = null
        entry.value
    }

    fun onRunning(videoId: String, job: Job) {
        val cancelled = synchronized(lock) {
            if (videoId !in running) return@synchronized true
            running[videoId] = job
            false
        }
        if (cancelled) job.cancel()
    }

    fun onIdle(videoId: String) {
        synchronized(lock) { running.remove(videoId) }
    }

    fun busy(): Boolean = synchronized(lock) { pending.isNotEmpty() || running.isNotEmpty() }

    fun onStopped() {
        synchronized(lock) { running.clear() }
    }

    private companion object {
        const val TAG = "nuvx"
        const val BATCH_WORKERS = 4
    }
}
