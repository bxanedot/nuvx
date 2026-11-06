@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bxane.nuvx

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.media3.common.C
import androidx.media3.common.Player

internal fun activeAudioEffectSessionId(playWhenReady: Boolean, playbackState: Int, audioSessionId: Int): Int =
    audioSessionId.takeIf {
        playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED && it != C.AUDIO_SESSION_ID_UNSET
    } ?: C.AUDIO_SESSION_ID_UNSET

internal fun Context.audioEffectSessionIntent(action: String, sessionId: Int): Intent =
    Intent(action)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
