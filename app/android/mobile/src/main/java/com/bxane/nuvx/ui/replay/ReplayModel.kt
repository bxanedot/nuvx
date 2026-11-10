package com.bxane.nuvx.ui.replay

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.bxane.nuvx.data.stats.ArtistFacts
import com.bxane.nuvx.data.stats.ListeningStats
import com.bxane.nuvx.data.stats.ReplayPeriod
import com.bxane.nuvx.data.stats.ReplaySummary
import com.bxane.nuvx.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class ReplayState(
    val period: ReplayPeriod,
    val summary: ReplaySummary?,
    val loading: Boolean,
    val memberSince: String?,
)

@Composable
fun rememberReplayState(active: Boolean): Pair<ReplayState, (ReplayPeriod) -> Unit> {
    var period by rememberSaveable { mutableStateOf(ReplayPeriod.THIS_YEAR) }
    var summary by remember { mutableStateOf<ReplaySummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var memberSince by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        memberSince = withContext(Dispatchers.IO) {
            ListeningStats.months().firstOrNull()?.let {
                "%02d/%02d".format(Locale.ROOT, it.monthValue, it.year % 100)
            }
        }
    }
    LaunchedEffect(period, active) {
        if (!active) return@LaunchedEffect
        loading = summary == null
        summary = ListeningStats.summary(period)
        loading = false

        ArtistFacts.revision.drop(1).collectLatest {
            delay(SETTLE_MILLIS)
            summary = ListeningStats.summary(period)
        }
    }
    return ReplayState(period, summary, loading, memberSince) to
        { next: ReplayPeriod -> period = next }
}

private const val SETTLE_MILLIS = 1_200L

data class HeadlineRun(val text: String, val bold: Boolean)

private fun runs(vararg parts: Pair<String, Boolean>): List<HeadlineRun> =
    parts.map { HeadlineRun(it.first, it.second) }

fun ReplaySummary.storyHeadline(context: Context, page: ReplayStoryPage): List<HeadlineRun> = when (page) {
    ReplayStoryPage.INTRO -> runs(
        context.getString(R.string.replay_intro_start) to false,
        " " to false,
        "Replay" to true,
        " " to false,
        context.getString(R.string.replay_intro_end) to false,
    )
    ReplayStoryPage.MINUTES -> runs(
        context.getString(R.string.replay_minutes_start) to false,
        " " to false,
        context.resources.getQuantityString(
            R.plurals.replay_minutes_unit,
            formatMinutes(totalMs).replace(",", "").toIntOrNull() ?: 0,
            formatMinutes(totalMs),
        ) to true,
        " " to false,
        context.getString(R.string.replay_minutes_end) to false,
    )
    ReplayStoryPage.SONGS -> runs(
        context.getString(R.string.replay_songs_start) to false,
        " " to false,
        context.replayCount(totalPlays, R.plurals.replay_song_count) to true,
        " " to false,
        (if (period == ReplayPeriod.THIS_WEEK) context.getString(R.string.replay_songs_end_week) else context.getString(R.string.replay_songs_end)) to false,
    )
    ReplayStoryPage.ARTISTS -> runs(
        (if (period == ReplayPeriod.THIS_WEEK) context.getString(R.string.replay_artist_start_week) else context.getString(R.string.replay_artist_start)) to false,
        " " to false,
        context.getString(R.string.replay_artist_focus) to true,
        " " to false,
        context.getString(R.string.replay_artist_end) to false,
    )
    ReplayStoryPage.ALBUMS -> runs(
        context.getString(R.string.replay_album_start) to false,
        " " to false,
        context.getString(R.string.replay_album_focus) to true,
        " " to false,
        context.getString(R.string.replay_album_end) to false,
    )
    ReplayStoryPage.GENRES -> runs(
        context.getString(R.string.replay_genre_start) to false,
        " " to false,
        context.getString(R.string.replay_genre_focus) to true,
        " " to false,
        context.getString(R.string.replay_genre_end) to false,
    )
    ReplayStoryPage.HABITS -> runs(
        context.getString(R.string.replay_habits_start) to false,
        " " to false,
        context.replayCount(distinctSongs, R.plurals.replay_song_count) to true,
        " " to false,
        context.getString(R.string.replay_habits_middle) to false,
        " " to false,
        context.replayCount(distinctArtists, R.plurals.replay_artist_count) to true,
        "." to false,
    )
    ReplayStoryPage.SUMMARY -> runs(
        context.getString(R.string.replay_summary_start) to false,
        " " to false,
        if (period == ReplayPeriod.THIS_WEEK) context.getString(R.string.replay_summary_end_week) to true else label to true,
        if (period == ReplayPeriod.THIS_WEEK) " in music." to false else " in music." to false,
    )
}

fun ReplayPeriod.localizedChip(context: Context): String = when (this) {
    ReplayPeriod.THIS_WEEK -> context.getString(R.string.replay_this_week)
    ReplayPeriod.THIS_MONTH -> context.getString(R.string.this_month)
    ReplayPeriod.THIS_YEAR -> context.getString(R.string.this_year)
    ReplayPeriod.ALL_TIME -> context.getString(R.string.all_time)
}

fun ReplayPeriod.localizedTab(context: Context): String = when (this) {
    ReplayPeriod.THIS_WEEK -> context.getString(R.string.replay_period_weekly)
    ReplayPeriod.THIS_MONTH -> context.getString(R.string.replay_period_monthly)
    ReplayPeriod.THIS_YEAR -> context.getString(R.string.replay_period_yearly)
    ReplayPeriod.ALL_TIME -> context.getString(R.string.replay_period_lifetime)
}

fun ReplaySummary.localizedLabel(context: Context): String = when (period) {
    ReplayPeriod.THIS_WEEK -> "This week"
    ReplayPeriod.THIS_MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        .format(LocalDate.now())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    ReplayPeriod.THIS_YEAR -> LocalDate.now().year.toString()
    ReplayPeriod.ALL_TIME -> context.getString(R.string.all_time)
}

fun ReplaySummary.storyArtwork(page: ReplayStoryPage): String? {
    val pool = (
        songs.map { it.song.thumbnailUrl } +
            artists.map { it.artworkUrl } +
            albums.map { it.artworkUrl }
        ).filterNotNull().distinct()
    val pinned = when (page) {
        ReplayStoryPage.SONGS -> songs.firstOrNull()?.song?.thumbnailUrl
        ReplayStoryPage.ARTISTS -> artists.firstOrNull()?.artworkUrl
        ReplayStoryPage.ALBUMS -> albums.firstOrNull()?.artworkUrl
        else -> null
    }
    if (pinned != null) return pinned
    if (pool.isEmpty()) return null
    return pool[page.ordinal % pool.size]
}

fun storyHue(page: ReplayStoryPage): Float = when (page) {
    ReplayStoryPage.INTRO -> 0f
    ReplayStoryPage.MINUTES -> 40f
    ReplayStoryPage.SONGS -> 95f
    ReplayStoryPage.ARTISTS -> 145f
    ReplayStoryPage.ALBUMS -> 195f
    ReplayStoryPage.GENRES -> 240f
    ReplayStoryPage.HABITS -> 285f
    ReplayStoryPage.SUMMARY -> 325f
}

enum class ReplayStoryPage {
    INTRO, MINUTES, ARTISTS, SONGS, ALBUMS, GENRES, HABITS, SUMMARY;

    companion object {
        val ordered: List<ReplayStoryPage> = entries
    }
}

fun formatListening(context: Context, ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> context.getString(R.string.minutes_short, minutes)
        minutes < 1_440 -> context.getString(R.string.hours_minutes_short, minutes / 60, minutes % 60)
        else -> context.getString(R.string.minutes_grouped_short, grouped(minutes))
    }
}

fun formatMinutes(ms: Long): String = grouped(ms / 60_000)

fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)

fun formatHour(context: Context, hour: Int): String = when (hour) {
    0 -> context.getString(R.string.midnight)
    12 -> context.getString(R.string.midday)
    in 1..11 -> context.getString(R.string.hour_am, hour)
    else -> context.getString(R.string.hour_pm, hour - 12)
}

fun formatDay(context: Context, iso: String): String = runCatching {
    LocalDate.parse(iso).format(
        DateTimeFormatter.ofPattern("d MMMM", context.resources.configuration.locales[0]),
    )
}.getOrDefault(iso)

fun Context.replayCount(count: Int, plural: Int): String =
    resources.getQuantityString(plural, count, grouped(count.toLong()))

data class ReplayRow(
    val key: String,
    val rank: Int,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val ms: Long,
    val plays: Int,
)

fun ReplaySummary.songRows(limit: Int): List<ReplayRow> =
    songs.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.song.videoId,
            rank = index + 1,
            title = entry.song.title,
            subtitle = entry.song.artist.takeIf { it.isNotBlank() },
            artworkUrl = entry.song.thumbnailUrl,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

fun ReplaySummary.artistRows(limit: Int): List<ReplayRow> =
    artists.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.title,
            rank = index + 1,
            title = entry.title,
            subtitle = null,
            artworkUrl = entry.artworkUrl,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

fun ReplaySummary.albumRows(limit: Int): List<ReplayRow> =
    albums.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.title + "|" + entry.subtitle.orEmpty(),
            rank = index + 1,
            title = entry.title,
            subtitle = entry.subtitle,
            artworkUrl = entry.artworkUrl,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

fun ReplaySummary.genreRows(limit: Int): List<ReplayRow> =
    genres.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.title,
            rank = index + 1,
            title = entry.title,
            subtitle = null,
            artworkUrl = null,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

data class ReplayHeroCard(
    val label: String,
    val value: String,
    val detail: String?,
    val artworkUrl: String?,
    val page: ReplayStoryPage,
)

fun ReplaySummary.cards(context: Context): List<ReplayHeroCard> = buildList {
    add(
        ReplayHeroCard(
            label = context.getString(R.string.minutes_listened),
            value = formatMinutes(totalMs),
            detail = "${context.replayCount(totalPlays, R.plurals.replay_play_count)} · " +
                localizedLabel(context),
            artworkUrl = songs.firstOrNull()?.song?.thumbnailUrl,
            page = ReplayStoryPage.MINUTES,
        ),
    )
    songs.firstOrNull()?.let {
        add(
            ReplayHeroCard(
                label = context.getString(R.string.top_song),
                value = it.song.title,
                detail = "${it.song.artist} · ${context.replayCount(it.plays, R.plurals.replay_play_count)}",
                artworkUrl = it.song.thumbnailUrl,
                page = ReplayStoryPage.SONGS,
            ),
        )
    }
    artists.firstOrNull()?.let {
        add(
            ReplayHeroCard(
                label = context.getString(R.string.top_artist),
                value = it.title,
                detail = "${formatListening(context, it.ms)} · " +
                    context.replayCount(it.plays, R.plurals.replay_play_count),
                artworkUrl = it.artworkUrl,
                page = ReplayStoryPage.ARTISTS,
            ),
        )
    }
    albums.firstOrNull()?.let {
        add(
            ReplayHeroCard(
                label = context.getString(R.string.top_album),
                value = it.title,
                detail = listOfNotNull(it.subtitle, formatListening(context, it.ms)).joinToString(" · "),
                artworkUrl = it.artworkUrl,
                page = ReplayStoryPage.ALBUMS,
            ),
        )
    }
}
