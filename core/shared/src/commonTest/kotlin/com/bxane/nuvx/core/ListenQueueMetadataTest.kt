package com.bxane.nuvx.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ListenQueueMetadataTest {
    private val current = Track("aaaaaaaaaaa", "Current")
    private val next = Track("bbbbbbbbbbb", "Upcoming title", listOf("First artist", "Second artist"), album = "Keep local album", durationMs = 180_000)

    @Test fun snapshotSharesBoundedDisplayInfoOnlyForRemoteQueuedTracks() {
        val local = Track("ccccccccccc", "Private", localUri = "file:///private.mp3")
        val shared = ListenSync.snapshot(PlayerState(listOf(current, next, local, next), 0), 0)
        assertEquals(listOf(SharedQueueTrack(next.videoId, next.title, next.artists)), shared.queueInfo)
        val message = shared.toRoomMessage()
        assertEquals(1, message["queueInfo"]!!.jsonArray.size)
        assertFalse(message.toString().contains("private", true))
        assertFalse(message.toString().contains("Keep local album"))
    }

    @Test fun unicodeMetadataStaysWithinBudgetWithoutDroppingQueueIds() {
        val tracks = List(200) { Track(it.toString().padStart(11, '0'), "界".repeat(200), List(10) { "艺".repeat(100) }) }
        val shared = ListenSync.snapshot(PlayerState(listOf(current) + tracks, 0), 0)
        assertEquals(200, shared.queue.size)
        assertTrue(shared.queueInfo.isNotEmpty())
        assertTrue(shared.toRoomMessage()["queueInfo"].toString().encodeToByteArray().size <= 65_536)
        assertTrue(shared.queueInfo.all { it.trackId in shared.queue })
    }

    @Test fun guestUsesHostNamesAndRefreshesMetadataWithoutRestartingCurrentTrack() {
        val shared = SharedPlayback(current.videoId, title = current.title, queue = listOf(next.videoId, next.videoId),
            queueInfo = listOf(SharedQueueTrack(next.videoId, next.title, next.artists)))
        val local = PlayerState(listOf(current, next.copy(title = "Old title")), 0, positionMs = 12_000)
        val action = assertIs<SyncAction.UpdateQueue>(ListenSync.decide(local, shared.copy(positionMs = 12_000), 0))
        assertEquals(listOf(next, next), action.upNext, "host display info overlays only locally held display fields")
        val updated = local.syncingQueue(action.current, action.upNext)!!
        assertEquals(12_000, updated.positionMs)
        assertEquals(SyncAction.None, ListenSync.decide(updated, shared.copy(positionMs = 12_000), 0))
        val last = ListenSync.snapshot(PlayerState(listOf(current, next), 0), 0)
        assertTrue(ListenSync.hostShouldPublish(last, PlayerState(listOf(current, next.copy(title = "Renamed")), 0), 0))
    }

    @Test fun legacyAndUnrelatedOrInvalidMetadataCannotReplaceKnownTrackInfo() {
        val legacy = Json.decodeFromString<SharedPlayback>("""{"trackId":"aaaaaaaaaaa","queue":["bbbbbbbbbbb"]}""")
        val loaded = assertIs<SyncAction.Load>(ListenSync.decide(PlayerState(), legacy, 0))
        assertEquals("Shared track", loaded.upNext.single().title)
        val invalid = legacy.copy(queueInfo = listOf(
            SharedQueueTrack("ccccccccccc", "Unrelated", emptyList()),
            SharedQueueTrack(next.videoId, "x".repeat(201), emptyList()),
        ))
        val known = PlayerState(listOf(current, next), 0)
        assertEquals(SyncAction.None, ListenSync.decide(known, invalid, 0))
    }

    @Test fun aPromotedGuestsPlaceholderTitleDoesNotOverwriteKnownNames() {
        val unknown = next.copy(title = "Shared track", artists = emptyList())
        assertTrue(ListenSync.snapshot(PlayerState(listOf(current, unknown), 0), 0).queueInfo.isEmpty())
        val shared = SharedPlayback(current.videoId, queue = listOf(next.videoId),
            queueInfo = listOf(SharedQueueTrack(next.videoId, "Shared track", emptyList())))
        assertEquals(SyncAction.None, ListenSync.decide(PlayerState(listOf(current, next), 0), shared, 0))
    }
}
