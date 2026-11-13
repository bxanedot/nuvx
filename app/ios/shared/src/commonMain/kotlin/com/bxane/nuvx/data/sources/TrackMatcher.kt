package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.model.Song
import kotlin.math.abs
































object TrackMatcher {


    data class Target(
        val title: String,
        val artist: String = "",

        val durationSec: Int? = null,

        val album: String? = null,

        val isExplicit: Boolean? = null,

        val isVideo: Boolean = false,
    )

    fun targetOf(song: Song): Target = Target(
        title = song.title,
        artist = song.artist,
        durationSec = secondsOf(song.durationText),
        album = song.albumName,
        isExplicit = song.isExplicit,
        isVideo = song.isVideo,
    )

















    fun queries(target: Target): List<String> {
        val title = searchableTitle(target.title, target.artist)
        if (title.isBlank()) return emptyList()
        val artist = primaryArtist(target.artist)
        if (artist.isBlank()) return listOf(title)
        return listOf("$title $artist", title)
    }


    internal fun searchableTitle(title: String, artist: String = ""): String =
        parseTitle(title, artist).let { (it.words + it.versions).joinToString(" ") }


    internal fun primaryArtist(artist: String): String =
        artist.lowercase().split(ARTIST_SEPARATORS).firstOrNull()?.trim().orEmpty()


    internal fun sharesArtist(wanted: String, got: String): Boolean {
        val want = artistNames(wanted)
        val have = artistNames(got)
        return want.isNotEmpty() && have.isNotEmpty() &&
            want.any { w -> have.any { h -> sameArtist(w, h) } }
    }













    fun best(candidates: List<Song>, target: Target): Song? = ranked(candidates, target).firstOrNull()











    fun bestOfficialAudioForVideo(candidates: List<Song>, target: Target): Song? = candidates
        .filter { candidate -> sameOfficialTitleAndArtist(candidate, target) }


        .sortedByDescending { candidate ->
            val wanted = target.durationSec
            val got = secondsOf(candidate.durationText)
            if (wanted == null || got == null) 0 else -abs(wanted - got)
        }
        .firstOrNull()







    fun rankedOfficialVideos(candidates: List<Song>, target: Target): List<Song> = candidates
        .filter { candidate -> sameOfficialTitleAndArtist(candidate, target) }
        .sortedBy { candidate ->
            val wanted = target.durationSec
            val got = secondsOf(candidate.durationText)
            if (wanted == null || got == null) Int.MAX_VALUE else abs(wanted - got)
        }

    private fun sameOfficialTitleAndArtist(candidate: Song, target: Target): Boolean {
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        return wanted.core.isNotEmpty() && wanted.core == got.core &&
            wanted.versions == got.versions && sharesArtist(target.artist, candidate.artist)
    }








    fun ranked(candidates: List<Song>, target: Target): List<Song> =
        candidates
            .mapNotNull { candidate -> score(candidate, target)?.let { candidate to it } }






            .let { scored ->
                val credited = scored.filter { (_, score) ->


                    score >= ARTIST_SHARED
                }
                credited.ifEmpty { scored }
            }
            .sortedByDescending { it.second }
            .map { it.first }








    fun score(candidate: Song, target: Target): Int? {
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        if (wanted.core.isEmpty() || got.core.isEmpty()) return null
        if (wanted.core != got.core) return null



        if (wanted.versions != got.versions) return null

        val creditedArtist = artistScore(target.artist, candidate.artist)
        val duration = durationScore(
            wanted = target.durationSec,
            got = secondsOf(candidate.durationText),







            allowVideoDrift = creditedArtist != null,
        ) ?: return null
        val artist = creditedArtist

















            ?: CREDITS_DISAGREE.takeIf {
                !target.isVideo && withinSeconds(candidate, target, CREDIT_OVERRIDE_SEC)
            }
            ?: return null
        val explicit = explicitScore(target.isExplicit, candidate.isExplicit) ?: return null
        return BASE + artist + duration +
            albumScore(target.album, candidate.albumName) + explicit +
            contextScore(wanted, got)
    }











    fun hasConflictingAlbums(candidates: List<Song>, target: Target): Boolean {
        if (!target.album.isNullOrBlank()) return false
        val comparable = if (target.durationSec != null) {
            candidates.filter { withinSeconds(it, target, DURATION_LIMIT_SEC) }
        } else {
            candidates
        }




        if (target.durationSec != null && comparable.isEmpty()) return false
        return comparable.mapNotNull { albumKey(it.albumName) }.distinct().size > 1
    }










    fun uniquelyMostCreditedCloseMatch(candidates: List<Song>, target: Target): Song? {
        val close = candidates.filter { withinSeconds(it, target, DURATION_LIMIT_SEC) }
        if (close.size < 2) return null
        val ranked = close.map { it to artistNames(it.artist).size }
        val topCredits = ranked.maxOfOrNull { it.second } ?: return null


        if (topCredits < 2) return null
        val winners = ranked.filter { it.second == topCredits }.map { it.first }
        return winners.singleOrNull()
    }










    fun withinSeconds(candidate: Song, target: Target, seconds: Int): Boolean {
        val wanted = target.durationSec ?: return false
        val got = secondsOf(candidate.durationText) ?: return false
        return abs(wanted - got) <= seconds
    }


    fun matches(candidate: Song, title: String, artist: String, durationSec: Int? = null): Boolean =
        score(candidate, Target(title, artist, durationSec)) != null







    internal data class TitleParts(

        val words: List<String>,

        val core: String,

        val versions: Set<String>,

        val context: Set<String>,
    )

    internal fun parseTitle(raw: String, artist: String = ""): TitleParts {





        val versions = mutableSetOf<String>()
        val context = mutableSetOf<String>()
        var text = raw.lowercase().replace("&", " and ")



        repeat(BRACKET_PASSES) {
            text = BRACKETED.replace(text) { match: MatchResult ->
                classify(match.groupValues[1], versions, context)
                " "
            }
        }

        repeat(DASH_PASSES) {
            text = DASH.replace(text) { match: MatchResult ->
                val segment = match.value.trim('-', '–', '—', '|', ' ').trim()
                if (isArtistName(segment, artist)) {
                    " "
                } else {
                    classify(segment, versions, context)
                    " "
                }
            }
        }

        text = FEATURING.replace(text, " ")



        val trailing = TRAILING_NOISE
        while (true) {
            val words = text.trim().split(WORD_SPLIT).filter { it.isNotEmpty() }
            val last = words.lastOrNull()?.replace(NON_ALNUM, "") ?: break
            if (words.size <= 1 || last !in trailing) break
            text = words.dropLast(1).joinToString(" ")
        }

        val words = text.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() && it !in NOISE_WORDS }

        val core = words.filter { it !in JOINING_WORDS }.joinToString("")

        return TitleParts(
            words = words,
            core = core,
            versions = versions.toSet(),
            context = context,
        )
    }











    private fun classify(segment: String, versions: MutableSet<String>, context: MutableSet<String>) {
        val words = segment.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        if (words.joinToString("") in NEUTRAL_SEGMENTS) return
        val marks = words.filter { it in VERSION_WORDS }
        if (marks.isNotEmpty()) {
            versions += marks
            return
        }
        context += words.filter { it.length > 2 && it !in NOISE_WORDS }
    }


    private fun isArtistName(text: String, artist: String): Boolean {
        if (artist.isBlank()) return false
        val words = text.split(WORD_SPLIT).map { it.replace(NON_ALNUM, "") }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val credited = artist.lowercase().split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
            .toSet()
        return words.all { it in credited }
    }
















    private fun artistScore(wanted: String, got: String): Int? {
        val want = artistNames(wanted)
        val have = artistNames(got)
        if (want.isEmpty() || have.isEmpty()) return 0
        if (!sharesArtist(wanted, got)) return null
        return if (want == have) ARTIST_EXACT else ARTIST_SHARED
    }










    internal fun artistNames(value: String): Set<List<String>> = value
        .lowercase()
        .split(ARTIST_SEPARATORS)
        .map { name ->
            name.split(WORD_SPLIT)
                .map { it.replace(NON_ALNUM, "") }
                .filter { it.length > 1 }
        }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun sameArtist(a: List<String>, b: List<String>): Boolean =
        runOf(a, b) || runOf(b, a)


    private fun runOf(outer: List<String>, inner: List<String>): Boolean {
        if (inner.isEmpty() || inner.size > outer.size) return false
        return (0..outer.size - inner.size).any { at ->
            outer.subList(at, at + inner.size) == inner
        }
    }











    private fun durationScore(wanted: Int?, got: Int?, allowVideoDrift: Boolean = false): Int? {
        if (wanted == null || got == null) return 0
        val drift = abs(wanted - got)
        return when {
            drift > DURATION_LIMIT_SEC && allowVideoDrift && drift <= VIDEO_DURATION_LIMIT_SEC -> 0
            drift > DURATION_LIMIT_SEC -> null
            drift <= DURATION_TIGHT_SEC -> DURATION_TIGHT
            else -> DURATION_LOOSE
        }
    }










    private fun albumScore(wanted: String?, got: String?): Int {
        val want = albumKey(wanted) ?: return 0
        val have = albumKey(got) ?: return 0
        return if (want == have) ALBUM_EXACT else 0
    }






    private fun explicitScore(wanted: Boolean?, got: Boolean?): Int? = when {
        wanted == null || got == null -> 0
        wanted != got -> null
        else -> EXPLICIT_EXACT
    }


    private fun albumKey(value: String?): String? {
        var text = value?.lowercase()?.trim().orEmpty()
        if (text.isEmpty()) return null
        repeat(BRACKET_PASSES) { text = BRACKETED.replace(text, " ") }
        val words = text.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() && it !in ALBUM_NOISE_WORDS }
        return words.joinToString("").takeIf { it.isNotEmpty() }
    }


    internal fun secondsOf(text: String?): Int? {
        val parts = text?.trim()?.split(':')?.takeIf { it.size in 2..3 } ?: return null
        val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
        return numbers.fold(0) { total, part -> total * 60 + part }.takeIf { it > 0 }
    }




    private fun contextScore(wanted: TitleParts, got: TitleParts): Int =
        if (wanted.context.any { it in got.context }) CONTEXT_SHARED else 0





    fun isSevereMismatch(expectedSec: Int?, actualSec: Int?): Boolean {
        if (expectedSec == null || actualSec == null) return false
        return abs(actualSec - expectedSec) > DURATION_LIMIT_SEC
    }




    private const val BASE = 100
    private const val ARTIST_EXACT = 25
    private const val ARTIST_SHARED = 10







    private const val CREDITS_DISAGREE = -30






    private const val CREDIT_OVERRIDE_SEC = 2
    private const val DURATION_TIGHT = 40
    private const val DURATION_LOOSE = 15
    private const val ALBUM_EXACT = 35
    private const val EXPLICIT_EXACT = 20
    private const val CONTEXT_SHARED = 20


    private const val DURATION_TIGHT_SEC = 3






    const val DURATION_LIMIT_SEC = 30


    private const val VIDEO_DURATION_LIMIT_SEC = 90

    private const val BRACKET_PASSES = 3
    private const val DASH_PASSES = 3

    private val BRACKETED = Regex("""[(\[]([^()\[\]]*)[)\]]""")
    private val DASH = Regex("""\s+[-–—|]+\s+""")
    private val FEATURING = Regex("""\b(feat|ft|featuring|with)\b.*""")
    private val WORD_SPLIT = Regex("""[\s.·]+""")
    private val NON_ALNUM = Regex("""[^a-z0-9]""")
    private val ARTIST_SEPARATORS =
        Regex("""\s*(?:[,&/;·|]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""")






    private val VERSION_WORDS = setOf(
        "remix", "remixes", "rmx", "refix", "flip", "bootleg", "mashup", "medley",
        "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke",



        "vocals", "vocal", "acapella", "acappella", "backing", "stems", "stem",
        "cover", "demo", "reprise", "remake", "rework", "extended", "edit",
        "version", "mix", "dub", "vip", "session", "sessions",
        "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "symphonic",
        "part", "pt", "chapter",
    )






    private val NEUTRAL_SEGMENTS = setOf(
        "albumversion", "originalversion", "originalmix", "singleversion",
        "radioversion", "radioedit", "stereoversion", "monoversion",
        "studioversion", "fullversion", "standardversion", "explicitversion",
        "deluxeversion", "originaltrack",
    )


    private val NOISE_WORDS = setOf(
        "official", "video", "audio", "lyrics", "lyric", "lyrical", "visualizer",
        "song", "songs", "full", "music", "the", "and", "from", "feat", "ft",
        "featuring", "with", "new", "latest", "free", "download", "remaster",
        "remastered", "explicit", "clean", "bonus", "track", "deluxe", "original",
        "album", "single", "hd", "hq", "4k", "mp3",
    )

    private val ALBUM_NOISE_WORDS = setOf(
        "album", "deluxe", "edition", "expanded", "remaster", "remastered",
        "version", "explicit", "clean", "bonus", "anniversary",
    )


    private val TRAILING_NOISE = setOf(
        "song", "songs", "video", "audio", "lyrics", "lyric", "lyrical",
        "official", "full", "hd", "hq", "4k", "mp3", "ost", "soundtrack",
    )


    private val JOINING_WORDS = setOf("and")
}
