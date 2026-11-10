package com.bxane.nuvx.data.model

import kotlinx.serialization.Serializable

@Serializable
data class WatchTarget(
    val playlistId: String,
    val videoId: String? = null,
    val params: String? = null,
)

@Serializable
enum class TopPickKind {
    LATEST_RELEASE,

    PERSONAL_STATION,

    MOOD,

    ARTIST_AND_SIMILAR,

    FEATURED_ARTIST,

    NEW_RELEASE_MIX,

    DISCOVERY_STATION,
}

@Serializable
enum class TopPickMood(val params: String, val shelfWords: List<String> = emptyList()) {
    ENERGY(ENERGIZE),
    RELAX(CHILL_CATEGORY, listOf("unwind", "peaceful", "relaxing", "laidback")),
    FEELING_BLUE(SAD, listOf("sad", "melanchol", "lonely")),
    FEEL_GOOD(FEEL_GOOD_CATEGORY),
    FOCUS(FOCUS_CATEGORY),
    HEARTBREAK(SAD, listOf("heartbreak", "breakup", "break-up", "broken", "heartache")),
    LOVE(ROMANCE),

    AFTER_HOURS(CHILL_CATEGORY, listOf("deep grooves", "electronic", "jazz", "r&b")),
    ALONE_TIME(CHILL_CATEGORY, listOf("mellow", "acoustic", "laidback")),
    CHILL(CHILL_CATEGORY, listOf("chill", "chilled")),
    COMMUTING(COMMUTE),
    EATING_COOKING(CHILL_CATEGORY, listOf("coffee", "soul", "jazz", "r&b")),
    EVENING(CHILL_CATEGORY, listOf("unwind", "acoustic", "laidback", "jazz")),
    FITNESS(WORKOUT, listOf("gym", "cardio", "essentials")),
    GAMING(GAMING_CATEGORY),
    HOME(CHILL_CATEGORY, listOf("coffee shop", "chill pop", "laidback")),
    MORNING(FEEL_GOOD_CATEGORY, listOf("happy", "confident", "pop")),
    MOTIVATION(ENERGIZE, listOf("motivation", "power", "beast")),
    OUTDOORS(WORKOUT, listOf("outdoor", "running")),
    PARTY(PARTY_CATEGORY),
    SLEEP(SLEEP_CATEGORY),
    SOCIAL(PARTY_CATEGORY, listOf("party today", "chill party", "throwback")),
    SPORTS(ENERGIZE, listOf("beast", "power", "hip-hop")),
    SUMMERTIME(FEEL_GOOD_CATEGORY, listOf("dance", "kicking back", "feel-good pop")),
    HOLIDAY(FEEL_GOOD_CATEGORY, listOf("throwback", "happy")),
    WEEKEND(PARTY_CATEGORY, listOf("party today", "dance")),
    WELLBEING(CHILL_CATEGORY, listOf("mindfulness", "meditation", "yoga")),
    WORK(FOCUS_CATEGORY, listOf("flow", "instrumental", "reading")),
    ;

    val isCoreMood: Boolean get() = ordinal <= LOVE.ordinal

    companion object {
        const val CATEGORY_BROWSE_ID = "FEmusic_moods_and_genres_category"
    }
}

private const val CHILL_CATEGORY = "ggMPOg1uX1JOQWZFeDByc2Jm"
private const val COMMUTE = "ggMPOg1uX044Z2o5WERLckpU"
private const val ENERGIZE = "ggMPOg1uX2lRZUZiMnNrQnJW"
private const val FEEL_GOOD_CATEGORY = "ggMPOg1uXzZQbDB5eThLRTQ3"
private const val FOCUS_CATEGORY = "ggMPOg1uX0NvNGNhWThMYWRh"
private const val GAMING_CATEGORY = "ggMPOg1uX3NmUVV4Vzl3WGQ0"
private const val PARTY_CATEGORY = "ggMPOg1uX0pmQ0s2V0JRclZs"
private const val ROMANCE = "ggMPOg1uX0FzQ2FhZWtUY211"
private const val SAD = "ggMPOg1uX0JLQ0gySWZKZVY1"
private const val SLEEP_CATEGORY = "ggMPOg1uX1MxaFQ3Z0JMZkN4"
private const val WORKOUT = "ggMPOg1uX09LWkhnTjRGRUJh"

@Serializable
data class TopPick(
    val kind: TopPickKind,
    val title: String = "",
    val subtitle: String = "",
    val artistName: String? = null,
    val artistBrowseId: String? = null,
    val images: List<String> = emptyList(),
    val browseId: String? = null,
    val watch: WatchTarget? = null,
    val mood: TopPickMood? = null,
    val exclude: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
)
