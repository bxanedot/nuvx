// SPDX-License-Identifier: AGPL-3.0-or-later

package com.bxane.nuvx.data

import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object YtMusicHistoryRepository {
    private class HistoryMemo(val cookie: String?, val at: Long, val songs: List<Song>)

    private val historyLock = Mutex()

    @Volatile
    private var historyMemo: HistoryMemo? = null

    suspend fun homeRecentlyPlayed(): Result<HomeShelf?> = YtMusicRepository.call("home:recent") {
        recentlyPlayed()
    }

    private suspend fun recentlyPlayed(): HomeShelf? {
        val ytSongs = if (Innertube.cookie != null) {
            runCatching { fetchHistory() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val allSongs = ytSongs.distinctBy { it.videoId }.take(RECENT_LIMIT)
        if (allSongs.isEmpty()) return null
        return HomeShelf(
            title = YtMusicRepository.RECENT_TITLE,
            items = allSongs.map {
                ShelfItem(
                    title = it.title,
                    subtitle = it.artist,
                    thumbnailUrl = it.thumbnailUrl,
                    videoId = it.videoId,
                    browseId = null,
                )
            },
        )
    }

    private suspend fun fetchHistory(): List<Song> = historyLock.withLock {
        val cookie = Innertube.cookie
        historyMemo?.takeIf { it.cookie == cookie && System.currentTimeMillis() - it.at < HISTORY_SHARE_MS }
            ?.let { return@withLock it.songs }
        InnertubeParser.collectSongsDeep(Innertube.browse(HISTORY)).distinctBy { it.videoId }
            .also { historyMemo = HistoryMemo(cookie, System.currentTimeMillis(), it) }
    }

    suspend fun history(): Result<List<Song>> = YtMusicRepository.call("history") { fetchHistory() }

    suspend fun recents(): Result<List<Song>> = YtMusicRepository.call("recents") {
        if (Innertube.cookie != null) {
            val history = runCatching { fetchHistory() }.getOrDefault(emptyList())
            if (history.isNotEmpty()) return@call history
        }
        emptyList()
    }

    suspend fun removeFromHistory(token: String): Result<Unit> = YtMusicRepository.call("removeFromHistory") {
        Innertube.sendFeedback(token)
    }

    fun invalidateHistoryCache() {
        historyMemo = null
    }

    suspend fun recentHistory(): Result<List<Song>> = YtMusicRepository.call("history:recent") {
        if (Innertube.cookie == null) return@call emptyList()
        val memo = historyMemo
        if (memo != null && memo.cookie == Innertube.cookie &&
            System.currentTimeMillis() - memo.at < HISTORY_MEMO_MS
        ) {
            memo.songs
        } else {
            fetchHistory()
        }
    }

    private const val HISTORY = "FEmusic_history"
    private const val RECENT_LIMIT = 20
    private const val HISTORY_SHARE_MS = 5_000L
    private const val HISTORY_MEMO_MS = 120_000L
}
