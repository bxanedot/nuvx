package com.bxane.nuvx.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow







object PlayerDeepLink {

    private val _pending = MutableStateFlow(false)


    val pending: StateFlow<Boolean> = _pending.asStateFlow()


    fun consume(): Boolean {
        _pending.value = true
        return true
    }


    fun handled() {
        _pending.value = false
    }
}
