package com.licht.nuvx.playback

import com.licht.nuvx.FakeSharedPreferences
import com.licht.nuvx.data.model.ArtistRef
import com.licht.nuvx.data.model.PlaybackSourceType
import com.licht.nuvx.data.model.QueueTier
import com.licht.nuvx.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BoundedContextPersistenceTest {

    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        BoundedContextPersistence.initForTesting(prefs)
    }

    @Test
    fun `round trip preserves song metadata source continuation and planned window`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(4) { index ->
            song("track-$index").copy(
                queueTier = QueueTier.CONTEXT,
                artistId = "artist-id",
                artists = listOf(ArtistRef("Artist", "artist-id")),
                albumId = "album-id",
                albumName = "Album",
                isVideo = index == 3,
                isVideoOrigin = index == 3,
                setVideoId = "set-$index",
                radioName = "Station",
                localUri = "content://$index",
                downloadFormat = "FLAC",
                localPath = "/music/$index",
                localDateAddedSeconds = 10L,
                localDateModifiedSeconds = 20L,
                sourceQuality = "LOSSLESS",
                isExplicit = true,
                playbackSource = source.title,
                playbackSourceType = source.type,
                playbackSourceId = source.id,
            )
        }
        val window = QueueWindow(4, shuffleSeed = 1234L).startAt(2, shuffle = true)
        window.upcoming(25, repeatAll = true)
        val active = window.snapshot()
        val entries = listOf(
            BoundedContextPersistence.Entry(canonical[2].copy(queueEntryId = "current"), 2, 0L),
            BoundedContextPersistence.Entry(canonical[3].copy(queueEntryId = "context-next"), 3, 0L),
            BoundedContextPersistence.Entry(
                song("user").copy(queueTier = QueueTier.USER_QUEUE, queueEntryId = "user"),
            ),
        )
        val state = BoundedContextPersistence.State(
            context = context(canonical, source, ContinuationState.IN_FLIGHT, "page-2"),
            source = source,
            window = active,
            entries = entries,
            currentIndex = 0,
            positionMs = 8_765L,
        )

        val raw = requireNotNull(BoundedContextPersistence.encode(state))
        val restored = requireNotNull(BoundedContextPersistence.decode(raw))
        assertEquals(state.source, restored.source)
        assertEquals(state.window, restored.window)
        assertEquals(state.context.copy(version = 0L), restored.context)
        assertEquals(state.entries, restored.entries)
        assertEquals(8_765L, restored.positionMs)
    }

    @Test
    fun `round trip preserves every continuation state and token shape`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(2) { song("track-$it") }
        val window = QueueWindow(2).startAt(0).snapshot()
        listOf(
            ContinuationState.AVAILABLE to "available-token",
            ContinuationState.IN_FLIGHT to "in-flight-token",
            ContinuationState.EXHAUSTED to null,
            ContinuationState.BLOCKED to "blocked-token",
        ).forEach { (continuationState, token) ->
            val restored = BoundedContextPersistence.decode(
                requireNotNull(
                    BoundedContextPersistence.encode(
                        state(
                            canonical,
                            source,
                            window,
                            entries = listOf(entry("current", QueueTier.CONTEXT, 0, 0L)),
                            currentIndex = 0,
                            sourceSnapshot = context(canonical, source, continuationState, token),
                        ),
                    ),
                ),
            )
            assertEquals(continuationState, restored?.context?.continuationState)
            assertEquals(token, restored?.context?.continuationToken)
        }
    }

    @Test
    fun `projection keeps bounded history ids and arranges all tails by tier`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(8) { song("track-$it") }
        val window = QueueWindow(8).startAt(3, shuffle = false)
        val upcoming = window.upcoming(25)
        val history = listOf(
            entry("old-1", QueueTier.CONTEXT, 0, 0L),
            entry("old-2", QueueTier.CONTEXT, 1, 0L),
            entry("old-3", QueueTier.CONTEXT, 2, 0L),
        )
        val state = state(
            canonical = canonical,
            source = source,
            window = window.snapshot(),
            entries = history + listOf(
                entry("current", QueueTier.CONTEXT, 3, 0L),
                entry("user-1", QueueTier.USER_QUEUE),
                entry("context-4", QueueTier.CONTEXT, 4, 0L),
                entry("auto-1", QueueTier.AUTOPLAY),
                entry("user-2", QueueTier.USER_QUEUE),
                entry("auto-2", QueueTier.AUTOPLAY),
            ),
            currentIndex = history.size,
            sourceSnapshot = context(canonical, source),
        )

        val projected = requireNotNull(BoundedContextPersistence.restoreProjection(state))
        assertEquals(listOf("track-0", "track-1", "track-2", "track-3", "user-1-song", "user-2-song", "track-4", "track-5", "track-6", "track-7", "auto-1-song", "auto-2-song"), projected.entries.map { it.song.videoId })
        assertEquals(listOf("old-1", "old-2", "old-3"), projected.entries.take(3).map { it.song.queueEntryId })
        assertEquals("context-4", projected.entries[6].song.queueEntryId)
        assertEquals(history.size, projected.currentIndex)
        assertEquals(upcoming.map { it.canonicalIndex }, projected.entries.drop(6).take(4).map { it.canonicalIndex })
    }

    @Test
    fun `repeat-all restore does not expose next cycle before active cycle ends`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(4) { song("track-$it") }
        val window = QueueWindow(4).startAt(1)
        val state = state(
            canonical = canonical,
            source = source,
            window = window.snapshot(),
            entries = listOf(
                entry("current", QueueTier.CONTEXT, 1, 0L),
                entry("context-2", QueueTier.CONTEXT, 2, 0L),
            ),
            currentIndex = 0,
            sourceSnapshot = context(canonical, source),
        )

        val projected = requireNotNull(
            BoundedContextPersistence.restoreProjection(state, repeatAll = true),
        )

        assertTrue(
            projected.entries.drop(1).all {
                it.canonicalIndex == null || it.cycleId == 0L
            },
        )
    }

    @Test
    fun `unrelated context history without anchors is retained but never mapped`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(3) { song("track-$it") }
        val window = QueueWindow(3).startAt(1)
        val state = state(
            canonical,
            source,
            window.snapshot(),
            entries = listOf(
                BoundedContextPersistence.Entry(song("from-old-queue").copy(queueTier = QueueTier.CONTEXT, queueEntryId = "old")),
                entry("current", QueueTier.CONTEXT, 1, 0L),
            ),
            currentIndex = 1,
            sourceSnapshot = context(canonical, source),
        )

        val projected = requireNotNull(BoundedContextPersistence.restoreProjection(state))
        assertEquals("from-old-queue", projected.entries.first().song.videoId)
        assertNull(projected.entries.first().canonicalIndex)
        assertNull(projected.entries.first().cycleId)
    }

    @Test
    fun `user and autoplay current rows do not break context cursor validation`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(5) { song("track-$it") }
        val window = QueueWindow(5).startAt(1)
        val entries = listOf(
            entry("history", QueueTier.CONTEXT, 1, 0L),
            entry("current-user", QueueTier.USER_QUEUE),
            entry("context-tail", QueueTier.CONTEXT, 2, 0L),
            entry("current-auto-tail", QueueTier.AUTOPLAY),
        )
        val userCurrent = state(
            canonical = canonical,
            source = source,
            window = window.snapshot(),
            entries = entries,
            currentIndex = 1,
            sourceSnapshot = context(canonical, source),
        )
        val userPlan = requireNotNull(BoundedContextPersistence.restoreProjection(userCurrent))
        assertEquals(listOf("track-1", "current-user-song", "track-2", "track-3", "track-4", "current-auto-tail-song"), userPlan.entries.map { it.song.videoId })

        val autoCurrent = userCurrent.copy(currentIndex = 3)
        val autoPlan = requireNotNull(BoundedContextPersistence.restoreProjection(autoCurrent))
        assertEquals(3, autoPlan.currentIndex)
        assertEquals("current-auto-tail", autoPlan.entries[autoPlan.currentIndex].song.queueEntryId)
    }

    @Test
    fun `history from a completed logical cycle remains restorable`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(3) { song("track-$it") }
        val window = QueueWindow(3).startAt(0)
        window.advanceAutomatically(repeatAll = true)
        window.advanceAutomatically(repeatAll = true)
        val current = requireNotNull(window.advanceAutomatically(repeatAll = true))
        assertEquals(1L, current.cycleId)

        val state = state(
            canonical = canonical,
            source = source,
            window = window.snapshot(),
            entries = listOf(
                entry("previous-1", QueueTier.CONTEXT, 1, 0L),
                entry("previous-2", QueueTier.CONTEXT, 2, 0L),
                entry("current", QueueTier.CONTEXT, current.canonicalIndex, current.cycleId),
            ),
            currentIndex = 2,
            sourceSnapshot = context(canonical, source),
        )

        assertNotNull(BoundedContextPersistence.encode(state))
        assertNotNull(BoundedContextPersistence.restoreProjection(state))
    }

    @Test
    fun `shuffling on mid-context keeps played history saveable and restorable`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(5) { song("track-$it") }
        val window = QueueWindow(5).startAt(2, shuffle = false).setShuffle(true, shuffleSeed = 7L)
        val upcoming = window.upcoming(25)
        assertTrue(upcoming.any { it.canonicalIndex in 0..1 })
        val state = state(
            canonical = canonical,
            source = source,
            window = window.snapshot(),
            entries = listOf(
                entry("old-0", QueueTier.CONTEXT, 0, 0L),
                entry("old-1", QueueTier.CONTEXT, 1, 0L),
                entry("current", QueueTier.CONTEXT, 2, 0L),
            ) + upcoming.map { entry("next-${it.canonicalIndex}", QueueTier.CONTEXT, it.canonicalIndex, it.cycleId) },
            currentIndex = 2,
            sourceSnapshot = context(canonical, source),
        )

        assertNotNull(BoundedContextPersistence.encode(state))
        val projected = requireNotNull(BoundedContextPersistence.restoreProjection(state))
        assertEquals(upcoming.map { it.canonicalIndex }, projected.entries.drop(3).map { it.canonicalIndex })
    }

    @Test
    fun `invalid anchors duplicate ids schema and malformed json are rejected`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(3) { song("track-$it") }
        val window = QueueWindow(3).startAt(1)
        val valid = state(
            canonical,
            source,
            window.snapshot(),
            entries = listOf(entry("current", QueueTier.CONTEXT, 1, 0L)),
            currentIndex = 0,
            sourceSnapshot = context(canonical, source),
        )
        assertNull(BoundedContextPersistence.encode(valid.copy(entries = listOf(
            entry("current", QueueTier.CONTEXT, 2, 0L),
        ))))
        assertNull(BoundedContextPersistence.encode(valid.copy(entries = listOf(
            entry("current", QueueTier.CONTEXT, 1, 0L),
            entry("current", QueueTier.USER_QUEUE),
        ))))
        assertNull(BoundedContextPersistence.decode("{\"schemaVersion\":999}"))
        assertNull(BoundedContextPersistence.decode("{\"schemaVersion\":1"))
    }

    @Test
    fun `progress sampling only updates matching persisted entry and clear removes scalars`() {
        val source = QueueSource("Release", PlaybackSourceType.BROWSE, "release-1")
        val canonical = List(2) { song("track-$it") }
        val window = QueueWindow(2).startAt(0)
        val state = state(
            canonical,
            source,
            window.snapshot(),
            entries = listOf(entry("current", QueueTier.CONTEXT, 0, 0L)),
            currentIndex = 0,
            positionMs = 1_000L,
            sourceSnapshot = context(canonical, source),
        )

        BoundedContextPersistence.saveImmediately(state)
        BoundedContextPersistence.savePlaybackState("stale", 99_000L)
        assertEquals(1_000L, requireNotNull(BoundedContextPersistence.load()).positionMs)
        BoundedContextPersistence.savePlaybackState("current", 2_000L)
        assertEquals(2_000L, requireNotNull(BoundedContextPersistence.load()).positionMs)
        BoundedContextPersistence.clearImmediately()
        assertTrue(prefs.getAll().isEmpty())
        assertNull(BoundedContextPersistence.load())
    }

    @Test
    fun `projection remains bounded for large canonical contexts`() {
        listOf(100, 1_000, 10_000).forEach { size ->
            val source = QueueSource("Large", PlaybackSourceType.BROWSE, "large")
            val canonical = List(size) { song("track-$it") }
            val window = QueueWindow(size, shuffleSeed = 55L).startAt(size / 2, shuffle = true)
            val logical = window.upcoming(25)
            val state = state(
                canonical,
                source,
                window.snapshot(),
                entries = listOf(entry("current", QueueTier.CONTEXT, size / 2, 0L)) +
                    logical.mapIndexed { index, item ->
                        entry("context-$index", QueueTier.CONTEXT, item.canonicalIndex, item.cycleId)
                    } + listOf(
                    entry("user", QueueTier.USER_QUEUE),
                    entry("auto", QueueTier.AUTOPLAY),
                ),
                currentIndex = 0,
                sourceSnapshot = context(canonical, source),
            )
            val projected = requireNotNull(BoundedContextPersistence.restoreProjection(state))
            assertTrue(projected.entries.size <= 1 + 25 + 2)
            assertEquals("current", projected.entries.first().song.queueEntryId)
            assertEquals(size / 2, projected.entries.first().canonicalIndex)
            assertEquals(logical.map { it.canonicalIndex }, projected.entries.drop(2).take(25).map { it.canonicalIndex })
        }
    }

    private fun context(
        songs: List<Song>,
        source: QueueSource,
        continuationState: ContinuationState = ContinuationState.EXHAUSTED,
        token: String? = null,
    ) = ContextSnapshot(
        contextToken = "context-token",
        sourceKey = "${source.type.name}:${source.id.orEmpty()}",
        browseId = source.id,
        songs = songs,
        version = 8L,
        continuationToken = token,
        continuationState = continuationState,
        diagnostic = if (continuationState == ContinuationState.BLOCKED) {
            ContinuationDiagnostic.NO_PROGRESS
        } else {
            null
        },
    )

    private fun state(
        canonical: List<Song>,
        source: QueueSource,
        window: QueueWindowSnapshot,
        entries: List<BoundedContextPersistence.Entry>,
        currentIndex: Int,
        positionMs: Long = 0L,
        sourceSnapshot: ContextSnapshot,
    ) = BoundedContextPersistence.State(
        context = sourceSnapshot,
        source = source,
        window = window,
        entries = entries,
        currentIndex = currentIndex,
        positionMs = positionMs,
    )

    private fun entry(
        id: String,
        tier: QueueTier,
        canonicalIndex: Int? = null,
        cycleId: Long? = null,
    ) = BoundedContextPersistence.Entry(
        song(canonicalIndex?.let { "track-$it" } ?: "$id-song")
            .copy(queueTier = tier, queueEntryId = id),
        canonicalIndex,
        cycleId,
    )

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = "https://img/$id",
        durationText = "3:21",
    )
}
