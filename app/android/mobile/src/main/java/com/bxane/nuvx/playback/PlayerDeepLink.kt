package com.bxane.nuvx.playback

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlayerDeepLink {

    const val EXTRA_OPEN_PLAYER = "nuvx.openPlayer"

    private val _pending = MutableStateFlow(false)

    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun consume(intent: Intent?): Boolean {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) return false
        intent.removeExtra(EXTRA_OPEN_PLAYER)
        _pending.value = true
        return true
    }

    fun handled() {
        _pending.value = false
    }
}
