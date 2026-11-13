package com.bxane.nuvx.data

import com.bxane.nuvx.data.model.LikeStatus




















fun shouldSkipAfterDislike(
    previousStatus: LikeStatus?,
    targetVideoId: String,
    currentVideoId: String?,
): Boolean = previousStatus != null &&
    previousStatus != LikeStatus.DISLIKE &&
    targetVideoId == currentVideoId
