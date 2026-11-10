package com.bxane.nuvx.playback

import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.listentogether.PartyMember
import com.bxane.nuvx.data.listentogether.PartyPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyAutoplayTest {

    private fun member(id: String, host: Boolean = false, connected: Boolean = true) = PartyMember(
        memberId = id,
        displayName = id,
        isHost = host,
        connected = connected,
    )

    private fun party(
        members: List<PartyMember>,
        you: PartyMember? = members.firstOrNull(),
        partyAutoplay: Boolean = true,
        hostOnly: Boolean = false,
        code: String? = "ABC123",
    ) = ListenTogether.State(
        code = code,
        you = you,
        members = members,
        hostOnlyControl = hostOnly,
        playback = PartyPlayback(autoplayEnabled = partyAutoplay),
    )

    @Test
    fun `alone, AutoPlay is this phone's own setting`() {
        val solo = ListenTogether.State()
        assertTrue(autoplayEnabledFor(solo, localPreference = true))
        assertFalse(autoplayEnabledFor(solo, localPreference = false))
    }

    @Test
    fun `in a party, the party's setting wins over the phone's`() {
        val off = party(listOf(member("a", host = true)), partyAutoplay = false)
        assertFalse(autoplayEnabledFor(off, localPreference = true))

        val on = party(listOf(member("a", host = true)), partyAutoplay = true)
        assertTrue(autoplayEnabledFor(on, localPreference = false))
    }

    @Test
    fun `nobody supplies outside a party`() {
        assertNull(autoplaySupplierId(ListenTogether.State()))
    }

    @Test
    fun `the host supplies while it is connected`() {
        val state = party(listOf(member("zz", host = true), member("aa")))
        assertEquals("zz", autoplaySupplierId(state))
    }

    @Test
    fun `an offline host hands over to the lowest connected member`() {
        val state = party(
            listOf(
                member("zz", host = true, connected = false),
                member("mm"),
                member("aa"),
            ),
        )
        assertEquals("aa", autoplaySupplierId(state))
    }

    @Test
    fun `every device elects the same supplier`() {
        val members = listOf(
            member("zz", host = true, connected = false),
            member("mm"),
            member("aa"),
        )
        val answers = listOf(
            members,
            members.reversed(),
            listOf(members[1], members[2], members[0]),
        ).map { autoplaySupplierId(party(it)) }
        assertEquals(listOf("aa", "aa", "aa"), answers)
    }

    @Test
    fun `a locked party has no stand-in supplier`() {
        val state = party(
            listOf(member("zz", host = true, connected = false), member("aa")),
            hostOnly = true,
        )
        assertNull(autoplaySupplierId(state))
    }

    @Test
    fun `a locked party still supplies through its own host`() {
        val state = party(
            listOf(member("zz", host = true), member("aa")),
            hostOnly = true,
        )
        assertEquals("zz", autoplaySupplierId(state))
    }

    @Test
    fun `a party with nobody connected supplies nothing`() {
        val state = party(
            listOf(
                member("zz", host = true, connected = false),
                member("aa", connected = false),
            ),
        )
        assertNull(autoplaySupplierId(state))
    }
}
