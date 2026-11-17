package com.licht.nuvx.net.privacy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrbotHandshakeTest {
    private val checked = mutableListOf<Int>()
    private suspend fun torOn(port: Int): Boolean { checked += port; return true }

    @Test
    fun orbotAlreadyRunningConnectsAtOnceOnItsReportedPort() = runTest {
        // The bug: nuvx waited for a status change that never comes when Orbot is already on.
        val statuses = Channel<OrbotStatus>(Channel.UNLIMITED).apply { trySend(OrbotStatus(on = true, socksPort = 9052)) }
        assertEquals(9052, awaitOrbot(statuses, 4_000, 45_000, {}, ::torOn))
        assertEquals(listOf(9052), checked)
    }

    @Test
    fun waitsForOrbotToFinishStartingAndKeepsThePortFromItsFirstReply() = runTest {
        val statuses = Channel<OrbotStatus>(Channel.UNLIMITED).apply { trySend(OrbotStatus(on = false, socksPort = 9055)) }
        launch {
            statuses.send(OrbotStatus(on = false, socksPort = null))
            statuses.send(OrbotStatus(on = true, socksPort = null))
        }
        assertEquals(9055, awaitOrbot(statuses, 4_000, 45_000, {}, ::torOn))
    }

    @Test
    fun noReplyStillUsesATorThatIsRunningOnTheDefaultPort() = runTest {
        assertEquals(9050, awaitOrbot(Channel(), 4_000, 45_000, {}, ::torOn))
        assertNull(awaitOrbot(Channel(), 4_000, 45_000, {}) { false })
    }

    @Test
    fun givesUpWhenTorNeverComesOn() = runTest {
        val statuses = Channel<OrbotStatus>(Channel.UNLIMITED).apply { trySend(OrbotStatus(on = false, socksPort = 9050)) }
        assertNull(awaitOrbot(statuses, 4_000, 45_000, {}, ::torOn))
        assertEquals(emptyList<Int>(), checked)
    }
}
