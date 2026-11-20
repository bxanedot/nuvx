package com.licht.nuvx

import com.licht.nuvx.ui.SingleReorderCheckpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleReorderCheckpointTest {
    @Test
    fun newestCheckpointSurvivesAndOlderOnesArePruned() {
        val history = SingleReorderCheckpoint()
        val first = history.issue()
        assertTrue(history.isCurrent(first))
        val second = history.issue()
        assertFalse(history.isCurrent(first))
        assertTrue(history.isCurrent(second))
        history.complete(first)
        assertTrue(history.isCurrent(second))
        history.complete(second)
        assertFalse(history.isCurrent(second))
    }
}
