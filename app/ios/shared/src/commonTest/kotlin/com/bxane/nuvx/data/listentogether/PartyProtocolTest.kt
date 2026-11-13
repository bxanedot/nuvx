package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put










class PartyProtocolTest {

    private fun encoded(frame: PartyOutgoing): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(PartyOutgoingCodec.encode(frame)).jsonObject



    @Test
    fun `a ping carries the local reading it will be given back`() {


        val frame = encoded(PartyOutgoing.Ping(clientMs = 12_345))
        assertEquals("ping", frame["type"]?.jsonPrimitive?.content)
        assertEquals(12_345L, frame["clientMs"]?.jsonPrimitive?.long)
    }

    @Test
    fun `sync asks for a state frame`() {
        assertEquals("sync", encoded(PartyOutgoing.Sync)["type"]?.jsonPrimitive?.content)
        assertEquals("syncQueue", encoded(PartyOutgoing.SyncQueue)["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a report carries this device's own playhead`() {
        val frame = encoded(PartyOutgoing.Report(positionMs = 4_200, isPlaying = true))
        assertEquals("report", frame["type"]?.jsonPrimitive?.content)
        assertEquals(4_200L, frame["positionMs"]?.jsonPrimitive?.long)
        assertEquals(true, frame["isPlaying"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `a control merges its payload onto the frame rather than nesting it`() {



        val frame = encoded(PartyOutgoingCodec.seek(4_200))
        assertEquals("control", frame["type"]?.jsonPrimitive?.content)
        assertEquals("seek", frame["action"]?.jsonPrimitive?.content)
        assertEquals(4_200L, frame["positionMs"]?.jsonPrimitive?.long)
        assertNull(frame["payload"])
    }

    @Test
    fun `a single queued song goes in track and several in tracks`() {


        val one = encoded(PartyOutgoingCodec.queueAdd(listOf(track("a"))))
        assertNotNull(one["track"])
        assertNull(one["tracks"])

        val many = encoded(PartyOutgoingCodec.queueAdd(listOf(track("a"), track("b"))))
        assertNotNull(many["tracks"])
        assertNull(many["track"])
        assertEquals(2, many["tracks"]?.jsonArray?.size)
    }

    @Test
    fun `a queue removal goes by video id and not by index`() {



        val frame = encoded(PartyOutgoingCodec.queueRemove("vid1"))
        assertEquals("vid1", frame["videoId"]?.jsonPrimitive?.content)
        assertNull(frame["index"])
    }

    @Test
    fun `the two enabled flags are not confused with each other`() {
        val autoplay = encoded(PartyOutgoingCodec.setAutoplay(true))
        assertEquals("setAutoplay", autoplay["action"]?.jsonPrimitive?.content)
        assertEquals(true, autoplay["enabled"]?.jsonPrimitive?.boolean)

        val hostOnly = encoded(PartyOutgoingCodec.setHostOnlyControl(true))
        assertEquals("setHostOnlyControl", hostOnly["action"]?.jsonPrimitive?.content)
        assertEquals(true, hostOnly["enabled"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `seek takes a position and the other controls take nothing`() {


        for (frame in listOf(PartyOutgoingCodec.play(), PartyOutgoingCodec.pause(), PartyOutgoingCodec.next())) {
            assertEquals(2, encoded(frame).size, "unexpected fields on $frame")
        }
    }

    @Test
    fun `a track on the wire carries the fields the server reads`() {
        val frame = encoded(PartyOutgoingCodec.setTrack(track("vid1", title = "T", artist = "A")))
        val sent = frame["track"]!!.jsonObject
        assertEquals("vid1", sent["videoId"]?.jsonPrimitive?.content)
        assertEquals("T", sent["title"]?.jsonPrimitive?.content)
        assertEquals("A", sent["artist"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an absent optional field is left out rather than sent as null`() {


        val sent = encoded(PartyOutgoingCodec.setTrack(track("vid1")))["track"]!!.jsonObject
        assertNull(sent["thumbnailUrl"])
        assertNull(sent["durationMs"])
    }



    @Test
    fun `a welcome frame is read`() {

        val frame = PartyFrameCodec.decode(
            """
            {"type":"welcome","serverMs":1790395254965,
             "you":{"memberId":"29677a391fa3bc7b","userId":"u1","displayName":"Listener",
                    "isHost":true,"connected":true,"joinedAtMs":1790395254965,"lastSeenMs":1790395254965},
             "party":{"code":"UVT2T1","maxMembers":5,"hostOnlyControl":false,
                      "members":[{"memberId":"29677a391fa3bc7b","displayName":"Listener","isHost":true}],
                      "playback":{"seq":1,"isPlaying":false,"positionMs":0,"anchorMs":1790395254965},
                      "queue":{"seq":0,"index":-1,"items":[]}}}
            """.trimIndent(),
        )
        val welcome = assertIs<PartyFrame.Welcome>(frame)
        assertEquals("UVT2T1", welcome.party.code)
        assertTrue(welcome.you.isHost)
        assertEquals(1_790_395_254_965L, welcome.serverMs)
    }

    @Test
    fun `a state frame is read with its playback`() {
        val frame = PartyFrameCodec.decode(
            """
            {"type":"state","serverMs":1790395255000,
             "playback":{"seq":7,"track":{"videoId":"vid1","title":"T","artist":"A","durationMs":200000},
                         "queueSeq":3,"queueLength":5,"queueIndex":2,"isPlaying":true,
                         "positionMs":30000,"anchorMs":1790395254000,
                         "startedBy":"m1","startedByName":"Ada","autoplayEnabled":true}}
            """.trimIndent(),
        )
        val state = assertIs<PartyFrame.State>(frame)
        assertEquals(7L, state.playback.seq)
        assertEquals(3L, state.playback.queueSeq)
        assertEquals(2, state.playback.queueIndex)
        assertEquals(30_000L, state.playback.positionMs)
        assertEquals("Ada", state.playback.startedByName)
        assertEquals("vid1", state.playback.currentTrack?.videoId)
        assertEquals(200_000L, state.playback.currentTrack?.durationMs)
    }

    @Test
    fun `a pong is read and echoes the token`() {
        val pong = assertIs<PartyFrame.Pong>(PartyFrameCodec.decode("""{"type":"pong","clientMs":999,"serverMs":1000}"""))
        assertEquals(999L, pong.clientMs)
        assertEquals(1_000L, pong.serverMs)
    }

    @Test
    fun `a members frame is read with the party-wide setting`() {
        val frame = PartyFrameCodec.decode(
            """
            {"type":"members","serverMs":1000,"maxMembers":3,"hostOnlyControl":true,
             "members":[{"memberId":"m1","displayName":"Ada","isHost":true},
                        {"memberId":"m2","displayName":"Grace"}]}
            """.trimIndent(),
        )
        val members = assertIs<PartyFrame.Members>(frame)
        assertEquals(2, members.members.size)
        assertEquals(3, members.maxMembers)
        assertTrue(members.hostOnlyControl)
    }

    @Test
    fun `a bye is distinguished from an error`() {




        assertEquals("kicked", assertIs<PartyFrame.Bye>(PartyFrameCodec.decode("""{"type":"bye","reason":"kicked"}""")).reason)
        assertEquals(
            "host_only",
            assertIs<PartyFrame.Failure>(PartyFrameCodec.decode("""{"type":"error","error":"host_only","message":"nope"}""")).error,
        )
    }

    @Test
    fun `an activity frame keeps the words the server wrote`() {
        val frame = PartyFrameCodec.decode(
            """{"type":"activity","action":"setTrack","by":"Ada","detail":"Changed the song to \"T\"","atMs":1234}""",
        )
        val activity = assertIs<PartyFrame.Activity>(frame)
        assertEquals("setTrack", activity.action)
        assertEquals("Ada", activity.by)
        assertEquals("""Changed the song to "T"""", activity.detail)
    }



    @Test
    fun `a frame this build does not know is ignored rather than fatal`() {


        assertNull(PartyFrameCodec.decode("""{"type":"somethingNew","x":1}"""))
    }

    @Test
    fun `nonsense is ignored rather than fatal`() {
        assertNull(PartyFrameCodec.decode("not json"))
        assertNull(PartyFrameCodec.decode("[]"))
        assertNull(PartyFrameCodec.decode("{}"))
        assertNull(PartyFrameCodec.decode(""))
    }

    @Test
    fun `an unknown field in a known frame is tolerated`() {


        val frame = PartyFrameCodec.decode("""{"type":"pong","clientMs":1,"serverMs":2,"somethingNew":true}""")
        assertEquals(1L, assertIs<PartyFrame.Pong>(frame).clientMs)
    }

    @Test
    fun `a missing optional field does not fail the frame`() {

        val frame = PartyFrameCodec.decode("""{"type":"state","serverMs":5,"playback":{"seq":2}}""")
        assertEquals(2L, assertIs<PartyFrame.State>(frame).playback.seq)
        assertNull(assertIs<PartyFrame.State>(frame).playback.track)
    }

    private fun track(
        videoId: String,
        title: String = "",
        artist: String = "",
    ) = PartyTrack(videoId = videoId, title = title, artist = artist)
}
