package com.licht.nuvx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRequestEpochTest {
    @Test
    fun logoutRejectsOlderSignInAndLibraryRequestsButAcceptsFreshOnes() {
        val requests = AccountRequestEpoch()
        val pendingSignIn = requests.capture()
        val pendingChannels = requests.capture()
        assertTrue(requests.isCurrent(pendingSignIn))
        requests.invalidate() // User signs out while both network calls are awaiting.
        assertFalse(requests.isCurrent(pendingSignIn))
        assertFalse(requests.isCurrent(pendingChannels))
        val freshRequest = requests.capture()
        assertTrue(requests.isCurrent(freshRequest))
        requests.invalidate() // Repeated logout also invalidates new work.
        assertFalse(requests.isCurrent(freshRequest))
    }
}
