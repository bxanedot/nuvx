package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue









class ServerSelectionTest {

    private val builtIn = "https://built.in"
    private val custom = "https://mine.example"


    private fun probes(vararg online: String): (String, Long) -> ProbeResult = { base, _ ->
        if (base in online) ProbeResult(isOnline = true, latencyMs = 40) else ProbeResult(isOnline = false)
    }

    private fun connectionOf(choice: ServerChoice) = choice.connection



    @Test
    fun `with no server at all nothing is probed`() {



        var probes = 0
        val choice = runBlockingProbe(customServer = "", defaultServer = "") { _, _ ->
            probes++
            ProbeResult(false)
        }
        assertIs<ServerConnection.Unconfigured>(choice.connection)
        assertEquals("", choice.base)
        assertEquals(0, probes)
    }

    @Test
    fun `an unconfigured state reports no health and no latency`() {
        val conn = ServerConnection.Unconfigured
        assertEquals(ServerHealth.UNKNOWN, conn.health)
        assertNull(conn.latencyMs)
        assertFalse(conn.isFallback)
    }



    @Test
    fun `the built-in server is used when it answers`() {
        val choice = runBlockingProbe(customServer = "", defaultServer = builtIn) { base, _ ->
            ProbeResult(base == builtIn, 40)
        }
        assertIs<ServerConnection.DefaultOnline>(choice.connection)
        assertEquals(builtIn, choice.base)
        assertEquals(40, choice.connection.latencyMs)
        assertFalse(choice.connection.isFallback)
    }

    @Test
    fun `the built-in server being down is offline rather than unconfigured`() {

        val choice = runBlockingProbe(customServer = "", defaultServer = builtIn) { _, _ -> ProbeResult(false) }
        assertIs<ServerConnection.Offline>(choice.connection)
        assertEquals(builtIn, choice.base)
    }



    @Test
    fun `a configured server that answers is used`() {
        val choice = runBlockingProbe(customServer = custom, defaultServer = builtIn) { base, _ ->
            ProbeResult(base == custom, 25)
        }
        assertIs<ServerConnection.CustomOnline>(choice.connection)
        assertEquals(custom, choice.base)
        assertEquals(25, choice.connection.latencyMs)
        assertFalse(choice.connection.isFallback)
    }

    @Test
    fun `a configured server equal to the built-in one is not a configured server`() {


        var probes = 0
        val choice = runBlockingProbe(customServer = "$builtIn/", defaultServer = builtIn) { base, _ ->
            probes++
            ProbeResult(base == builtIn, 40)
        }
        assertIs<ServerConnection.DefaultOnline>(choice.connection)
        assertEquals(1, probes)
    }

    @Test
    fun `a configured server that differs only in spelling is not a second server`() {
        var probes = 0
        val choice = runBlockingProbe(customServer = "MINE.example".let { "https://$it" }, defaultServer = builtIn) { _, _ ->
            probes++
            ProbeResult(true, 40)
        }
        assertIs<ServerConnection.CustomOnline>(choice.connection)
        assertEquals(1, probes)
    }



    @Test
    fun `a configured server that is down falls back to the built-in one`() {
        val choice = runBlockingProbe(customServer = custom, defaultServer = builtIn) { base, _ ->
            ProbeResult(base == builtIn, 55)
        }
        assertIs<ServerConnection.CustomFallback>(choice.connection)
        assertEquals(builtIn, choice.base)
        assertTrue(choice.connection.isFallback)
    }

    @Test
    fun `a fallback reports the latency of the server actually in use`() {



        val choice = runBlockingProbe(customServer = custom, defaultServer = builtIn) { base, _ ->
            if (base == custom) ProbeResult(isOnline = false, latencyMs = 8_000) else ProbeResult(true, 55)
        }
        val conn = connectionOf(choice)
        assertIs<ServerConnection.CustomFallback>(conn)
        assertEquals(55, conn.latencyMs)
    }

    @Test
    fun `both servers down is offline`() {
        val choice = runBlockingProbe(customServer = custom, defaultServer = builtIn) { _, _ -> ProbeResult(false) }
        assertIs<ServerConnection.Offline>(choice.connection)


        assertTrue(choice.base == custom || choice.base == builtIn)
    }

    @Test
    fun `a configured server with nothing to fall back to is offline not fallback`() {


        val choice = runBlockingProbe(customServer = custom, defaultServer = "") { _, _ -> ProbeResult(false) }
        assertIs<ServerConnection.Offline>(choice.connection)
        assertEquals(custom, choice.base)
        assertFalse(choice.connection.isFallback)
    }



    @Test
    fun `an address that does not parse is treated as no address`() {


        val choice = runBlockingProbe(customServer = "http://", defaultServer = builtIn) { base, _ ->
            ProbeResult(base == builtIn, 40)
        }
        assertIs<ServerConnection.DefaultOnline>(choice.connection)
        assertEquals(builtIn, choice.base)
    }

    @Test
    fun `a malformed address is normalised away before being compared`() {
        val choice = runBlockingProbe(customServer = "  BUILT.IN/  ", defaultServer = builtIn) { _, _ ->
            ProbeResult(true, 40)
        }
        assertIs<ServerConnection.DefaultOnline>(choice.connection)
    }



    @Test
    fun `health is folded out of the connection state`() {
        assertEquals(ServerHealth.ONLINE, ServerConnection.DefaultOnline(1).health)
        assertEquals(ServerHealth.ONLINE, ServerConnection.CustomOnline(1).health)
        assertEquals(ServerHealth.ONLINE, ServerConnection.CustomFallback(1).health)
        assertEquals(ServerHealth.OFFLINE, ServerConnection.Offline.health)
        assertEquals(ServerHealth.CHECKING, ServerConnection.Checking.health)
    }

    @Test
    fun `a state with no latency says so rather than reporting zero`() {


        assertNull(ServerConnection.Offline.latencyMs)
        assertNull(ServerConnection.Checking.latencyMs)
        assertNull(ServerConnection.Unconfigured.latencyMs)
        assertEquals(0, ServerConnection.CustomOnline(0).latencyMs)
    }



    @Test
    fun `an invite that names a server wins over everything`() {
        val invite = "https://invite.example"
        assertEquals(invite, ServerSelection.switchTarget(invite, custom, builtIn))
    }

    @Test
    fun `an invite's server is normalised before it is used`() {
        assertEquals(
            "https://invite.example",
            ServerSelection.switchTarget("INVITE.example/", custom, builtIn),
        )
    }

    @Test
    fun `a switch with no explicit target lands on the resolved idle server`() {



        assertEquals(builtIn, ServerSelection.switchTarget(null, "", builtIn))
        assertEquals(custom, ServerSelection.switchTarget(null, custom, builtIn))
    }

    @Test
    fun `an invite naming an unusable server falls through rather than failing`() {
        val choice = ServerSelection.switchTarget("ftp://nope", "", builtIn)
        assertEquals(builtIn, choice)
    }



    @Test
    fun `a transport failure is eligible because a different server might answer`() {
        assertTrue(PartyFailure.Transport("connection refused").isEligibleForFallback())
    }

    @Test
    fun `a 5xx is eligible because the server is unwell rather than absent`() {
        assertTrue(PartyFailure.Server(500).isEligibleForFallback())
        assertTrue(PartyFailure.Server(503).isEligibleForFallback())
    }

    @Test
    fun `a 4xx is not eligible because the server is working and said no`() {



        assertFalse(PartyFailure.Rejected(403).isEligibleForFallback())
        assertFalse(PartyFailure.Rejected(404).isEligibleForFallback())
        assertFalse(PartyFailure.Rejected(401).isEligibleForFallback())
        assertFalse(PartyFailure.Rejected(429).isEligibleForFallback())
    }

    @Test
    fun `a 5xx boundary is exact`() {

        assertFalse(PartyFailure.Server(499).isEligibleForFallback())
        assertTrue(PartyFailure.Server(500).isEligibleForFallback())
    }

    @Test
    fun `anything else is not eligible`() {


        assertFalse(PartyFailure.Other("malformed json").isEligibleForFallback())
    }



    private fun runBlockingProbe(
        customServer: String,
        defaultServer: String = builtIn,
        probe: (String, Long) -> ProbeResult,
    ): ServerChoice = kotlinx.coroutines.runBlocking {
        ServerSelection.resolve(customServer, defaultServer) { base, timeout -> probe(base, timeout) }
    }

    @Test
    fun `each server is given its own timeout`() {



        val timeouts = mutableListOf<Long>()
        runBlockingProbe(customServer = custom) { base, timeout ->
            timeouts += timeout
            ProbeResult(base == custom, 10)
        }
        assertEquals(listOf(ServerSelection.CUSTOM_SERVER_TIMEOUT_MS), timeouts)

        timeouts.clear()
        runBlockingProbe(customServer = "") { _, timeout ->
            timeouts += timeout
            ProbeResult(true, 10)
        }
        assertEquals(listOf(ServerSelection.DEFAULT_SERVER_TIMEOUT_MS), timeouts)
    }
}
