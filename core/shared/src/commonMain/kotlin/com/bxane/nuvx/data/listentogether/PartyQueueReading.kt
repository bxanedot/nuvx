package com.bxane.nuvx.data.listentogether

fun partyQueueIndexOf(queue: PartyQueue, playback: PartyPlayback, videoId: String?): Int {
    if (videoId == null) return -1
    val items = queue.items
    val hint = if (playback.queueSeq == queue.seq) playback.queueIndex else queue.index
    if (hint in items.indices && items[hint].videoId == videoId) return hint
    var best = -1
    var bestDistance = Int.MAX_VALUE
    for (i in items.indices) {
        if (items[i].videoId != videoId) continue
        val distance = if (i >= hint) i - hint else (hint - i) * 2 + 1
        if (distance < bestDistance) {
            best = i
            bestDistance = distance
        }
    }
    return best
}

fun partyUpcomingAfter(queue: PartyQueue, playback: PartyPlayback, videoId: String?): List<PartyTrack> {
    val at = partyQueueIndexOf(queue, playback, videoId)
    return if (at < 0) emptyList() else queue.items.subList(at + 1, queue.items.size)
}
