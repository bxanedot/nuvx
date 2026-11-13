package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

















class PartyProtocolLiveTest {


    private val realWelcome = """
        {"party":{"code":"E63UED","createdAtMs":1790398562006,"hostOnlyControl":false,"maxMembers":5,
        "members":[{"avatarUrl":null,"connected":true,"displayName":"Host","isHost":true,
        "joinedAtMs":1790398562006,"lastSeenMs":1790398566425,"memberId":"dd983aba6f58ad44",
        "userId":"host"}],
        "playback":{"anchorMs":1790398562006,"autoplayEnabled":false,"effectivePositionMs":0,
        "isPlaying":false,"positionMs":0,"queueIndex":-1,"queueLength":0,"queueSeq":0,"seq":0,
        "startedBy":null,"startedByName":null,"track":null,"updatedAtMs":1790398562006,
        "updatedBy":null},
        "queue":{"index":-1,"items":[],"seq":0},"serverMs":1790398566425},
        "serverMs":1790398566425,"type":"welcome",
        "you":{"avatarUrl":null,"connected":true,"displayName":"Host","isHost":true,
        "joinedAtMs":1790398562006,"lastSeenMs":1790398566425,"memberId":"dd983aba6f58ad44",
        "userId":"host"}}
    """.trimIndent()


    private val realMembers = """
        {"hostOnlyControl":false,"maxMembers":5,
        "members":[{"avatarUrl":null,"connected":true,"displayName":"Host","isHost":true,
        "joinedAtMs":1790398562006,"lastSeenMs":1790398566425,"memberId":"dd983aba6f58ad44",
        "userId":"host"}],"serverMs":1790398566425,"type":"members"}
    """.trimIndent()

    @Test
    fun `the live welcome frame decodes`() {
        val welcome = assertIs<PartyFrame.Welcome>(PartyFrameCodec.decode(realWelcome))

        assertEquals("E63UED", welcome.party.code)
        assertEquals(5, welcome.party.maxMembers)
        assertEquals(1, welcome.party.members.size)
        assertTrue(welcome.party.members.first().isHost)
        assertTrue(welcome.party.members.first().connected)
        assertEquals("dd983aba6f58ad44", welcome.you.memberId)
        assertEquals(1_790_398_566_425L, welcome.serverMs)





        assertEquals(0L, welcome.party.playback.seq)
        assertEquals(0L, welcome.party.playback.positionMs)
        assertNull(welcome.party.playback.track)
    }

    @Test
    fun `the live members frame decodes`() {
        val members = assertIs<PartyFrame.Members>(PartyFrameCodec.decode(realMembers))
        assertEquals(1, members.members.size)
        assertEquals(5, members.maxMembers)
        assertFalse(members.hostOnlyControl)
        assertEquals(1_790_398_566_425L, members.serverMs)
    }

    @Test
    fun `both live frames drive a real session`() {
        val session = PartySession()
        session.begin("http://192.168.0.252:8000", "E63UED")

        assertEquals(PartySession.Applied.Members, session.apply(PartyFrameCodec.decode(realWelcome)!!))
        assertEquals(PartySession.Applied.Members, session.apply(PartyFrameCodec.decode(realMembers)!!))

        val state = session.current
        assertTrue(state.inParty)
        assertTrue(state.isHost)
        assertEquals("Host", state.hostName)
        assertTrue(state.canControl)
        assertTrue(state.hasRoom)
        assertEquals(1_790_398_566_425L, state.lastServerMs)
    }

    @Test
    fun `a state frame after the live welcome is not swallowed`() {




        val session = PartySession()
        session.begin("http://192.168.0.252:8000", "E63UED")
        session.apply(PartyFrameCodec.decode(realWelcome)!!)

        val next = PartyFrame.State(
            playback = PartyPlayback(
                seq = 1,
                track = PartyTrack(videoId = "dQw4w9WgXcQ", title = "Verification", artist = "PartySync"),
                isPlaying = true,
                positionMs = 42_000,
                anchorMs = 1_790_398_566_425L,
            ),
            serverMs = 1_790_398_567_000L,
        )
        assertEquals(PartySession.Applied.Playback, session.apply(next))
        assertEquals("dQw4w9WgXcQ", session.current.playback.track?.videoId)
        assertTrue(session.current.playback.isPlaying)
    }

    @Test
    fun `the corrected position of a live frame advances with time`() {



        val clock = ServerClock()
        val frameServerMs = 1_790_398_566_425L
        clock.record(sentAtLocalMs = 0, serverMs = frameServerMs, receivedAtLocalMs = 40)

        val playback = assertIs<PartyFrame.Welcome>(
            PartyFrameCodec.decode(realWelcome),
        ).party.playback





        val halfRoundTrip = 20L
        val elapsed = frameServerMs - playback.anchorMs - halfRoundTrip
        assertEquals(5_000L + elapsed, clock.positionFor(playback.positionMs, playback.anchorMs, localNowMs = 5_000))





        assertEquals(
            90_000L + elapsed,
            clock.positionFor(playback.positionMs, playback.anchorMs, localNowMs = 90_000),
        )
    }

    @Test
    fun `an unsynced device holds rather than playing a stale position`() {

        val clock = ServerClock()
        assertEquals(0L, clock.positionFor(positionMs = 42_000, trueAtServerMs = 0, localNowMs = 5_000))
        assertNull(clock.serverNowMs(5_000))
    }

    @Test
    fun `the token is not something the frame carries`() {




        assertNotNull(PartyFrameCodec.decode(realWelcome))
        assertTrue("token" !in realWelcome)
        assertTrue("Authorization" !in realWelcome)
    }

    private fun assertFalse(value: Boolean) = assertTrue(!value, "expected false")
}
