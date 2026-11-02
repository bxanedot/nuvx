package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenSyncTest {
    private val id = "dQw4w9WgXcQ"
    private val playing = SharedPlayback(id, positionMs = 10_000, playing = true, updatedAt = 1_000, title = "Song", artists = listOf("Band"))
    private fun local(videoId: String?, pos: Long = 0, isPlaying: Boolean = true, buffering: Boolean = false) =
        PlayerState(listOfNotNull(videoId?.let { Track(it, "t") }), if (videoId == null) -1 else 0, isPlaying, buffering, pos, 200_000)

    @Test fun clockOffsetAssumesSymmetricRoundTrip() = assertEquals(500, ListenSync.offset(sentAt = 1_000, serverAt = 1_550, receivedAt = 1_100))

    @Test fun expectedPositionAdvancesOnlyWhilePlaying() {
        assertEquals(13_000, ListenSync.expected(playing, 4_000))
        assertEquals(10_000, ListenSync.expected(playing.copy(playing = false), 4_000))
    }

    @Test fun guestLoadsTheHostTrackAtTheRightPlace() {
        val a = ListenSync.decide(local(null), playing, 4_000)
        assertEquals(SyncAction.Load(Track(id, "Song", listOf("Band"), artworkUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg"), 13_000), a)
    }

    @Test fun guestFixesPlayStateThenDrift() {
        assertEquals(SyncAction.SetPlaying(true), ListenSync.decide(local(id, 13_000, isPlaying = false), playing, 4_000))
        assertEquals(SyncAction.Seek(13_000), ListenSync.decide(local(id, 5_000), playing, 4_000))
        assertEquals(SyncAction.None, ListenSync.decide(local(id, 12_000), playing, 4_000), "within 2 s is left alone")
        assertEquals(SyncAction.None, ListenSync.decide(local(id, 0, buffering = true), playing, 4_000), "no corrections while loading")
        assertEquals(SyncAction.None, ListenSync.decide(local(id), SharedPlayback(), 4_000), "nothing shared yet")
    }

    @Test fun hostPublishesOnlyMeaningfulChanges() {
        assertTrue(ListenSync.hostShouldPublish(null, local(id, 10_000), 1_000))
        assertFalse(ListenSync.hostShouldPublish(playing, local(id, 12_500), 3_500), "normal playback progress")
        assertTrue(ListenSync.hostShouldPublish(playing, local(id, 60_000), 3_500), "seek")
        assertTrue(ListenSync.hostShouldPublish(playing, local(id, 12_500, isPlaying = false), 3_500), "pause")
        assertTrue(ListenSync.hostShouldPublish(playing, local("aaaaaaaaaaa"), 3_500), "new track")
    }
}
