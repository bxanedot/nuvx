package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue








class PartySessionTest {

    private fun session() = PartySession().apply { begin("https://jam.example", "ABC123") }

    private fun state(seq: Long, queueSeq: Long = 0, isPlaying: Boolean = true) =
        PartyFrame.State(
            playback = PartyPlayback(seq = seq, queueSeq = queueSeq, isPlaying = isPlaying),
            serverMs = 1_000 + seq,
        )

    private fun member(id: String, host: Boolean = false, name: String = "") =
        PartyMember(memberId = id, displayName = name, isHost = host, connected = true)



    @Test
    fun `a newer state is applied`() {
        val s = session()
        assertEquals(PartySession.Applied.Playback, s.apply(state(seq = 1)))
        assertEquals(1L, s.current.playback.seq)
    }

    @Test
    fun `an equal state is not applied`() {




        val s = session()
        s.apply(state(seq = 5))
        assertEquals(PartySession.Applied.Nothing, s.apply(state(seq = 5)))
    }

    @Test
    fun `an older state is not applied`() {

        val s = session()
        s.apply(state(seq = 9))
        assertEquals(PartySession.Applied.Nothing, s.apply(state(seq = 4)))
        assertEquals(9L, s.current.playback.seq)
    }

    @Test
    fun `a first state always applies`() {
        val s = session()
        assertEquals(PartySession.Applied.Playback, s.apply(state(seq = 0)))
    }



    @Test
    fun `a stale queue asks to be refetched`() {
        val s = session()
        assertEquals(PartySession.Applied.Queue, s.apply(state(seq = 1, queueSeq = 7)))
        assertTrue(s.current.needsQueueRefetch)
    }

    @Test
    fun `a second stale frame before the refetch does not ask again`() {


        val s = session()
        s.apply(state(seq = 1, queueSeq = 7))
        s.queueRefetchSent()
        assertEquals(PartySession.Applied.Queue, s.apply(state(seq = 2, queueSeq = 7)))

        assertEquals(PartySession.Applied.Playback, s.apply(state(seq = 3, queueSeq = 7)))
    }

    @Test
    fun `a queue frame clears the request`() {
        val s = session()
        s.apply(state(seq = 1, queueSeq = 7))
        s.apply(PartyFrame.Queue(PartyQueue(seq = 7, index = 0)))
        assertFalse(s.current.needsQueueRefetch)
    }

    @Test
    fun `a state whose queue is current does not ask for a refetch`() {
        val s = session()
        s.apply(PartyFrame.Queue(PartyQueue(seq = 7, index = 0)))
        assertEquals(PartySession.Applied.Playback, s.apply(state(seq = 1, queueSeq = 7)))
        assertFalse(s.current.needsQueueRefetch)
    }

    @Test
    fun `a welcome means the held queue is current`() {


        val s = session()
        s.apply(PartyFrame.Welcome(
            you = member("m1", host = true),
            party = PartySnapshot(code = "ABC123", queue = PartyQueue(seq = 4)),
        ))
        assertEquals(PartySession.Applied.Playback, s.apply(state(seq = 1, queueSeq = 4)))
    }



    @Test
    fun `members arrive in their own frame`() {



        val s = session()
        s.apply(PartyFrame.Members(listOf(member("m1", host = true), member("m2")), maxMembers = 3))
        assertEquals(2, s.current.members.size)
        assertEquals(3, s.current.maxMembers)
        assertEquals("m1", s.current.host?.memberId)
    }

    @Test
    fun `the host only setting comes from the server`() {


        val s = session()
        s.apply(PartyFrame.Welcome(member("m1"), PartySnapshot(hostOnlyControl = true)))
        assertFalse(s.current.canControl)

        s.apply(PartyFrame.Welcome(member("m1", host = true), PartySnapshot(hostOnlyControl = true)))
        assertTrue(s.current.canControl)
    }

    @Test
    fun `room is counted over members and not over connected members`() {


        val s = session()
        val dropped = PartyMember(memberId = "m2", connected = false)
        s.apply(PartyFrame.Members(listOf(member("m1"), member("m2"), member("m3"), dropped, member("m5")), maxMembers = 5))
        assertTrue(s.current.isFull)
        assertFalse(s.current.hasRoom)
    }



    @Test
    fun `a bye ends the session and keeps the reason`() {


        val s = session()
        assertEquals(PartySession.Applied.Left, s.apply(PartyFrame.Bye(reason = "kicked")))
        assertFalse(s.current.inParty)
        assertEquals("left", s.current.error?.code)
        assertEquals("kicked", s.current.error?.message)
    }

    @Test
    fun `a refusal is recorded without ending the session`() {
        val s = session()
        s.apply(PartyFrame.Failure(error = "host_only", message = "Only the host can do that."))
        assertTrue(s.current.inParty)
        assertEquals("host_only", s.current.error?.code)
    }

    @Test
    fun `a later good frame clears a refusal`() {
        val s = session()
        s.apply(PartyFrame.Failure("host_only", "no"))
        s.apply(PartyFrame.State(PartyPlayback(seq = 1)))
        assertNull(s.current.error)
    }

    @Test
    fun `an activity frame is kept for display and does not disturb the rest`() {
        val s = session()
        s.apply(state(seq = 3))
        s.apply(PartyFrame.Activity(action = "setTrack", by = "Ada", detail = "Changed the song", atMs = 500))
        assertEquals(3L, s.current.playback.seq)
        assertEquals("Ada", s.current.activity?.by)
    }

    @Test
    fun `a pong records the server clock without touching playback`() {
        val s = session()
        s.apply(state(seq = 3, isPlaying = true))
        s.apply(PartyFrame.Pong(clientMs = 1, serverMs = 9_999))
        assertEquals(9_999L, s.current.lastServerMs)
        assertTrue(s.current.playback.isPlaying)
    }



    @Test
    fun `a refusal is answered by the party's next word and not only by a change`() {




        val s = session()
        s.apply(state(seq = 7))
        s.apply(PartyFrame.Failure(error = "end_of_queue", message = "Already at the end of the queue."))
        assertEquals("end_of_queue", s.current.error?.code)


        assertEquals(PartySession.Applied.Nothing, s.apply(state(seq = 7)))
        assertNull(s.current.error)
    }

    @Test
    fun `a repeated heartbeat with nothing to clear changes nothing`() {
        val s = session()
        s.apply(state(seq = 3))
        val before = s.current
        s.apply(state(seq = 3))
        assertEquals(before, s.current)
    }

    @Test
    fun `a setting the server does not version with seq still lands`() {





        val s = session()
        s.apply(PartyFrame.State(playback = PartyPlayback(seq = 4, isPlaying = true, autoplayEnabled = true), serverMs = 1_000))
        assertTrue(s.current.playback.autoplayEnabled)
        s.apply(
            PartyFrame.State(
                playback = PartyPlayback(seq = 4, isPlaying = true, autoplayEnabled = false),
                serverMs = 2_000,
            ),
        )
        assertFalse(s.current.playback.autoplayEnabled)
    }

    @Test
    fun `queue counters move without moving the playhead`() {


        val s = session()
        s.apply(state(seq = 4, queueSeq = 0))
        assertEquals(0, s.current.playback.queueLength)
        s.apply(
            PartyFrame.State(
                playback = PartyPlayback(seq = 4, isPlaying = true, queueSeq = 2, queueLength = 2),
                serverMs = 2_000,
            ),
        )
        assertEquals(2, s.current.playback.queueLength)
        assertEquals(2L, s.current.playback.queueSeq)
    }

    @Test
    fun `the activity log is kept and not just the latest`() {


        val s = session()
        s.apply(PartyFrame.Activity(action = "play", by = "Sam", atMs = 1_000, serverMs = 1_000))
        s.apply(PartyFrame.Activity(action = "pause", by = "Alex", atMs = 2_000, serverMs = 2_000))
        assertEquals(2, s.current.activities.size)

        assertEquals("pause", s.current.activities.first().action)
        assertEquals("pause", s.current.activity?.action)
    }

    @Test
    fun `the activity log is bounded`() {
        val s = session()
        repeat(PartySession.ACTIVITY_LIMIT + 25) { index ->
            s.apply(PartyFrame.Activity(action = "seek", by = "Sam", atMs = index.toLong(), serverMs = 0))
        }
        assertEquals(PartySession.ACTIVITY_LIMIT, s.current.activities.size)
    }

    @Test
    fun `a replayed activity is not logged twice`() {


        val s = session()
        val frame = PartyFrame.Activity(action = "next", by = "Sam", atMs = 5_000, detail = "Skipped ahead")
        s.apply(frame)
        s.apply(frame)
        assertEquals(1, s.current.activities.size)
    }

    @Test
    fun `a stale queue is noticed from a repeated heartbeat and not only a new song`() {


        val s = session()
        s.apply(state(seq = 4, queueSeq = 0))
        val applied = s.apply(
            PartyFrame.State(
                playback = PartyPlayback(seq = 4, isPlaying = true, queueSeq = 5),
                serverMs = 2_000,
            ),
        )
        assertEquals(PartySession.Applied.Queue, applied)
        assertTrue(s.current.needsQueueRefetch)
    }

    @Test
    fun `a repeated heartbeat does not rewind the playhead but does not lose the rest`() {

        val s = session()
        s.apply(PartyFrame.State(playback = PartyPlayback(seq = 9, positionMs = 5_000, isPlaying = true), serverMs = 1_000))
        s.apply(
            PartyFrame.State(
                playback = PartyPlayback(
                    seq = 9, positionMs = 5_000, isPlaying = true,
                    autoplayEnabled = false, queueLength = 3, queueIndex = 1,
                ),
                serverMs = 6_000,
            ),
        )
        assertEquals(5_000L, s.current.playback.positionMs)
        assertFalse(s.current.playback.autoplayEnabled)
        assertEquals(3, s.current.playback.queueLength)
    }

    @Test
    fun `the server's own idea of the position is not taken over the local one`() {



        val s = session()
        s.apply(PartyFrame.State(playback = PartyPlayback(seq = 9, positionMs = 5_000, effectivePositionMs = 5_000), serverMs = 1_000))
        s.apply(
            PartyFrame.State(
                playback = PartyPlayback(seq = 9, positionMs = 5_000, effectivePositionMs = 99_000),
                serverMs = 6_000,
            ),
        )
        assertEquals(5_000L, s.current.playback.effectivePositionMs)
    }

    @Test
    fun `beginning a session starts empty and in the party`() {
        val s = PartySession()
        assertFalse(s.current.inParty)
        s.begin("https://jam.example", "ABC123")
        assertTrue(s.current.inParty)
        assertEquals("ABC123", s.code)
        assertEquals("https://jam.example", s.serverBase)
    }

    @Test
    fun `resetting clears everything including the bound server`() {
        val s = session()
        s.apply(state(seq = 3))
        s.reset()
        assertFalse(s.current.inParty)
        assertEquals(0L, s.current.playback.seq)
        assertEquals("", s.serverBase)
    }

    @Test
    fun `the bound server has no public setter`() {



        val s = session()
        assertEquals("https://jam.example", s.serverBase)
        s.apply(state(seq = 1))
        s.apply(PartyFrame.Members(listOf(member("m1", host = true))))
        assertEquals("https://jam.example", s.serverBase)
        s.reset()
        assertEquals("", s.serverBase)
    }

    @Test
    fun `a new party rebinds the server`() {


        val s = session()
        s.reset()
        s.begin("https://other.example", "XYZ789")
        assertEquals("https://other.example", s.serverBase)
        assertEquals("XYZ789", s.code)
    }



    @Test
    fun `a member with no name falls back to a shortened id`() {


        assertEquals("ABC123", PartyMember(memberId = "abc123def456").name)
    }

    @Test
    fun `a member with a name uses it`() {
        assertEquals("Ada", PartyMember(memberId = "abc123", displayName = "Ada").name)
    }
}
