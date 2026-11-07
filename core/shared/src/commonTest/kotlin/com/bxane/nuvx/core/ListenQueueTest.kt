package com.bxane.nuvx.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenQueueTest {
    private val current = Track("aaaaaaaaaaa", "Current", listOf("Artist"))
    private val next = Track("bbbbbbbbbbb", "Next")
    private val later = Track("ccccccccccc", "Later")

    @Test fun hostSharesOnlyBoundedUpcomingRemoteIdsWithoutLosingDuplicates() {
        val private = Track("ddddddddddd", "Private title", localUri = "file:///private/song.mp3")
        val local = Track("local:private", "Local title")
        val state = PlayerState(listOf(later, current, private, local, Track("bad", "Bad"), next, next) + List(205) { later }, 1)
        val shared = ListenSync.snapshot(state, 1_000)
        assertEquals(current.videoId, shared.trackId)
        assertEquals(200, shared.queue.size)
        assertEquals(listOf("bbbbbbbbbbb", "bbbbbbbbbbb", "ccccccccccc"), shared.queue.take(3))
        val message = shared.toRoomMessage()
        assertEquals("bbbbbbbbbbb", message["queue"]!!.jsonArray.first().jsonPrimitive.content)
        assertFalse(message.toString().contains("Private"))
        assertFalse(message.toString().contains("file:"))
        assertNull(ListenSync.snapshot(state.copy(index = 2), 1_000).trackId)
    }

    @Test fun hostPublishesQueueEditsWhilePlaybackPositionIsUnchanged() {
        val state = PlayerState(listOf(current, next), 0, isPlaying = false, positionMs = 5_000)
        val last = ListenSync.snapshot(state, 1_000)
        assertFalse(ListenSync.hostShouldPublish(last, state, 1_000))
        assertTrue(ListenSync.hostShouldPublish(last, state.copy(queue = listOf(current, later, next)), 1_000))
    }

    @Test fun guestLoadsTheCurrentTrackAndValidatedUpcomingOrder() {
        val shared = SharedPlayback(current.videoId, title = current.title, artists = current.artists,
            queue = listOf(next.videoId, "local:private", "https://invalid", next.videoId, later.videoId))
        val action = assertIs<SyncAction.Load>(ListenSync.decide(PlayerState(), shared, 0))
        assertEquals(listOf("bbbbbbbbbbb", "bbbbbbbbbbb", "ccccccccccc"), action.upNext.map(Track::videoId))
        assertEquals(current.title, action.track.title)
    }

    @Test fun guestQueueEditPreservesCurrentPositionPauseAndHistory() {
        val local = PlayerState(listOf(later, current, next), 1, positionMs = 25_000, durationMs = 100_000, volume = 0.4f)
        val shared = SharedPlayback(current.videoId, 25_000, title = current.title, artists = current.artists, queue = listOf(later.videoId, next.videoId))
        val action = assertIs<SyncAction.UpdateQueue>(ListenSync.decide(local, shared, 0))
        val updated = local.syncingQueue(action.current, action.upNext)!!
        assertEquals(local.copy(queue = listOf(later, current, later, next)), updated)
        assertEquals("Later", action.upNext.first().title, "reuse metadata already held by this device")
        assertNull(local.syncingQueue(next, emptyList()), "a late update cannot replace a different playing track")
        assertEquals(SyncAction.None, ListenSync.decide(updated, shared, 0))
    }

    @Test fun hostClearingQueueRemovesGuestUpcomingTracksAndRefreshesCurrentTitle() {
        val local = PlayerState(listOf(current.copy(title = "Shared track"), next), 0)
        val shared = SharedPlayback(current.videoId, title = current.title, artists = current.artists)
        val action = assertIs<SyncAction.UpdateQueue>(ListenSync.decide(local, shared, 0))
        assertEquals(current.title, action.current.title)
        assertTrue(action.upNext.isEmpty())
    }
}
