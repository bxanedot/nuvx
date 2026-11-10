package com.bxane.nuvx

import com.bxane.nuvx.data.LikeState
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.YtMusicRepository.SongPage
import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LikedMusicSyncTest {

    private fun song(id: String) = Song(videoId = id, title = "t", artist = "a", thumbnailUrl = null)

    private fun songs(n: Int, offset: Int): List<Song> =
        List(n) { song("v${offset + it}") }

    @After
    fun tearDown() {
        LikeState.clear()
    }

    @Test
    fun `liked sync follows continuations past the old ten page cap`() = runBlocking {
        LikeState.clear()
        val pageCount = 12
        val perPage = 100
        val pages = LinkedHashMap<String, SongPage>()
        for (p in 0 until pageCount) {
            val token = "page-$p"
            val next = if (p == pageCount - 1) null else "page-${p + 1}"
            pages[token] = SongPage(songs = songs(perPage, p * perPage), continuation = next)
        }

        YtMusicRepository.syncLikedMusic("page-0") { pages[it] }

        val liked = LikeState.overrides.value
        assertEquals(pageCount * perPage, liked.size)
        assertEquals(pageCount * perPage, liked.values.count { it == LikeStatus.LIKE })
        assertTrue(liked.containsKey("v${pageCount * perPage - 1}"))
    }

    @Test
    fun `liked sync stops when a continuation points back at a page already read`() = runBlocking {
        LikeState.clear()
        val pages = LinkedHashMap<String, SongPage>()
        pages["t0"] = SongPage(songs = songs(3, 0), continuation = "t1")
        pages["t1"] = SongPage(songs = songs(3, 3), continuation = "t0")
        var calls = 0

        val job = launch {
            YtMusicRepository.syncLikedMusic("t0") { calls++; pages[it] }
        }
        withTimeout(1000) { job.join() }

        assertEquals(2, calls)
        assertEquals(setOf("v0", "v1", "v2", "v3", "v4", "v5"), LikeState.overrides.value.keys)
    }

    @Test
    fun `liked sync stops when the continuation runs dry`() = runBlocking {
        LikeState.clear()
        val pages = LinkedHashMap<String, SongPage>()
        pages["t0"] = SongPage(songs = songs(3, 0), continuation = "t1")
        pages["t1"] = SongPage(songs = songs(3, 3), continuation = null)
        var calls = 0

        YtMusicRepository.syncLikedMusic("t0") {
            calls++
            pages[it]
        }

        assertEquals(2, calls)
        assertEquals(setOf("v0", "v1", "v2", "v3", "v4", "v5"), LikeState.overrides.value.keys)
    }

    @Test
    fun `liked sync is a no-op with no continuation`() = runBlocking {
        LikeState.clear()
        var calls = 0
        YtMusicRepository.syncLikedMusic(null) { calls++; error("never called") }
        assertEquals(0, calls)
        assertTrue(LikeState.overrides.value.isEmpty())
    }

    @Test
    fun `first page is seeded while background sync is still in flight`() = runBlocking {
        LikeState.clear()
        val gate = CompletableDeferred<SongPage?>()

        val background = launch { YtMusicRepository.syncLikedMusic("t0") { gate.await() } }
        try {
            yield()
            val firstPageIds = setOf("v0", "v1", "v2")
            LikeState.seedLiked(firstPageIds)
            assertEquals(firstPageIds, LikeState.overrides.value.keys)
        } finally {
            if (!gate.isCompleted) gate.complete(SongPage(songs = songs(3, 3), continuation = null))
        }
        background.join()
        assertEquals(setOf("v0", "v1", "v2", "v3", "v4", "v5"), LikeState.overrides.value.keys)
    }

    @Test
    fun `a failed continuation stops the sync without losing earlier ids`() = runBlocking {
        LikeState.clear()
        val pages = LinkedHashMap<String, SongPage>()
        pages["t0"] = SongPage(songs = songs(3, 0), continuation = "t1")

        YtMusicRepository.syncLikedMusic("t0") {
            if (it == "t1") null else pages[it]
        }

        assertEquals(setOf("v0", "v1", "v2"), LikeState.overrides.value.keys)
    }

    @Test
    fun `cancelling the sync stops further pagination`() = runBlocking {
        LikeState.clear()
        val pages = LinkedHashMap<String, SongPage>()
        pages["t0"] = SongPage(songs = songs(3, 0), continuation = "t1")
        val reachedSecond = Channel<Unit>(1)

        val job = launch {
            YtMusicRepository.syncLikedMusic("t0") {
                if (it == "t1") {
                    reachedSecond.trySend(Unit)
                    yield()
                    SongPage(songs = songs(3, 3), continuation = "t2")
                } else pages[it]
            }
        }
        reachedSecond.receive()
        job.cancel()
        job.join()

        assertEquals(setOf("v0", "v1", "v2"), LikeState.overrides.value.keys)
    }

    @Test
    fun `a row without a like button states no rating`() {
        val json = """
        {"contents":[{"playlistPanelVideoRenderer":{
          "videoId":"abc123",
          "menu":{"menuRenderer":{"items":[]}}
        }}]}
        """.trimIndent()

        val menu = InnertubeParser.parseSongMenu(Json.parseToJsonElement(json), "abc123")

        assertNull(menu?.likeStatus)
    }

    @Test
    fun `an existing like survives a menu that says nothing`() {
        LikeState.clear()
        LikeState.set("abc123", LikeStatus.LIKE)

        InnertubeParser.parseSongMenu(
            Json.parseToJsonElement(
                """{"contents":[{"playlistPanelVideoRenderer":{"videoId":"abc123"}}]}""",
            ),
            "abc123",
        )?.let { LikeState.rememberStated("abc123", it.likeStatus) }

        assertEquals(LikeStatus.LIKE, LikeState.overrides.value["abc123"])
    }

    @Test
    fun `a stated indifferent rating does not downgrade an existing like`() {
        LikeState.clear()
        LikeState.set("abc123", LikeStatus.LIKE)

        LikeState.rememberStated("abc123", LikeStatus.INDIFFERENT)

        assertEquals(LikeStatus.LIKE, LikeState.overrides.value["abc123"])
    }
}
