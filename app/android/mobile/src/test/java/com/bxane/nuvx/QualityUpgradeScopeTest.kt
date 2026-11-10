package com.bxane.nuvx

import com.bxane.nuvx.playback.QualityUpgrade
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityUpgradeScopeTest {

    private val mediaId = "i1o1p_DD6TU"

    @Test
    fun `a fresh play re-opens a verdict reached on the previous one`() {
        QualityUpgrade.markAnsweredForTest(mediaId)
        assertTrue(QualityUpgrade.hasAnsweredFor(mediaId))

        QualityUpgrade.onPlaybackStarted(mediaId, rendition = null)

        assertFalse(
            "a second play reads the base cache entry, so last play's yes does not describe it",
            QualityUpgrade.hasAnsweredFor(mediaId),
        )
    }

    @Test
    fun `the upgraded item does not re-open its own verdict`() {
        QualityUpgrade.markAnsweredForTest(mediaId)

        QualityUpgrade.onPlaybackStarted(mediaId, rendition = "hifi")

        assertTrue(QualityUpgrade.hasAnsweredFor(mediaId))
    }

    @Test
    fun `a reverted item does not re-open its own verdict either`() {
        QualityUpgrade.markAnsweredForTest(mediaId)

        QualityUpgrade.onPlaybackStarted(mediaId, rendition = "original")

        assertTrue(QualityUpgrade.hasAnsweredFor(mediaId))
    }

    @Test
    fun `re-opening one track leaves the rest alone`() {
        QualityUpgrade.markAnsweredForTest(mediaId)
        QualityUpgrade.markAnsweredForTest("other")

        QualityUpgrade.onPlaybackStarted(mediaId, rendition = null)

        assertFalse(QualityUpgrade.hasAnsweredFor(mediaId))
        assertTrue(QualityUpgrade.hasAnsweredFor("other"))
    }

}
