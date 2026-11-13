package com.bxane.nuvx.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadePlayerListenerTest {
    @Test
    fun mediaItemTransitionsKeepTheirExistingDispatch() {
        var bailCount = 0
        var ranOutCount = 0
        val listener = CrossfadePlayerListener(
            readAhead = CrossfadeReadAheadController("test"),
            isArming = { false },
            isFading = { false },
            handedOff = { false },
            incoming = { null },
            bail = { bailCount++ },
            ranOutBeforeHandoff = { ranOutCount++ },
            handOff = { _, _ -> },
        )

        listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)

        assertEquals(2, bailCount)
        assertEquals(1, ranOutCount)
    }
}
