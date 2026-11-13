package com.bxane.nuvx.data.lyrics

import com.bxane.nuvx.data.http.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive





















object LyricsTranslation {

    sealed interface Result {







        data class Translated(
            val lines: List<LyricLineDto>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : Result


        data class SameLanguage(val language: String) : Result


        data object Unavailable : Result
    }

    sealed interface RomanizationResult {
        data class Romanized(
            val lines: List<LyricLineDto>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : RomanizationResult


        data object AlreadyRomanized : RomanizationResult

        data object Unavailable : RomanizationResult
    }

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val INPUT_TOOLS_ENDPOINT = "https://inputtools.google.com/request"








    private const val CACHE_VERSION = 4

    private const val MAX_BATCH_CHARS = 3_500
    private const val MAX_PARALLEL_REQUESTS = 2
    private const val TRANSLATION_AGENT = "Nuvx/1.6.1"

    private val markerRegex = Regex("[^]*")
    private val json = Json { ignoreUnknownKeys = true }

    private data class TextSlot(
        val lineIndex: Int,
        val background: Boolean,
        val text: String,
        val sectionHeader: Boolean,
    )

    private data class Batch(
        val slots: List<TextSlot>,
        val payload: String,
    )

    private data class BatchAnswer(
        val translations: List<String>,
        val sourceLanguage: String,
        val sourceWeight: Int,
    )



    suspend fun translate(
        trackId: String,
        lines: List<LyricLineDto>,
        targetLanguageTag: String,
    ): Result {





        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) return Result.Unavailable

        val slots = flatten(lines)
        if (slots.isEmpty()) return Result.Unavailable
        val cacheKey = cacheKey(trackId, target, slots)
        val cached = TranslationCache.get(cacheKey)
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return if (sameLanguage(cached.sourceLanguage, target)) {
                Result.SameLanguage(cached.sourceLanguage)
            } else {
                Result.Translated(
                    lines = rebuild(lines, slots, cached.texts),
                    sourceLanguage = cached.sourceLanguage,
                    fromCache = true,
                )
            }
        }

        val batches = batches(slots)
        val answers = coroutineScope {


            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return Result.Unavailable
        val complete = answers.filterNotNull()
        val source = dominantSource(complete)
        if (source.isBlank()) return Result.Unavailable

        var translated = complete.flatMap { it.translations }
        if (translated.size != slots.size) return Result.Unavailable








        if (
            !sameLanguage(source, target) &&
            predominantlyLatin(slots) &&
            unchangedWeight(slots, translated) * 3 >= slots.sumOf { it.text.length }
        ) {
            val retried = retryRomanizedTranslation(batches, source, target)
            if (
                retried != null &&
                retried.size == slots.size &&
                unchangedWeight(slots, retried) < unchangedWeight(slots, translated)
            ) {
                translated = retried
            }
        }

        TranslationCache.put(
            cacheKey,
            CachedTranslation(
                version = CACHE_VERSION,
                sourceLanguage = source,
                targetLanguage = target,
                texts = translated,
            ),
        )

        return if (sameLanguage(source, target)) {
            Result.SameLanguage(source)
        } else {
            Result.Translated(
                lines = rebuild(lines, slots, translated),
                sourceLanguage = source,
                fromCache = false,
            )
        }
    }



    suspend fun romanize(
        trackId: String,
        lines: List<LyricLineDto>,
        targetLanguageTag: String,
    ): RomanizationResult {
        if (lines.isEmpty()) return RomanizationResult.Unavailable
        val slots = flatten(lines)
        if (slots.isEmpty()) return RomanizationResult.Unavailable


        if (!slots.any { slot -> slot.text.any { isNonLatinLetter(it) } }) {
            return RomanizationResult.AlreadyRomanized
        }





        val cacheKey = cacheKey(trackId, "romanize", slots)
        val cached = TranslationCache.get(cacheKey)
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return RomanizationResult.Romanized(
                lines = rebuild(lines, slots, cached.texts),
                sourceLanguage = cached.sourceLanguage,
                fromCache = true,
            )
        }

        val target = targetLanguageTag.trim().ifBlank { "en" }
        val batches = batches(slots)
        val answers = coroutineScope {
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestRomanizationBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return RomanizationResult.Unavailable
        val complete = answers.filterNotNull()
        val source = dominantSource(complete)
        val romanized = complete.flatMap { it.translations }
        if (source.isBlank() || romanized.size != slots.size) {
            return RomanizationResult.Unavailable
        }
        if (unchangedWeight(slots, romanized) == slots.sumOf { it.text.length }) {



            return RomanizationResult.Unavailable
        }

        TranslationCache.put(
            cacheKey,
            CachedTranslation(
                version = CACHE_VERSION,
                sourceLanguage = source,
                targetLanguage = "Latn",
                texts = romanized,
            ),
        )
        return RomanizationResult.Romanized(
            lines = rebuild(lines, slots, romanized),
            sourceLanguage = source,
            fromCache = false,
        )
    }





    suspend fun romanizeText(text: String, target: String = "en"): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank() || !trimmed.any { isNonLatinLetter(it) }) return null
        val body = postForm(ENDPOINT, mapOf(
            "client" to "dict-chrome-ex",
            "sl" to "auto",
            "tl" to target,
            "dt" to "rm",
            "q" to trimmed,
        )) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonArray
            val romanizedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty()
            }.trim()
            if (romanizedBody.isBlank() || romanizedBody.equals(trimmed, ignoreCase = true)) null else romanizedBody
        }.getOrNull()
    }










    private fun flatten(lines: List<LyricLineDto>): List<TextSlot> = buildList {
        lines.forEachIndexed { index, line ->
            if (line.text.isNotBlank()) {
                val header = isSectionHeader(line.text)
                add(
                    TextSlot(
                        lineIndex = index,
                        background = false,



                        text = if (header) {
                            line.text.removePrefix("[").removeSuffix("]").trim()
                        } else {
                            line.text
                        },
                        sectionHeader = header,
                    ),
                )
            }
            line.background?.takeIf { it.text.isNotBlank() }?.let { background ->
                add(TextSlot(index, background = true, background.text, sectionHeader = false))
            }
        }
    }


    internal fun isSectionHeader(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return false
        val inner = trimmed.removePrefix("[").removeSuffix("]").trim()

        if (inner.isEmpty() || inner.contains('\n')) return false
        return SECTION_HEADER.matches(inner)
    }

    private val SECTION_HEADER =
        Regex("""(?i)\s*(?:verse|chorus|intro|outro|bridge|hook|refrain|prechorus|pre-chorus|interlude|instrumental|breakdown|drop|tag)\b.*""")



    private fun batches(slots: List<TextSlot>): List<Batch> {
        val result = mutableListOf<Batch>()
        var current = mutableListOf<TextSlot>()
        var length = 0

        fun flush() {
            if (current.isEmpty()) return
            result += Batch(current.toList(), payload(current))
            current = mutableListOf()
            length = 0
        }

        slots.forEach { slot ->
            val added = slot.text.length + if (current.isEmpty()) 0 else 8
            if (current.isNotEmpty() && length + added > MAX_BATCH_CHARS) flush()
            current += slot
            length += added
        }
        flush()
        return result
    }

    private fun payload(slots: List<TextSlot>): String = buildString {
        slots.forEachIndexed { index, slot ->
            if (index > 0) append('\n').append(marker(index)).append('\n')
            append(slot.text)
        }
    }









    private fun marker(index: Int): String = "${index.toString().padStart(4, '0')}"



    private suspend fun requestBatch(
        batch: Batch,
        target: String,
        sourceLanguage: String = "auto",
    ): BatchAnswer? {
        val body = postForm(ENDPOINT, mapOf(
            "client" to "dict-chrome-ex",
            "sl" to sourceLanguage,
            "tl" to target,
            "dt" to "t",
            "q" to batch.payload,
        )) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonArray
            val translatedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = translatedBody.split(markerRegex).map { it.trim() }
            if (parts.size != batch.slots.size || parts.any { it.isBlank() }) return null
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private suspend fun requestRomanizationBatch(batch: Batch, target: String): BatchAnswer? {
        val body = postForm(ENDPOINT, mapOf(
            "client" to "dict-chrome-ex",
            "sl" to "auto",
            "tl" to target,
            "dt" to "rm",
            "q" to batch.payload,
        )) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonArray


            val romanizedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = romanizedBody.split(markerRegex).map { it.trim() }
            if (source.isBlank() || parts.size != batch.slots.size || parts.any { it.isBlank() }) {
                return null
            }
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }








    private suspend fun retryRomanizedTranslation(
        batches: List<Batch>,
        sourceLanguage: String,
        target: String,
    ): List<String>? = coroutineScope {
        val source = canonicalLanguage(sourceLanguage)
        if (source.isBlank() || source == "en") return@coroutineScope null
        val answers = batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
            group.map { batch ->
                async {
                    val nativePayload = requestNativeScript(batch.payload, source) ?: return@async null
                    requestBatch(batch.copy(payload = nativePayload), target, source)
                }
            }.awaitAll()
        }
        if (answers.any { it == null }) null else answers.filterNotNull().flatMap { it.translations }
    }

    private suspend fun requestNativeScript(text: String, sourceLanguage: String): String? {
        val body = postForm(INPUT_TOOLS_ENDPOINT, mapOf(
            "text" to text,
            "itc" to "$sourceLanguage-t-i0-und",
            "num" to "1",
            "cp" to "0",
            "cs" to "1",
            "ie" to "utf-8",
            "oe" to "utf-8",
        )) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonArray
            if (root.getOrNull(0)?.jsonPrimitive?.contentOrNull != "SUCCESS") return null
            root[1].jsonArray[0].jsonArray[1].jsonArray[0].jsonPrimitive.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun postForm(url: String, form: Map<String, String>): String? = try {
        withContext(Dispatchers.Default) {
            Http.postForm(url, form, headers = mapOf("User-Agent" to TRANSLATION_AGENT))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }




    private fun dominantSource(answers: List<BatchAnswer>): String = answers
        .groupBy { canonicalLanguage(it.sourceLanguage) }
        .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
        ?.key
        .orEmpty()

    private fun predominantlyLatin(slots: List<TextSlot>): Boolean {
        var latin = 0
        var other = 0
        for (slot in slots) {
            for (ch in slot.text) {
                if (!isLetter(ch)) continue
                if (isLatinLetter(ch)) latin++ else other++
            }
        }
        return latin > 0 && latin >= other * 4
    }









    private fun unchangedWeight(slots: List<TextSlot>, transformed: List<String>): Int =
        slots.zip(transformed).sumOf { (slot, text) ->
            if (comparable(slot.text) == comparable(text)) slot.text.length else 0
        }

    private fun comparable(text: String): String = text
        .trim()
        .lowercase()
        .replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("""\s+""")
















    internal fun sameLanguage(a: String, b: String): Boolean {
        val left = canonicalLanguage(a)
        val right = canonicalLanguage(b)
        return left.isNotBlank() && left == right
    }








    internal fun canonicalLanguage(tag: String): String {
        val base = tag.trim().replace('_', '-').substringBefore('-').lowercase()
        return when (base) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            "mo" -> "ro"

            "nb", "nn" -> "no"
            else -> base
        }
    }










    private fun rebuild(
        original: List<LyricLineDto>,
        slots: List<TextSlot>,
        translated: List<String>,
    ): List<LyricLineDto> {
        val byLine = slots.zip(translated).groupBy { it.first.lineIndex }
        return original.mapIndexed { index, line ->
            val entries = byLine[index].orEmpty()
            val lead = entries.firstOrNull { !it.first.background }
            val backing = entries.firstOrNull { it.first.background }
            val leadText = lead?.let { (slot, text) ->
                if (slot.sectionHeader) "[$text]" else text
            } ?: line.text
            line.copy(
                text = leadText,
                words = retimeWords(line, leadText),
                background = line.background?.let { source ->
                    val text = backing?.second ?: source.text
                    source.copy(text = text, words = retimeWords(source, text))
                },
            )
        }
    }










    private fun retimeWords(source: LyricLineDto, translated: String): List<LyricWordDto> {
        if (source.words.isEmpty()) return emptyList()
        if (translated.isEmpty()) return emptyList()
        val start = source.words.first().startMs
        val end = source.words.last().endMs
        if (end <= start) return emptyList()
        return listOf(LyricWordDto(start, end, translated))
    }











    private fun cacheKey(trackId: String, target: String, slots: List<TextSlot>): String {
        val fingerprint = slots.joinToString("\u0000") { "${it.background}:${it.text}" }
        return "$CACHE_VERSION|$trackId|$target|${fingerprint.length}|${fingerprint.hashCode()}"
    }
}












internal fun isLatinLetter(ch: Char): Boolean {
    val code = ch.code
    return when (code) {
        in 'a'.code..'z'.code -> true
        in 'A'.code..'Z'.code -> true
        in 0x00C0..0x00FF -> true
        in 0x0100..0x017F -> true
        in 0x0180..0x024F -> true
        in 0x1E00..0x1EFF -> true
        in 0x2C60..0x2C7F -> true
        in 0xA720..0xA7FF -> true
        in 0xAB30..0xAB6F -> true
        in 0x0250..0x02AF -> isIpaExtension(code)
        in 0x1D00..0x1D7F -> isIpaExtension(code)
        else -> false
    }
}


private fun isIpaExtension(code: Int): Boolean =
    code in 0x0250..0x02AF || (code in 0x1D00..0x1D7F && code != 0x1D7E && code != 0x1D7F)


internal fun isLetter(ch: Char): Boolean = ch.isLetter()









internal fun isNonLatinLetter(ch: Char): Boolean = ch.isLetter() && !isLatinLetter(ch)
