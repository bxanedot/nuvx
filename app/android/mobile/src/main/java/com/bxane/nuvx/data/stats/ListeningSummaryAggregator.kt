package com.bxane.nuvx.data.stats

import com.bxane.nuvx.data.model.Song
import java.time.LocalDate
import java.util.Locale

internal class ListeningSummaryAggregator {
    private companion object {
        const val ARTISTS_TO_RESOLVE = 15
    }
    val tracks = HashMap<String, TrackEntry>()
    val artists = HashMap<String, NameEntry>()
    val albums = HashMap<String, NameEntry>()
    val hours = LongArray(24)
    val days = HashMap<String, Long>()
    val trackDays = HashMap<String, DayStats>()
    val artistDays = HashMap<String, DayStats>()
    val albumDays = HashMap<String, DayStats>()
    var earliest: String? = null

    fun add(bucket: StoredBucket) {
        bucket.tracks.forEach { entry ->
            tracks.merge(entry.id, entry.copy()) { a, b -> a.also { it.absorb(b) } }
        }
        bucket.artists.forEach { entry ->
            val lead = entry.copy(name = ListeningStats.primaryArtist(entry.name) ?: entry.name)
            artists.merge(lead.name.lowercase(Locale.ROOT), lead) { a, b -> a.also { it.absorb(b) } }
        }
        bucket.albums.forEach { entry ->
            val key = ListeningStats.albumKey(entry.id, entry.name, entry.sub.orEmpty())
            albums.merge(key, entry.copy()) { a, b -> a.also { it.absorb(b) } }
        }
        repeat(24) { hours[it] += bucket.hours.getOrElse(it) { 0L } }
        bucket.days.forEach { (day, ms) ->
            val date = "${bucket.month}-%02d".format(day)
            days[date] = (days[date] ?: 0L) + ms
        }
        if (earliest == null || bucket.month < earliest!!) earliest = bucket.month
    }

    fun addDaySubs(bucket: StoredBucket) {
        bucket.trackDays?.forEach { (dateStr, stats) ->
            trackDays.merge(dateStr, stats) { a, b -> DayStats(a.ms + b.ms, a.plays + b.plays) }
        }
        bucket.artistDays?.forEach { (dateStr, stats) ->
            artistDays.merge(dateStr, stats) { a, b -> DayStats(a.ms + b.ms, a.plays + b.plays) }
        }
        bucket.albumDays?.forEach { (dateStr, stats) ->
            albumDays.merge(dateStr, stats) { a, b -> DayStats(a.ms + b.ms, a.plays + b.plays) }
        }
    }

    fun add(bucket: StoredBucket, range: Pair<LocalDate, LocalDate>) {
        bucket.tracks.forEach { entry ->
            tracks.merge(entry.id, entry.copy()) { a, b -> a.also { it.absorb(b) } }
        }
        bucket.artists.forEach { entry ->
            val lead = entry.copy(name = ListeningStats.primaryArtist(entry.name) ?: entry.name)
            artists.merge(lead.name.lowercase(Locale.ROOT), lead) { a, b -> a.also { it.absorb(b) } }
        }
        bucket.albums.forEach { entry ->
            val key = ListeningStats.albumKey(entry.id, entry.name, entry.sub.orEmpty())
            albums.merge(key, entry.copy()) { a, b -> a.also { it.absorb(b) } }
        }
        addDaySubs(bucket)
        bucket.days.forEach { (day, ms) ->
            val candidate = "${bucket.month}-%02d".format(day)
            val date = runCatching { LocalDate.parse(candidate.replace("-", "/")) }.getOrNull()
                ?: return@forEach
            if (date >= range.first && date <= range.second) {
                days[candidate] = (days[candidate] ?: 0L) + ms
            }
        }
    }

    fun toSummary(period: ReplayPeriod, today: LocalDate): ReplaySummary {
        val weekRange = if (period.needsDayFilter()) period.range(today) else null

        val rankedSongs = if (weekRange != null) {
            tracks.values
                .mapNotNull { entry ->
                    val (ms, plays) = entry.weekRange(weekRange)
                    if (ms > 0) entry to (ms to plays) else null
                }
                .sortedWith(compareByDescending<Pair<TrackEntry, Pair<Long, Int>>> { it.second.first }
                    .thenByDescending { it.second.second })
                .map { (entry, msPlays) ->
                    val (ms, plays) = msPlays
                    RankedSong(
                        song = Song(
                            videoId = entry.id,
                            title = entry.title,
                            artist = entry.artist,
                            thumbnailUrl = entry.art,
                            artistId = entry.artistId,
                            albumId = entry.albumId,
                            albumName = entry.album,
                        ),
                        ms = ms,
                        plays = plays,
                    )
                }
        } else {
            tracks.values
                .sortedWith(compareByDescending<TrackEntry> { it.ms }.thenByDescending { it.plays })
                .map {
                    RankedSong(
                        song = Song(
                            videoId = it.id,
                            title = it.title,
                            artist = it.artist,
                            thumbnailUrl = it.art,
                            artistId = it.artistId,
                            albumId = it.albumId,
                            albumName = it.album,
                        ),
                        ms = it.ms,
                        plays = it.plays,
                    )
                }
        }

        val rankedArtists = if (weekRange != null) {
            artists.values
                .mapNotNull { entry ->
                    val (ms, plays) = entry.weekRange(weekRange)
                    if (ms > 0) entry to (ms to plays) else null
                }
                .sortedWith(compareByDescending<Pair<NameEntry, Pair<Long, Int>>> { it.second.first }
                    .thenByDescending { it.second.second })
                .map { (entry, msPlays) ->
                    val (ms, plays) = msPlays
                    RankedEntry(
                        title = entry.name,
                        subtitle = entry.sub,
                        artworkUrl = ArtistFacts.imageFor(entry.name) ?: entry.art,
                        browseId = entry.id ?: ArtistFacts.browseIdFor(entry.name),
                        ms = ms,
                        plays = plays,
                    )
                }
        } else {
            artists.values
                .sortedWith(compareByDescending<NameEntry> { it.ms }.thenByDescending { it.plays })
                .map {
                    RankedEntry(
                        title = it.name,
                        subtitle = it.sub,
                        artworkUrl = ArtistFacts.imageFor(it.name) ?: it.art,
                        browseId = it.id ?: ArtistFacts.browseIdFor(it.name),
                        ms = it.ms,
                        plays = it.plays,
                    )
                }
        }

        val rankedAlbums = if (weekRange != null) {
            albums.values
                .mapNotNull { entry ->
                    val (ms, plays) = entry.weekRange(weekRange)
                    if (ms > 0) entry to (ms to plays) else null
                }
                .sortedWith(compareByDescending<Pair<NameEntry, Pair<Long, Int>>> { it.second.first }
                    .thenByDescending { it.second.second })
                .map { (entry, msPlays) ->
                    val (ms, plays) = msPlays
                    RankedEntry(entry.name, entry.sub, entry.art, entry.id, ms, plays)
                }
        } else {
            albums.values
                .sortedWith(compareByDescending<NameEntry> { it.ms }.thenByDescending { it.plays })
                .map { RankedEntry(it.name, it.sub, it.art, it.id, it.ms, it.plays) }
        }

        val genreMs = LinkedHashMap<String, Long>()
        val genrePlays = LinkedHashMap<String, Int>()
        val genreArt = LinkedHashMap<String, String?>()
        rankedArtists.forEach { artist ->
            ArtistFacts.genresFor(artist.title).forEach { genre ->
                genreMs[genre] = (genreMs[genre] ?: 0L) + artist.ms
                genrePlays[genre] = (genrePlays[genre] ?: 0) + artist.plays
                if (genreArt[genre] == null) genreArt[genre] = artist.artworkUrl
            }
        }
        rankedArtists.take(ARTISTS_TO_RESOLVE)
            .filter { it.artworkUrl == null || it.browseId == null }
            .forEach { ArtistFacts.noticed(it.title) }

        val rankedGenres = genreMs.entries
            .sortedByDescending { it.value }
            .map {
                RankedEntry(
                    title = it.key,
                    subtitle = null,
                    artworkUrl = genreArt[it.key],
                    browseId = null,
                    ms = it.value,
                    plays = genrePlays[it.key] ?: 0,
                )
            }

        val busiest = days.entries.maxByOrNull { it.value }
        return ReplaySummary(
            period = period,
            label = period.label(today),
            totalMs = rankedSongs.sumOf { it.ms },
            totalPlays = rankedSongs.sumOf { it.plays },
            songs = rankedSongs,
            artists = rankedArtists,
            albums = rankedAlbums,
            genres = rankedGenres,
            hourOfDay = hours.toList(),
            busiestDay = busiest?.key,
            busiestDayMs = busiest?.value ?: 0L,
            distinctSongs = rankedSongs.size,
            distinctArtists = rankedArtists.size,
            distinctAlbums = rankedAlbums.size,
            since = earliest,
        )
    }
}

