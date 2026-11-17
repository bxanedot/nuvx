package com.licht.nuvx.data

import com.licht.nuvx.data.model.LikeStatus




















fun shouldSkipAfterDislike(
    previousStatus: LikeStatus?,
    targetVideoId: String,
    currentVideoId: String?,
): Boolean = previousStatus != null &&
    previousStatus != LikeStatus.DISLIKE &&
    targetVideoId == currentVideoId
