package com.bxane.nuvx.core

object PlaybackSpeed {
    private const val KEY = "playbackSpeed"
    const val DEFAULT = 1f
    val options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    fun normalize(speed: Float): Float = speed.takeIf(options::contains) ?: DEFAULT

    fun read(store: KeyValueStore): Float = store.get(KEY)?.toFloatOrNull()?.let(::normalize) ?: DEFAULT

    fun write(store: KeyValueStore, speed: Float): Float {
        val selected = normalize(speed)
        store.put(KEY, selected.toString())
        return selected
    }

    fun label(speed: Float): String = "${if (speed % 1f == 0f) speed.toInt() else speed}×"
}
