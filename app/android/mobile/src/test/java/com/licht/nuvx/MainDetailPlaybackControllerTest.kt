package com.licht.nuvx

import com.licht.nuvx.data.model.BrowseType
import com.licht.nuvx.data.model.DetailPage
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.model.UiState
import com.licht.nuvx.playback.QueueSource
import com.licht.nuvx.ui.MainDetailPlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainDetailPlaybackControllerTest {

    private class Recorder {
        var invoked = false
        var refused = false
        var songs: List<Song> = emptyList()
        var index = -1
        var source: QueueSource? = null
        var bounded = false
        var token: String? = null
        var contextRequests = 0
    }

    private fun song(id: String, album: String? = null, setVideoId: String? = null) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        albumName = album,
        setVideoId = setVideoId,
    )

    private fun page(
        type: BrowseType,
        songs: List<Song>,
        continuation: String? = null,
        browseId: String = "page",
    ) = DetailPage(
        browseId = browseId,
        title = "Page Title",
        subtitle = "",
        thumbnailUrl = null,
        songs = UiState.Success(songs),
        type = type,
        continuation = continuation,
    )

    private fun controller(
        recorder: Recorder,
        latest: DetailPage? = null,
        locked: Boolean = false,
        casting: Boolean = false,
    ) = MainDetailPlaybackController(
        playFrom = { songs, index, source, bounded, token ->
            recorder.invoked = true
            recorder.songs = songs
            recorder.index = index
            recorder.source = source
            recorder.bounded = bounded
            recorder.token = token
        },
        takePlaybackContext = { browseId ->
            recorder.contextRequests++
            latest?.takeIf { it.browseId == browseId }
        },
        controlsLocked = { locked },
        casting = { casting },
        refusedByHost = { recorder.refused = true },
    )

    @Test
    fun `album playback fills missing album names from the page title`() {
        val recorder = Recorder()
        val page = page(BrowseType.ALBUM, listOf(song("a"), song("b", album = "Existing")))
        val songs = listOf(song("a"), song("b", album = "Existing"))

        controller(recorder).play(page, songs, 0)

        assertTrue(recorder.invoked)
        assertEquals(listOf("Page Title", "Existing"), recorder.songs.map { it.albumName })
        assertEquals("page", recorder.source?.id)
        assertTrue(recorder.bounded)
        assertEquals(0, recorder.index)
    }

    @Test
    fun `playlist playback remaps the selection after the latest context is applied`() {
        val recorder = Recorder()
        val ordered = listOf(
            song("a", setVideoId = "s1"),
            song("b", setVideoId = "s2"),
            song("c", setVideoId = "s3"),
        )
        val page = page(BrowseType.PLAYLIST, ordered, continuation = "older")
        val latest = page.copy(
            songs = UiState.Success(listOf(ordered[2], ordered[1], ordered[0])),
            continuation = "newer",
        )

        controller(recorder, latest = latest).play(page, ordered, 0)

        assertTrue(recorder.invoked)
        assertEquals(listOf("c", "b", "a"), recorder.songs.map { it.videoId })
        assertEquals(2, recorder.index)
        assertTrue(recorder.bounded)
        assertEquals("newer", recorder.token)
        assertEquals(1, recorder.contextRequests)
    }

    @Test
    fun `casting keeps the tapped list and avoids bounded context`() {
        val recorder = Recorder()
        val ordered = listOf(
            song("a", setVideoId = "s1"),
            song("b", setVideoId = "s2"),
        )
        val page = page(BrowseType.ALBUM, ordered, continuation = "older")

        controller(recorder, latest = page, casting = true).play(page, ordered, 1)

        assertTrue(recorder.invoked)
        assertFalse(recorder.bounded)
        assertEquals(listOf("a", "b"), recorder.songs.map { it.videoId })
        assertEquals(1, recorder.index)
        assertEquals("older", recorder.token)
        assertEquals(0, recorder.contextRequests)
    }

    @Test
    fun `locked party controls refuse the request without changing playback`() {
        val recorder = Recorder()
        val page = page(BrowseType.ALBUM, listOf(song("a")))
        val songs = listOf(song("a"))

        controller(recorder, locked = true).play(page, songs, 0)

        assertTrue(recorder.refused)
        assertFalse(recorder.invoked)
    }

    @Test
    fun `non album playback leaves album names untouched`() {
        val recorder = Recorder()
        val page = page(BrowseType.OTHER, listOf(song("a")))
        val songs = listOf(song("a"))

        controller(recorder).play(page, songs, 0)

        assertTrue(recorder.invoked)
        assertNull(recorder.songs.single().albumName)
        assertFalse(recorder.bounded)
    }

    @Test
    fun `empty playback list does nothing`() {
        val recorder = Recorder()
        val page = page(BrowseType.ALBUM, listOf(song("a")))

        controller(recorder).play(page, emptyList(), 0)

        assertFalse(recorder.invoked)
    }
}
