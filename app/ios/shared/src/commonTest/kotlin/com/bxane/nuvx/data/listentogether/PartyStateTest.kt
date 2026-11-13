package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue










class PartyStateTest {

    private fun member(id: String, host: Boolean = false) =
        PartyMember(memberId = id, displayName = "M$id", isHost = host, connected = true)

    private fun state(
        inParty: Boolean = true,
        you: PartyMember? = member("me"),
        members: List<PartyMember> = listOf(member("me"), member("them")),
        maxMembers: Int = 5,
        hostOnlyControl: Boolean = false,
    ) = PartyState(
        inParty = inParty,
        you = you,
        members = members,
        maxMembers = maxMembers,
        hostOnlyControl = hostOnlyControl,
    )



    @Test
    fun `my member id is what a member list is compared against`() {
        assertEquals("abc12345", state(you = member("abc12345")).myMemberId)
    }

    @Test
    fun `my member id is empty before the first frame`() {



        assertEquals("", state(you = null).myMemberId)
    }

    @Test
    fun `a member list can say which row is me`() {
        val s = state(you = member("me"))
        assertTrue(s.isMe(member("me")))
        assertFalse(s.isMe(member("them")))
    }

    @Test
    fun `no member is me before the first frame`() {
        assertFalse(state(you = null).isMe(member("anyone")))
    }



    @Test
    fun `an unlocked party lets anybody drive the music`() {
        assertTrue(state(hostOnlyControl = false).canControl)
    }

    @Test
    fun `a locked party locks a listener out`() {
        val s = state(you = member("me"), hostOnlyControl = true)
        assertFalse(s.canControl)
        assertTrue(s.controlsLocked)
    }

    @Test
    fun `a locked party never locks the host out of their own`() {


        val s = state(you = member("me", host = true), hostOnlyControl = true)
        assertTrue(s.canControl)
        assertFalse(s.controlsLocked)
    }

    @Test
    fun `nobody is locked out of a party that is not a party`() {


        val s = state(inParty = false, you = null, members = emptyList(), hostOnlyControl = true)
        assertFalse(s.controlsLocked)
    }



    @Test
    fun `a device with no party is offline`() {
        assertEquals(PartyConnection.OFFLINE, PartyState().connection)
    }

    @Test
    fun `joining starts as connecting rather than live`() {


        val s = PartySession()
        s.begin("https://jam.example", "ABC123")
        assertEquals(PartyConnection.CONNECTING, s.current.connection)
        assertTrue(s.current.inParty)
    }

    @Test
    fun `the first frame is what makes it live`() {
        val s = PartySession()
        s.begin("https://jam.example", "ABC123")
        s.apply(
            PartyFrame.Welcome(
                you = member("me", host = true),
                party = PartySnapshot(code = "ABC123", members = listOf(member("me", host = true))),
                serverMs = 1_000,
            ),
        )
        assertEquals(PartyConnection.LIVE, s.current.connection)
    }

    @Test
    fun `being removed takes the connection down with the party`() {
        val s = PartySession()
        s.begin("https://jam.example", "ABC123")
        s.apply(PartyFrame.Bye(reason = "kicked", serverMs = 1_000))
        assertEquals(PartyConnection.OFFLINE, s.current.connection)
        assertFalse(s.current.inParty)
    }

    @Test
    fun `resetting forgets the clock as well as the party`() {


        val s = PartySession()
        s.recordPong(sentAtLocalMs = 1_000, serverMs = 500_000, receivedAtLocalMs = 1_200)
        assertTrue(s.current.clockSynced)
        s.reset()
        assertFalse(s.current.clockSynced)
        assertEquals(0L, s.current.roundTripMs)
        assertNull(s.serverClock().offsetMs)
    }

    @Test
    fun `a pong publishes the measurement rather than leaving it in the clock`() {


        val s = PartySession()
        assertFalse(s.current.clockSynced)
        s.recordPong(sentAtLocalMs = 1_000, serverMs = 500_000, receivedAtLocalMs = 1_200)
        assertTrue(s.current.clockSynced)
        assertEquals(200L, s.current.roundTripMs)
    }



    @Test
    fun `room is counted over members and not over connected ones`() {
        val s = state(
            members = listOf(member("me"), member("disconnected")),
            maxMembers = 2,
        )
        assertTrue(s.isFull)
        assertTrue(s.isFullFor(joining = true))
    }

    @Test
    fun `a full party reads as full to nobody who is already in it`() {


        assertFalse(state(maxMembers = 2).isFullFor(joining = false))
    }

    @Test
    fun `the host is found among the members`() {
        val s = state(you = member("me"), members = listOf(member("me"), member("host", host = true)))
        assertEquals("Mhost", s.hostName)
    }

    @Test
    fun `the host name is empty rather than guessed when the host is not resolvable`() {
        assertEquals("", state(you = null, members = emptyList()).hostName)
    }
}
