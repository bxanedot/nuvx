package com.bxane.nuvx.download

import com.bxane.nuvx.data.model.Song
import java.util.Locale

internal object DownloadCollectionCatalog {
    fun tracksOnlyIn(
        id: String,
        collections: Map<String, SavedCollection>,
        saved: Map<String, String>,
    ): List<String> {
        val record = collections[id] ?: return emptyList()
        val dropped = droppedWith(id, collections)
        val others = collections.values
            .filter { it.id !in dropped && (record.artist || !it.artist) }
            .flatMapTo(HashSet()) { it.videoIds }
        val otherFiles = others.mapNotNullTo(HashSet()) { saved[it] }
        return dropped.flatMap { collections.getValue(it).videoIds }.distinct()
            .filter { it !in others && saved[it] !in otherFiles }
    }

    fun savedReleases(
        collections: Map<String, SavedCollection>,
        onDisk: Map<String, String>,
    ): List<SavedCollection> {
        if (collections.isEmpty()) return emptyList()
        return collections.values
            .filter { record -> record.videoIds.any { it in onDisk } }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun collectionsAmong(
        collections: Map<String, SavedCollection>,
        saved: Map<String, String>,
        songs: List<Song>,
    ): List<DownloadedCollection> {
        if (songs.isEmpty() || collections.isEmpty()) return emptyList()
        val byId = songs.associateBy { it.videoId }
        val byUri = songs.mapNotNull { song -> song.localUri?.let { it to song } }.toMap()
        return collections.values
            .mapNotNull { record ->
                val tracks = record.videoIds
                    .mapNotNull { id -> byId[id] ?: saved[id]?.let(byUri::get) }
                    .distinctBy { it.localUri ?: it.videoId }
                if (tracks.isEmpty()) {
                    null
                } else {
                    DownloadedCollection(
                        id = record.id,
                        title = record.title,
                        subtitle = record.subtitle,
                        thumbnailUrl = record.thumbnailUrl ?: tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        playlist = record.playlist,
                        songs = tracks,
                        artist = record.artist,
                    )
                }
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun droppedWith(id: String, collections: Map<String, SavedCollection>): Set<String> {
        val record = collections[id] ?: return emptySet()
        if (!record.artist) return setOf(id)
        val catalogue = record.videoIds.toHashSet()
        return collections.values
            .filter { it.id == id || (!it.artist && !it.playlist && it.videoIds.isNotEmpty() && catalogue.containsAll(it.videoIds)) }
            .mapTo(HashSet()) { it.id }
    }
}
