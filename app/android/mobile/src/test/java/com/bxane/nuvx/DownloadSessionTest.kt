package com.bxane.nuvx

import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.download.DownloadProgress
import com.bxane.nuvx.download.DownloadSession
import com.bxane.nuvx.download.DownloadTarget
import com.bxane.nuvx.download.Downloads
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadSessionTest {

    private fun song(id: String, title: String = id, artist: String = "Artist") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    private fun onDisk(id: String, album: String? = null) = song(id).copy(
        albumName = album,
        localUri = "content://media/external/audio/media/$id",
    )

    @Before
    fun reset() = clearState()

    @After
    fun tearDown() = clearState()

    private fun clearState() {
        DownloadSession.clear()
        Downloads.collections.value.keys.toList().forEach(Downloads::forgetCollection)
    }

    @Test
    fun `nothing asked for means no indicator at all`() {
        assertFalse(DownloadSession.state.value.visible)
    }

    @Test
    fun `an indicator survives the batch finishing, and goes on being seen`() {
        DownloadSession.queued(song("a"))
        assertTrue(DownloadSession.state.value.visible)

        DownloadSession.done("a")
        assertTrue(DownloadSession.state.value.visible)
        assertFalse(DownloadSession.state.value.busy)

        DownloadSession.markSeen()
        assertFalse(DownloadSession.state.value.visible)
    }

    @Test
    fun `looking in halfway through does not sign the batch off`() {
        DownloadSession.queued(song("a"))
        DownloadSession.queued(song("b"))
        DownloadSession.done("a")

        DownloadSession.markSeen()
        assertTrue(DownloadSession.state.value.visible)

        DownloadSession.done("b")
        assertTrue(DownloadSession.state.value.visible)

        DownloadSession.markSeen()
        assertFalse(DownloadSession.state.value.visible)
    }

    @Test
    fun `a new batch after a seen one brings the indicator back`() {
        DownloadSession.queued(song("a"))
        DownloadSession.done("a")
        DownloadSession.markSeen()
        assertFalse(DownloadSession.state.value.visible)

        DownloadSession.queued(song("b"))
        assertTrue(DownloadSession.state.value.visible)
    }

    @Test
    fun `a failure is something to be told about, not something to hide`() {
        DownloadSession.queued(song("a"))
        DownloadSession.failed("a", "Download failed — check your connection")

        val state = DownloadSession.state.value
        assertTrue(state.visible)
        assertFalse(state.busy)
        assertEquals(1, state.failed)
    }

    @Test
    fun `cancelling the only download leaves nothing to report`() {
        DownloadSession.queued(song("a"))
        DownloadSession.forget("a")
        assertFalse(DownloadSession.state.value.visible)
    }

    @Test
    fun `re-asking for a failed track replaces its row rather than adding one`() {
        DownloadSession.queued(song("a"))
        DownloadSession.failed("a", "nope")
        DownloadSession.queued(song("a"))

        val state = DownloadSession.state.value
        assertEquals(1, state.items.size)
        assertEquals(DownloadProgress.Queued, state.items.single().progress)
        assertEquals(0, state.failed)
    }

    @Test
    fun `progress counts settled tracks whole, however they settled`() {
        DownloadSession.queued(song("a"))
        DownloadSession.queued(song("b"))
        DownloadSession.queued(song("c"))
        DownloadSession.queued(song("d"))

        assertEquals(0f, DownloadSession.state.value.fraction, 0.001f)

        DownloadSession.done("a")
        DownloadSession.failed("b", "nope")
        DownloadSession.running("c", 0.5f)

        assertEquals(0.625f, DownloadSession.state.value.fraction, 0.001f)
    }

    @Test
    fun `the catalogue swap corrects the row rather than adding another`() {
        DownloadSession.queued(song("vid", title = "Kesariya (Official Video)"))
        DownloadSession.retitle("vid", song("audio", title = "Kesariya"))

        val item = DownloadSession.state.value.items.single()
        assertEquals("vid", item.videoId)
        assertEquals("Kesariya", item.song.title)
    }

    @Test
    fun `a playlist is grouped by what was tapped, not by any tag on its tracks`() {
        val target = DownloadTarget(
            id = "VLPL123",
            title = "Late night drive",
            thumbnailUrl = "https://example/cover.jpg",
            playlist = true,
        )
        val tracks = listOf(onDisk("a", album = "One"), onDisk("b"), onDisk("c"))
        Downloads.rememberCollection(target, tracks)

        val found = Downloads.collectionsAmong(tracks).single()
        assertEquals("Late night drive", found.title)
        assertTrue(found.playlist)
        assertEquals("https://example/cover.jpg", found.thumbnailUrl)
        assertEquals(listOf("a", "b", "c"), found.songs.map { it.videoId })
    }

    @Test
    fun `a release is only drawn for the files that are still there`() {
        val target = DownloadTarget(id = "MPREb1", title = "Motion", subtitle = "Calvin Harris")
        Downloads.rememberCollection(target, listOf(onDisk("a"), onDisk("b"), onDisk("c")))

        val survivors = listOf(onDisk("a"), onDisk("c"))
        val found = Downloads.collectionsAmong(survivors).single()
        assertEquals(listOf("a", "c"), found.songs.map { it.videoId })

        assertTrue(Downloads.collectionsAmong(listOf(onDisk("z"))).isEmpty())
    }

    @Test
    fun `re-downloading a release merges into the entry already there`() {
        val target = DownloadTarget(id = "MPREb1", title = "Motion")
        Downloads.rememberCollection(target, listOf(onDisk("a"), onDisk("b")))
        Downloads.rememberCollection(target, listOf(onDisk("a"), onDisk("b"), onDisk("c")))

        val all = listOf(onDisk("a"), onDisk("b"), onDisk("c"))
        val found = Downloads.collectionsAmong(all)
        assertEquals(1, found.size)
        assertEquals(listOf("a", "b", "c"), found.single().songs.map { it.videoId })
    }

    @Test
    fun `a cached collection cover is retained when the release is downloaded again`() {
        val target = DownloadTarget(
            id = "VLPL1",
            title = "Late night drive",
            thumbnailUrl = "https://example/cover.jpg",
            playlist = true,
        )
        Downloads.rememberCollection(target, listOf(onDisk("a")))
        Downloads.rememberCollectionArtwork("VLPL1", "file:///data/user/0/app/files/download-artwork/cover.jpg")
        Downloads.rememberCollection(target, listOf(onDisk("a"), onDisk("b")))

        assertEquals(
            "file:///data/user/0/app/files/download-artwork/cover.jpg",
            Downloads.collections.value.getValue("VLPL1").thumbnailUrl,
        )
    }

    @Test
    fun `an empty ask records nothing`() {
        Downloads.rememberCollection(DownloadTarget(id = "MPREb1", title = "Motion"), emptyList())
        assertTrue(Downloads.collections.value.isEmpty())
    }

    private fun onDiskMap(vararg ids: String) =
        ids.associateWith { "content://media/external/audio/media/$it" }

    @Test
    fun `playlists and albums downloaded whole both get a card`() {
        Downloads.rememberCollection(
            DownloadTarget(id = "VLPL1", title = "Late night drive", playlist = true),
            listOf(onDisk("a"), onDisk("b")),
        )
        Downloads.rememberCollection(
            DownloadTarget(id = "MPREb1", title = "Motion", playlist = false),
            listOf(onDisk("c")),
        )

        val shelf = Downloads.savedReleases(onDiskMap("a", "b", "c"))
        assertEquals(listOf("Late night drive", "Motion"), shelf.map { it.title })
    }

    @Test
    fun `a playlist with nothing downloaded yet is not on the device`() {
        Downloads.rememberCollection(
            DownloadTarget(id = "VLPL1", title = "Late night drive", playlist = true),
            listOf(onDisk("a"), onDisk("b")),
        )
        assertTrue(Downloads.savedReleases(emptyMap()).isEmpty())

        assertEquals(1, Downloads.savedReleases(onDiskMap("b")).size)
    }

    @Test
    fun `a playlist whose last file is gone loses its card`() {
        Downloads.rememberCollection(
            DownloadTarget(id = "VLPL1", title = "Late night drive", playlist = true),
            listOf(onDisk("a"), onDisk("b")),
        )
        assertEquals(1, Downloads.savedReleases(onDiskMap("a")).size)
        assertTrue(Downloads.savedReleases(onDiskMap("unrelated")).isEmpty())
    }

    @Test
    fun `cards are in name order, whatever order they were downloaded in`() {
        listOf("Zephyr", "anthems", "Morning").forEach { title ->
            Downloads.rememberCollection(
                DownloadTarget(id = "VL$title", title = title, playlist = true),
                listOf(onDisk(title)),
            )
        }
        assertEquals(
            listOf("anthems", "Morning", "Zephyr"),
            Downloads.savedReleases(onDiskMap("Zephyr", "anthems", "Morning")).map { it.title },
        )
    }

    @Test
    fun `a playlist page id round-trips, and the device folders are not one`() {
        assertEquals("VLPL1", Downloads.recordIdOf(Downloads.pageIdFor("VLPL1")))
        assertNull(Downloads.recordIdOf("local:downloads"))
        assertNull(Downloads.recordIdOf("local:all"))
        assertNull(Downloads.recordIdOf("VLPL1"))
        assertNull(Downloads.recordIdOf(Downloads.PLAYLIST_PREFIX))
    }
}
