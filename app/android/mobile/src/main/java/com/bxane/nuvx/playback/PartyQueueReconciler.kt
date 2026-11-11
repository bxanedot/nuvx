package com.bxane.nuvx.playback

import androidx.media3.common.Player
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.listentogether.partyQueueIndexOf

internal class PartyQueueReconciler {
    fun reconcile(party: ListenTogether.State, player: Player, maxUpcomingQueue: Int) {
        val partyQueue = party.queue.items
        if (partyQueue.isEmpty()) return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = player.currentMediaItem?.mediaId ?: return
        val partyIndex = partyQueueIndexOf(party.queue, party.playback, currentMediaId)
        if (partyIndex < 0) return

        val desiredUpcoming = partyQueue.subList(partyIndex + 1, partyQueue.size).take(maxUpcomingQueue)
        val desiredUpcomingIds = desiredUpcoming.map { it.videoId }

        val localUpcomingIds = (currentIndex + 1 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaId
        }

        if (localUpcomingIds != desiredUpcomingIds) {
            if (desiredUpcomingIds.startsWith(localUpcomingIds)) {
                player.addMediaItems(desiredUpcoming.drop(localUpcomingIds.size).map { it.toSong().toMediaItem() })
                return
            }

            if (localUpcomingIds.startsWith(desiredUpcomingIds)) {
                player.removeMediaItems(currentIndex + 1 + desiredUpcomingIds.size, player.mediaItemCount)
                return
            }

            val singleMove = if (localUpcomingIds.size == desiredUpcomingIds.size) {
                detectSingleMove(localUpcomingIds, desiredUpcomingIds)
            } else {
                null
            }

            if (singleMove != null) {
                player.moveMediaItem(
                    currentIndex + 1 + singleMove.fromIndex,
                    currentIndex + 1 + singleMove.toIndex,
                )
            } else {
                player.replaceMediaItems(
                    currentIndex + 1,
                    player.mediaItemCount,
                    desiredUpcoming.map { it.toSong().toMediaItem() },
                )
            }
        }
    }

    private fun <T> List<T>.startsWith(prefix: List<T>): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
