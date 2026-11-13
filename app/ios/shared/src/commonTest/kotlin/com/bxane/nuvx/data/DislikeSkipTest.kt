package com.bxane.nuvx.data

import com.bxane.nuvx.data.model.LikeStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue








class DislikeSkipTest {

    @Test
    fun `disliking the track that is playing skips it`() {
        assertTrue(
            shouldSkipAfterDislike(
                previousStatus = LikeStatus.INDIFFERENT,
                targetVideoId = "abc",
                currentVideoId = "abc",
            ),
        )
    }

    @Test
    fun `disliking a liked track that is playing still skips it`() {


        assertTrue(
            shouldSkipAfterDislike(
                previousStatus = LikeStatus.LIKE,
                targetVideoId = "abc",
                currentVideoId = "abc",
            ),
        )
    }

    @Test
    fun `taking a dislike back off does not skip`() {
        assertFalse(
            shouldSkipAfterDislike(
                previousStatus = LikeStatus.DISLIKE,
                targetVideoId = "abc",
                currentVideoId = "abc",
            ),
        )
    }

    @Test
    fun `disliking a track that is not playing does not skip`() {


        assertFalse(
            shouldSkipAfterDislike(
                previousStatus = LikeStatus.INDIFFERENT,
                targetVideoId = "abc",
                currentVideoId = "xyz",
            ),
        )
    }

    @Test
    fun `nothing playing means nothing to skip`() {
        assertFalse(
            shouldSkipAfterDislike(
                previousStatus = LikeStatus.INDIFFERENT,
                targetVideoId = "abc",
                currentVideoId = null,
            ),
        )
    }

    @Test
    fun `a rating nobody sent skips nothing`() {


        assertFalse(
            shouldSkipAfterDislike(
                previousStatus = null,
                targetVideoId = "abc",
                currentVideoId = "abc",
            ),
        )
    }
}
