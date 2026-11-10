package com.bxane.nuvx.wear

data class WatchOutputState(
    val available: Boolean = false,
    val name: String? = null,
    val active: Boolean = false,
    val volume: Float = 0f,
)
