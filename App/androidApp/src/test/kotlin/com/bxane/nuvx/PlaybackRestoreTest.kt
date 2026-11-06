package com.bxane.nuvx

import com.bxane.nuvx.core.KeyValueStore
import com.bxane.nuvx.core.MediaKind
import com.bxane.nuvx.core.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRestoreTest {
    private val song = Track("aaaaaaaaaaa", "Song", listOf("First", "Second"), "Album", 156_000,
        artworkUrl = "https://art.invalid/cover", artistId = "artist-id", albumId = "album-id")
    private val video = Track("bbbbbbbbbbb", "Video", mediaKind = MediaKind.VIDEO, pairedVideoId = "ccccccccccc")
    private val local = Track("local:42", "Local", localUri = "content://media/external/audio/media/42")

    @Test fun freshOwnerAndPlayerRestoreQueueOrderCursorPositionAndMetadataPaused() = runTest {
        val store = MemoryStore()
        val old = FakePlayer(listOf(song, local, video, song), 2, 12_000, true)
        val oldScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val owner = PlaybackRestore(store, oldScope, old::capture, old::restore)
        owner.checkpoint()
        assertNotNull("queue changes must persist without onDestroy", store.get(PlaybackRestore.STORAGE_KEY))
        oldScope.cancel() // Process death: no close/final save, and no old player is reused.

        val fresh = FakePlayer()
        PlaybackRestore(store, backgroundScope, fresh::capture, fresh::restore).use {
            assertEquals(1, fresh.restores)
            assertEquals(listOf(song.copy(artworkUrl = null), local, video, song.copy(artworkUrl = null)), fresh.queue)
            assertEquals(2, fresh.index)
            assertEquals(12_000L, fresh.positionMs)
            assertFalse("passive recreation must never start audio", fresh.playWhenReady)
        }
    }

    @Test fun bufferingPlayIntentAndLaterDeliberatePauseAreCapturedSeparatelyFromIsPlaying() = runTest {
        for (requestedPlay in listOf(true, false)) {
            val store = MemoryStore()
            val old = FakePlayer(listOf(song), 0, 18_000, true).apply { isPlaying = false }
            val oldScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val owner = PlaybackRestore(store, oldScope, old::capture, old::restore)
            owner.checkpoint()
            assertTrue(saved(store).getValue("playWhenReady").jsonPrimitive.boolean)
            old.playWhenReady = requestedPlay
            owner.checkpoint()
            assertEquals(requestedPlay, saved(store).getValue("playWhenReady").jsonPrimitive.boolean)
            oldScope.cancel()
            val fresh = FakePlayer()
            PlaybackRestore(store, backgroundScope, fresh::capture, fresh::restore).use {
                assertEquals(listOf(song.copy(artworkUrl = null)), fresh.queue)
                assertEquals(18_000L, fresh.positionMs)
                assertFalse("both recorded intents must restore passively paused", fresh.playWhenReady)
            }
        }
    }

    @Test fun positionCheckpointsAreBoundedAndCleanupStopsFurtherWrites() = runTest {
        val store = MemoryStore()
        val player = FakePlayer(listOf(song), 0, 100, true)
        val owner = PlaybackRestore(store, backgroundScope, player::capture, player::restore)
        owner.checkpoint()
        runCurrent()
        val writes = store.writes
        player.positionMs = 5_100
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(writes, store.writes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(5_100L, saved(store).getValue("positionMs").jsonPrimitive.long)
        assertEquals(writes + 1, store.writes)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("unchanged snapshots must not rewrite preferences", writes + 1, store.writes)

        player.positionMs = 7_000
        owner.close()
        assertEquals(7_000L, saved(store).getValue("positionMs").jsonPrimitive.long)
        val captures = player.captures
        val closedWrites = store.writes
        player.positionMs = 99_000
        owner.checkpoint()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(captures, player.captures)
        assertEquals(closedWrites, store.writes)
    }

    @Test fun clearingQueueClearsTheSavedQueueInsteadOfResurrectingIt() = runTest {
        val store = MemoryStore()
        val player = FakePlayer(listOf(song), 0, 0, false)
        PlaybackRestore(store, backgroundScope, player::capture, player::restore).use { owner ->
            owner.checkpoint()
            player.queue = emptyList()
            player.index = -1
            owner.checkpoint()
            assertNull(store.get(PlaybackRestore.STORAGE_KEY))
        }
        val fresh = FakePlayer()
        PlaybackRestore(store, backgroundScope, fresh::capture, fresh::restore).use {
            assertEquals(0, fresh.restores)
            assertTrue(fresh.queue.isEmpty())
        }
    }

    @Test fun malformedAndInvalidSnapshotsAreDiscardedWithoutMutatingAFreshPlayer() = runTest {
        val store = MemoryStore()
        val player = FakePlayer(listOf(song), 0, 10, true)
        PlaybackRestore(store, backgroundScope, player::capture, player::restore).use { it.checkpoint() }
        val valid = saved(store)
        fun changed(key: String, value: JsonElement) = JsonObject(valid + (key to value)).toString()
        val first = valid.getValue("queue").jsonArray[0].jsonObject
        fun trackChanged(key: String, value: JsonElement) = changed("queue", JsonArray(listOf(JsonObject(first + (key to value)))))
        val invalid = listOf(
            "not-json", "{}", "[]", changed("version", JsonPrimitive(2)),
            changed("index", JsonPrimitive(-1)), changed("index", JsonPrimitive(1)),
            changed("positionMs", JsonPrimitive(-1)), changed("positionMs", JsonPrimitive("12")),
            changed("playWhenReady", JsonPrimitive("true")), changed("queue", JsonArray(emptyList())),
            changed("queue", JsonArray(List(1_001) { JsonObject(first) })),
            trackChanged("videoId", JsonPrimitive("not-an-id")),
            trackChanged("mediaKind", JsonPrimitive("UNKNOWN")),
            trackChanged("localUri", JsonPrimitive("https://media.invalid/stream?signature=synthetic")),
            trackChanged("pairedVideoId", JsonPrimitive("bad")),
            trackChanged("durationMs", JsonPrimitive(-1)),
            " ".repeat(1_048_577),
        )
        for (raw in invalid) {
            store.put(PlaybackRestore.STORAGE_KEY, raw)
            val fresh = FakePlayer()
            PlaybackRestore(store, backgroundScope, fresh::capture, fresh::restore).use {
                assertEquals(0, fresh.restores)
                assertTrue(fresh.queue.isEmpty())
                assertNull("bad persisted data must be cleared", store.get(PlaybackRestore.STORAGE_KEY))
            }
        }
    }

    @Test fun onlyStableIdentitiesAndMetadataArePersisted() = runTest {
        val store = MemoryStore()
        val player = FakePlayer(listOf(song, video, local), 1, 12_000, true)
        PlaybackRestore(store, backgroundScope, player::capture, player::restore).use { it.checkpoint() }
        val raw = requireNotNull(store.get(PlaybackRestore.STORAGE_KEY))
        assertFalse(raw.contains("https://"))
        assertFalse(raw.contains("headers"))
        assertTrue(raw.contains("content://media/external/audio/media/42"))

        val fresh = FakePlayer()
        PlaybackRestore(store, backgroundScope, fresh::capture, fresh::restore).use {
            assertEquals(3, fresh.queue.size)
            assertEquals(video, fresh.queue[1])
            assertEquals(local, fresh.queue[2])
        }
    }

    @Test fun invalidLocalReferencesAreNotPersistedOrRestored() = runTest {
        for (uri in listOf("https://media.invalid/signed", "content://media/path?token=synthetic", "file://remote/path", "javascript:invalid")) {
            val store = MemoryStore()
            val player = FakePlayer(listOf(local.copy(localUri = uri)), 0, 0, false)
            PlaybackRestore(store, backgroundScope, player::capture, player::restore).use { it.checkpoint() }
            assertNull(store.get(PlaybackRestore.STORAGE_KEY))
            val fresh = FakePlayer()
            PlaybackRestore(store, backgroundScope, fresh::capture, fresh::restore).use { assertEquals(0, fresh.restores) }
        }
    }

    @Test fun failedStorageWriteDoesNotStopPlaybackAndCanBeRetried() = runTest {
        val store = MemoryStore().apply { failWrites = true }
        val player = FakePlayer(listOf(song), 0, 0, true)
        PlaybackRestore(store, backgroundScope, player::capture, player::restore).use { owner ->
            owner.checkpoint()
            assertTrue(player.playWhenReady)
            assertNull(store.get(PlaybackRestore.STORAGE_KEY))
            store.failWrites = false
            owner.checkpoint()
            assertNotNull(store.get(PlaybackRestore.STORAGE_KEY))
        }
    }

    private fun saved(store: MemoryStore) = Json.parseToJsonElement(requireNotNull(store.get(PlaybackRestore.STORAGE_KEY))).jsonObject

    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        var writes = 0
        var failWrites = false
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            check(!failWrites)
            writes++
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class FakePlayer(
        var queue: List<Track> = emptyList(),
        var index: Int = -1,
        var positionMs: Long = 0,
        var playWhenReady: Boolean = false,
    ) {
        var isPlaying = false
        var restores = 0
        var captures = 0
        fun capture(): PlaybackSnapshot {
            captures++
            return PlaybackSnapshot(queue, index, positionMs, playWhenReady)
        }
        fun restore(snapshot: PlaybackSnapshot) {
            restores++
            queue = snapshot.queue
            index = snapshot.index
            positionMs = snapshot.positionMs
            playWhenReady = snapshot.playWhenReady
        }
    }
}
