package com.licht.nuvx.ui

/**
 * Keep only one actionable reorder recovery checkpoint.
 * The snackbar owns its snapshot; superseded callbacks cannot restore older data.
 */
internal class SingleReorderCheckpoint {
    private var nextId = 0L
    private var current: Long? = null

    fun issue(): Long = (++nextId).also { current = it }

    fun isCurrent(id: Long): Boolean = current == id

    fun complete(id: Long) {
        if (isCurrent(id)) current = null
    }
}
