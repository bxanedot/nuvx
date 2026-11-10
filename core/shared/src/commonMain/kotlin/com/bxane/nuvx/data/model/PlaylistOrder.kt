package com.bxane.nuvx.data.model

data class PlaylistMove(val setVideoId: String, val before: String?)

fun playlistMoves(current: List<String>, target: List<String>): List<PlaylistMove> {
    if (current == target) return emptyList()
    if (current.size != target.size || current.toSet() != target.toSet() || current.toSet().size != current.size) {
        return emptyList()
    }
    val position = current.withIndex().associate { (index, id) -> id to index }
    val stay = longestIncreasingRun(target.map { position.getValue(it) })
        .mapTo(HashSet()) { target[it] }
    val moves = ArrayList<PlaylistMove>()
    for (index in target.indices.reversed()) {
        val id = target[index]
        if (id in stay) continue
        moves += PlaylistMove(id, target.getOrNull(index + 1))
    }
    return moves
}

private fun longestIncreasingRun(values: List<Int>): List<Int> {
    if (values.isEmpty()) return emptyList()
    val tails = IntArray(values.size)
    val previous = IntArray(values.size) { -1 }
    var length = 0
    for (i in values.indices) {
        var low = 0
        var high = length
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[tails[mid]] < values[i]) low = mid + 1 else high = mid
        }
        if (low > 0) previous[i] = tails[low - 1]
        tails[low] = i
        if (low == length) length++
    }
    val run = ArrayList<Int>(length)
    var at = tails[length - 1]
    while (at >= 0) {
        run += at
        at = previous[at]
    }
    return run.asReversed()
}
