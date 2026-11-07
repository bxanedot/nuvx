package com.bxane.nuvx

import com.bxane.nuvx.core.MediaKind
import com.bxane.nuvx.core.PlayerState

internal fun PlayerState.canEnterPictureInPicture(supported: Boolean, watch: Boolean, tv: Boolean): Boolean =
    supported && !watch && !tv && isPlaying && current?.mediaKind == MediaKind.VIDEO
