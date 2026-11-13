package com.bxane.nuvx.playback

import com.bxane.nuvx.data.model.Song
import kotlin.random.Random








object QueueShuffle {


    private var original: List<String> = emptyList()

    fun isShuffledStart(songs: List<Song>, startIndex: Int): List<Song> {
        original = songs.map { it.videoId }
        val rest = songs.filterIndexed { i, _ -> i != startIndex }.shuffled(Random.Default)
        return listOf(songs[startIndex]) + rest
    }


    internal fun moves(
        current: List<String>,
        from: Int,
        target: List<String>,
    ): List<Pair<Int, Int>> {
        val ids = current.toMutableList()
        val out = mutableListOf<Pair<Int, Int>>()
        target.forEachIndexed { offset, id ->
            val to = from + offset
            if (ids.getOrNull(to) == id) return@forEachIndexed
            val at = (to + 1 until ids.size).firstOrNull { ids[it] == id }
                ?: return@forEachIndexed
            out += at to to
            ids.add(to, ids.removeAt(at))
        }
        return out
    }


    fun shuffleQueue(queue: List<Song>, playingIndex: Int): List<Song> {
        if (queue.isEmpty() || playingIndex >= queue.size) return queue
        original = queue.map { it.videoId }
        val from = playingIndex + 1
        val autoplay = queue.drop(from)
            .mapIndexedNotNull { i, s -> if (s.fromAutoplay) i else null }
            .toSet()
        val ownShuffled = queue.drop(from)
            .filterIndexed { i, _ -> i !in autoplay }
            .shuffled(Random.Default)
        val mixShuffled = queue.drop(from)
            .filterIndexed { i, _ -> i in autoplay }
            .shuffled(Random.Default)
        return queue.take(from) + ownShuffled + mixShuffled
    }


    fun restoreQueue(queue: List<Song>, playingIndex: Int): List<Song> {
        if (original.isEmpty()) return queue
        val from = playingIndex + 1
        val upcomingIds = queue.drop(from).map { it.videoId }.toMutableList()
        val restored = original.filter { upcomingIds.remove(it) }
            .mapNotNull { id -> queue.firstOrNull { it.videoId == id } } +
            upcomingIds.mapNotNull { id -> queue.firstOrNull { it.videoId == id } }
        original = emptyList()
        return queue.take(from) + restored
    }
}
