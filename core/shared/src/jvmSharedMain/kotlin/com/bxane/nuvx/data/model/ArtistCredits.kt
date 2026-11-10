package com.bxane.nuvx.data.model

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

fun normalizedArtistName(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
        .replace(REPEATED_SPACE, " ")
        .trim()

private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")

private val REPEATED_SPACE = Regex("\\s+")

fun isExactArtistMatch(answer: String, wanted: String): Boolean {
    val name = normalizedArtistName(wanted)
    if (name.isEmpty()) return false
    return normalizedArtistName(answer) == name
}

data class ArtistCredit(
    val name: String,
    val browseId: String? = null,
)

data class CreditSegment(
    val text: String,
    val credit: ArtistCredit? = null,
)

fun creditSegments(text: String, credits: List<ArtistCredit>): List<CreditSegment> {
    val names = credits
        .filter { it.name.isNotBlank() }
        .distinctBy { normalizedArtistName(it.name) }
        .sortedByDescending { it.name.length }
    if (text.isEmpty()) return emptyList()
    if (names.isEmpty()) return listOf(CreditSegment(text))

    val out = mutableListOf<CreditSegment>()
    val plain = StringBuilder()
    var index = 0
    while (index < text.length) {
        val hit = names.firstOrNull { text.startsWithIgnoringCase(it.name, index) }
        if (hit == null) {
            plain.append(text[index])
            index++
            continue
        }
        if (plain.isNotEmpty()) {
            out += CreditSegment(plain.toString())
            plain.clear()
        }
        out += CreditSegment(text.substring(index, index + hit.name.length), hit)
        index += hit.name.length
    }
    if (plain.isNotEmpty()) out += CreditSegment(plain.toString())
    return out
}

private fun String.startsWithIgnoringCase(prefix: String, at: Int): Boolean =
    regionMatches(at, prefix, 0, prefix.length, ignoreCase = true)

object ArtistNameIndex {
    private val channels = ConcurrentHashMap<String, MutableSet<String>>()
    private val joiners = ConcurrentHashMap.newKeySet<String>()
    private val joinerTexts = ConcurrentHashMap.newKeySet<String>()
    private val joinerSightings = ConcurrentHashMap<String, Int>()

    fun record(refs: List<ArtistRef>) {
        refs.forEach { ref ->
            val name = normalizedArtistName(ref.name)
            val id = ref.browseId
            if (name.isEmpty() || id.isNullOrBlank()) return@forEach
            channels.computeIfAbsent(name) { ConcurrentHashMap.newKeySet() }.add(id)
        }
    }

    fun recordJoiners(segments: List<CreditSegment>) {
        val creditAt = segments.withIndex().filter { it.value.credit != null }.map { it.index }
        if (creditAt.size < 2) return
        val first = creditAt.first()
        val last = creditAt.last()
        for (index in first + 1..last) {
            val segment = segments[index]
            if (segment.credit != null) continue
            val key = normalizedArtistName(segment.text)
            if (key.isEmpty()) continue
            if (segment.text.any { it.isLetterOrDigit() }) {
                if (key.any { it.isWhitespace() } || key.any { it.isDigit() }) continue
                val seen = joinerSightings.merge(key, 1, Int::plus) ?: 1
                if (seen < JOINER_WORD_SIGHTINGS) continue
            }
            joiners.add(key)
            joinerTexts.add(segment.text.trim())
        }
    }

    fun lookup(name: String): String? {
        val ids = channels[normalizedArtistName(name)] ?: return null
        return ids.singleOrNull()
    }

    fun isJoiner(text: String): Boolean = normalizedArtistName(text) in joiners

    fun joinerTexts(): Set<String> = joinerTexts

    fun forget() {
        channels.clear()
        joiners.clear()
        joinerTexts.clear()
        joinerSightings.clear()
    }
}

fun completeArtistCredits(
    line: String,
    stated: List<ArtistRef>,
): List<ArtistRef> {
    if (line.isBlank() || stated.isEmpty()) return stated
    val named = stated.filter { it.name.isNotBlank() }
    val segments = creditSegments(line, named.map { ArtistCredit(it.name, it.browseId) })
    ArtistNameIndex.recordJoiners(segments)

    val used = mutableSetOf<String>()
    named.forEach { ref -> ref.browseId?.let(used::add) }
    val cutter = fragmentCutter()
    val out = mutableListOf<ArtistRef>()
    val placed = mutableSetOf<String>()

    segments.forEach { segment ->
        val credit = segment.credit
        if (credit != null) {
            val ref = named.firstOrNull { it.name.equals(credit.name, ignoreCase = true) }
                ?: ArtistRef(credit.name, credit.browseId)
            if (placed.add(normalizedArtistName(ref.name))) out += ref
            return@forEach
        }
        fragmentsOf(segment.text, cutter).forEach { text ->
            if (ArtistNameIndex.isJoiner(text)) return@forEach
            val id = ArtistNameIndex.lookup(text) ?: return@forEach
            if (!used.add(id)) return@forEach
            if (placed.add(normalizedArtistName(text))) out += ArtistRef(name = text, browseId = id)
        }
    }

    named.forEach { ref ->
        if (placed.add(normalizedArtistName(ref.name))) out += ref
    }
    return out
}

private fun fragmentsOf(stretch: String, cutter: Regex): List<String> =
    stretch.split(cutter)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private fun fragmentCutter(): Regex {
    val words = ArtistNameIndex.joinerTexts().filter { it.isNotBlank() }
    if (words.isEmpty()) return JOINER
    val anchored = words.map { word -> "(?:^|(?<=\\s))\\s*${Regex.escape(word)}\\s*(?=\\s|\$)" }
    return Regex((anchored + listOf(JOINER.pattern)).joinToString("|"), RegexOption.IGNORE_CASE)
}

private val JOINER = Regex("""\s*,\s*|\s*，\s*|\s+&\s+|\s+＆\s+|\s*•\s*|\s*·\s*|\s*・\s*|\s*、\s*""")

val CreditJoiner = JOINER

private const val JOINER_WORD_SIGHTINGS = 2
