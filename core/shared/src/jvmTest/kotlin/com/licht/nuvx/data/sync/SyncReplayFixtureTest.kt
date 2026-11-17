package com.licht.nuvx.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Replays the shared fixture every Nuvx client must agree on (`core/sync/fixtures/replay-v1.json`). */
class SyncReplayFixtureTest {

    private val fixture = Json.parseToJsonElement(File("../sync/fixtures/replay-v1.json").readText()).jsonObject

    private val ops = fixture["ops"]!!.jsonArray.map { it.jsonObject }.map {
        SyncOp(
            seq = it["seq"]!!.jsonPrimitive.long,
            kind = it["kind"]!!.jsonPrimitive.content,
            entity = it["entity"]!!.jsonPrimitive.content,
            payload = it["payload"] as JsonObject,
        )
    }

    private fun assertPlaylists(expected: JsonObject, state: SyncState) {
        for ((id, want) in expected) {
            val got = state.playlists[id]
            if (want is JsonNull) { assertNull(got, "$id should not exist"); continue }
            val obj = want.jsonObject
            assertEquals(obj["title"]!!.jsonPrimitive.content, got?.title, "$id title")
            assertEquals(obj["tracks"]!!.jsonArray.map { it.jsonPrimitive.content }, got?.tracks, "$id tracks")
        }
    }

    @Test
    fun replayingTheWholeLogGivesTheExpectedState() {
        val state = replay(ops)
        val expected = fixture["expected"]!!.jsonObject
        assertPlaylists(expected["playlists"]!!.jsonObject, state)
        assertEquals(expected["playlists"]!!.jsonObject.keys, state.playlists.keys, "no other playlists")
        assertEquals(expected["favorites"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), state.favorites)
        assertEquals(expected["settings"]!!.jsonObject.toMap(), state.settings)
    }

    @Test
    fun replayingUpToASeqRestoresThatVersion() {
        val upTo = fixture["expectedUpTo"]!!.jsonObject
        assertPlaylists(upTo["playlists"]!!.jsonObject, replay(ops, upTo = upTo["seq"]!!.jsonPrimitive.long))
    }

    @Test
    fun theOrderOpsArriveInDoesNotMatterOnlyTheirSeq() {
        assertEquals(replay(ops), replay(ops.shuffled(kotlin.random.Random(7))))
    }
}
