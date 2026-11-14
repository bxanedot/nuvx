package com.bxane.nuvx.ui

import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.PlaybackSourceType
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.playback.QueueSource
import com.bxane.nuvx.playback.contextEntryKey

internal class MainDetailPlaybackController(
    private val playFrom: (List<Song>, Int, QueueSource, Boolean, String?) -> Unit,
    private val takePlaybackContext: (String) -> DetailPage?,
    private val controlsLocked: () -> Boolean,
    private val casting: () -> Boolean,
    private val refusedByHost: () -> Unit,
) {
    fun play(page: DetailPage, songs: List<Song>, index: Int) {
        if (controlsLocked()) {
            refusedByHost()
            return
        }
        val source = QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId)
        val selected = songs.getOrNull(index)
        val pageSongKeys = (page.songs as? UiState.Success)?.data.orEmpty().mapTo(HashSet()) { it.contextEntryKey() }
        val useBounded =
            (page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST) &&
                selected?.contextEntryKey()?.let(pageSongKeys::contains) == true &&
                !casting()
        val latestPage = if (useBounded) takePlaybackContext(page.browseId) else null
        val latestSongs = latestPage
            ?.let { (it.songs as? UiState.Success)?.data.orEmpty() }
            ?.map { albumSong(page, it) }
            ?.takeIf { it.isNotEmpty() }
        val queue = latestSongs ?: songs.map { albumSong(page, it) }
        if (queue.isEmpty()) return
        val queueIndex = selected?.let { tapped ->
            queue.indexOfFirst { it.contextEntryKey() == tapped.contextEntryKey() }
        }?.takeIf { it >= 0 } ?: index
        playFrom(
            queue,
            queueIndex.coerceIn(queue.indices),
            source,
            useBounded,
            latestPage?.continuation ?: page.continuation,
        )
    }

    fun albumSong(page: DetailPage, song: Song): Song =
        if (page.type == BrowseType.ALBUM) song.copy(albumName = song.albumName ?: page.title) else song
}
