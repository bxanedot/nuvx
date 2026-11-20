package com.licht.nuvx.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android Auto informational rows are never playable, even if selected. */
class AutoInfoItemPolicyTest {
    @Test
    fun helpAndUnavailableCardsAreNotPlayableTargets() {
        for (id in listOf(
            "msg:login_required",
            "msg:empty_recents",
            "msg:empty_downloads",
            "msg:unavailable|abcdefghijk",
            "msg:empty_local",
        )) {
            assertTrue(isAutoInformationItem(id))
        }
    }

    @Test
    fun realLibraryAndTrackIdentifiersRemainAvailable() {
        for (id in listOf(
            "root", "recents", "downloads", "local_music",
            "playlist:VL1234", "browse:MPREb_album", "abcdefghijk",
        )) {
            assertFalse(isAutoInformationItem(id))
        }
    }
}
