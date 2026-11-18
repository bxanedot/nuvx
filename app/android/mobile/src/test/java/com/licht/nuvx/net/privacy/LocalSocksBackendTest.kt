package com.licht.nuvx.net.privacy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSocksBackendTest {
    @Test fun localSocksModeOnlySelectsExternalBackend() {
        assertEquals(listOf(BackendId.LOCAL_SOCKS), PrivacyMode.LOCAL_SOCKS.candidates())
        assertFalse(PrivacyMode.AUTOMATIC.candidates().contains(BackendId.LOCAL_SOCKS))
    }

    @Test fun localSocksRequiresASuccessfulProxiedProbe() = runTest {
        var working = false
        var checks = 0
        val backend = LocalSocksBackend(10808) { port ->
            checks++
            assertEquals(10808, port)
            working
        }
        assertNull(backend.connect { })
        assertEquals(1, checks)
        working = true
        assertEquals(10808, backend.connect { })
        assertTrue(backend.verify(10808))
        assertFalse(backend.verify(9050))
    }

    @Test fun invalidPortsAreNeverUsed() = runTest {
        var probes = 0
        val backend = LocalSocksBackend(65536) { probes++; true }
        assertNull(backend.connect { })
        assertFalse(backend.verify(65536))
        assertEquals(0, probes)
    }

    @Test fun failedLocalSocksNeverFallsBackToDirectOrTor() = runTest {
        val backend = LocalSocksBackend(10808) { false }
        val manager = ConnectionManager(PrivacyMode.LOCAL_SOCKS, listOf(backend), CoroutineScope(coroutineContext))
        manager.start()
        advanceUntilIdle()
        assertTrue(manager.state.value is ConnectionState.Unavailable)
        assertNull(manager.socksPort)
        assertFalse(manager.verifyCurrent())
        // Protected requests use a blocked loopback port, not a direct connection.
        val selector = PrivacyProxySelector(protected = true, socksPort = { manager.socksPort })
        val route = selector.select(java.net.URI("https://music.youtube.com")).single()
        assertEquals(PrivacyProxySelector.BLOCKED, route)
    }

    @Test fun verifiedLocalSocksIsSelectableAndRetested() = runTest {
        var up = true
        val backend = LocalSocksBackend(10808) { up }
        val manager = ConnectionManager(PrivacyMode.LOCAL_SOCKS, listOf(backend), CoroutineScope(coroutineContext))
        manager.start()
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected(BackendId.LOCAL_SOCKS, 10808), manager.state.value)
        assertTrue(manager.verifyCurrent())
        up = false
        assertFalse(manager.verifyCurrent())
    }
}
