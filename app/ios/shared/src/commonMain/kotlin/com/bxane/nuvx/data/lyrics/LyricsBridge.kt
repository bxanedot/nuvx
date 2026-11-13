package com.bxane.nuvx.data.lyrics

import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable







object LyricsBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun interface LyricsCallback {
        fun onResult(lines: List<LyricLineDto>)
    }


    fun interface AttributedLyricsCallback {
        fun onResult(source: String, sourceLabel: String, lines: List<LyricLineDto>)
    }





    enum class LyricsProviderState { NOT_FETCHED, FETCHING, FOUND, NOT_FOUND, BLOCKED }


    fun interface SourceStateCallback {
        fun onSourceState(source: String, sourceLabel: String, state: LyricsProviderState, lines: List<LyricLineDto>)
    }















    fun fetchWithSourceStates(
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        videoId: String?,
        localPath: String?,
        callback: AttributedLyricsCallback,
        onSourceState: SourceStateCallback,
    ) {
        scope.launch {




            val lock = Mutex()
            val started = mutableSetOf<String>()
            val settled = mutableSetOf<String>()
            suspend fun report(source: LyricsSource, state: LyricsProviderState, lines: List<LyricLineDto> = emptyList()) {
                val fresh = lock.withLock {
                    when (state) {
                        LyricsProviderState.FETCHING -> started.add(source.name)
                        LyricsProviderState.FOUND, LyricsProviderState.NOT_FOUND, LyricsProviderState.BLOCKED -> settled.add(source.name)
                        LyricsProviderState.NOT_FETCHED -> false
                    }
                }
                if (fresh) onSourceState.onSourceState(source.name, source.label, state, lines)
            }
            val (source, lines) = runCatching {
                lookup(
                    title, artist, durationMs, album, videoId, localPath,
                    onSourceStarted = { report(it, LyricsProviderState.FETCHING) },
                    onSourceResult = { source, result ->
                        if (result != null) report(source, LyricsProviderState.FOUND, result.lines)
                        else report(source, LyricsProviderState.NOT_FOUND)
                    },
                    onSourceCancelled = { report(it, LyricsProviderState.NOT_FETCHED) },
                    onSourceBlocked = { report(it, LyricsProviderState.BLOCKED) },
                )
            }.getOrElse { null to emptyList() }
            callback.onResult(source?.name.orEmpty(), source?.label.orEmpty(), lines)
        }
    }

    fun fetch(title: String, artist: String, durationMs: Long, callback: LyricsCallback) {
        fetch(title, artist, durationMs, album = null, callback)
    }

    fun fetch(
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        callback: LyricsCallback,
    ) = fetch(title, artist, durationMs, album, videoId = null, callback)

    fun fetch(
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        videoId: String?,
        callback: LyricsCallback,
    ) = fetchAttributed(title, artist, durationMs, album, videoId, localPath = null) { _, _, lines ->
        callback.onResult(lines)
    }





    fun fetchAttributed(
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        videoId: String?,
        localPath: String?,
        callback: AttributedLyricsCallback,
    ) {
        scope.launch {
            val (source, lines) = runCatching {
                lookup(title, artist, durationMs, album, videoId, localPath)
            }.getOrElse { null to emptyList() }
            callback.onResult(source?.name.orEmpty(), source?.label.orEmpty(), lines)
        }
    }


    fun fetchEmbedded(path: String, callback: LyricsCallback) {
        scope.launch {
            val lines = runCatching { EmbeddedLyrics.forPath(path) }.getOrNull().orEmpty()
            callback.onResult(lines)
        }
    }

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        videoId: String? = null,
    ): List<LyricLineDto> = lookup(title, artist, durationMs, album, videoId, localPath = null).second

    private suspend fun lookup(
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        videoId: String?,
        localPath: String?,
        onSourceStarted: (suspend (LyricsSource) -> Unit)? = null,
        onSourceResult: (suspend (LyricsSource, LyricsRepository.Result?) -> Unit)? = null,
        onSourceCancelled: (suspend (LyricsSource) -> Unit)? = null,
        onSourceBlocked: (suspend (LyricsSource) -> Unit)? = null,
    ): Pair<LyricsSource?, List<LyricLineDto>> {
        if (!localPath.isNullOrBlank()) {
            EmbeddedLyrics.forPath(localPath)?.let { return null to it }
        }
        if (!AppSettings.syncedLyrics.value) return null to emptyList()
        val sources = AppSettings.lyricsSourcesSet()
        if (sources.isEmpty()) return null to emptyList()





        val found = LyricsRepository.lyrics(
            videoId = videoId.orEmpty(),
            title = title,
            artist = artist,
            durationMs = durationMs,
            album = album,
            sources = sources,
            order = AppSettings.lyricsSourceOrderList(),
            prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
            onSourceStarted = onSourceStarted,
            onSourceResult = onSourceResult,
            onSourceCancelled = onSourceCancelled,
            onSourceBlocked = onSourceBlocked,
        )
        if (found != null && found.lines.isNotEmpty()) {
            return found.source to found.lines
        }



        if (title.any { isNonLatinLetter(it) } || artist.any { isNonLatinLetter(it) }) {
            val romanizedTitle = LyricsTranslation.romanizeText(title) ?: title
            val romanizedArtist = LyricsTranslation.romanizeText(artist) ?: artist
            if (romanizedTitle != title || romanizedArtist != artist) {
                com.bxane.nuvx.data.DebugLog.d("lyrics F13: retrying with romanised query '$romanizedTitle' - '$romanizedArtist'")
                val secondPass = LyricsRepository.lyrics(
                    videoId = "",
                    title = romanizedTitle,
                    artist = romanizedArtist,
                    durationMs = durationMs,
                    album = album,
                    sources = sources,
                    order = AppSettings.lyricsSourceOrderList(),
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )
                if (secondPass != null && secondPass.lines.isNotEmpty()) {
                    return secondPass.source to secondPass.lines
                }
            }
        }
        return found?.source to found?.lines.orEmpty()
    }












    fun translate(
        trackId: String,
        linesJson: String,
        targetLanguageTag: String,
        callback: TranslationCallback,
    ) {
        scope.launch {
            val lines = decodeLines(linesJson)
            val result = if (lines.isEmpty()) {
                LyricsTranslation.Result.Unavailable
            } else {
                runCatching { LyricsTranslation.translate(trackId, lines, targetLanguageTag) }
                    .getOrElse { LyricsTranslation.Result.Unavailable }
            }
            when (result) {
                is LyricsTranslation.Result.Translated -> callback.onResult(
                    status = "translated",
                    document = encodeLines(result.lines),
                    sourceLanguage = result.sourceLanguage,
                    fromCache = result.fromCache,
                )
                is LyricsTranslation.Result.SameLanguage -> callback.onResult(
                    status = "same",
                    document = linesJson,
                    sourceLanguage = result.language,
                    fromCache = true,
                )
                LyricsTranslation.Result.Unavailable -> callback.onResult(
                    status = "unavailable", document = "", sourceLanguage = "", fromCache = false,
                )
            }
        }
    }








    fun romanize(
        trackId: String,
        linesJson: String,
        targetLanguageTag: String,
        callback: TranslationCallback,
    ) {
        scope.launch {
            val lines = decodeLines(linesJson)
            val result = if (lines.isEmpty()) {
                LyricsTranslation.RomanizationResult.Unavailable
            } else {
                runCatching { LyricsTranslation.romanize(trackId, lines, targetLanguageTag) }
                    .getOrElse { LyricsTranslation.RomanizationResult.Unavailable }
            }
            when (result) {
                is LyricsTranslation.RomanizationResult.Romanized -> callback.onResult(
                    status = "translated",
                    document = encodeLines(result.lines),
                    sourceLanguage = result.sourceLanguage,
                    fromCache = result.fromCache,
                )


                LyricsTranslation.RomanizationResult.AlreadyRomanized -> callback.onResult(
                    status = "same", document = linesJson, sourceLanguage = "", fromCache = true,
                )
                LyricsTranslation.RomanizationResult.Unavailable -> callback.onResult(
                    status = "unavailable", document = "", sourceLanguage = "", fromCache = false,
                )
            }
        }
    }








    fun languagesJson(): String {
        val translate = TRANSLATION_LANGUAGES.joinToString(",", prefix = "[", postfix = "]") {
            """{"code":${quote(it.code)},"name":${quote(it.fallbackName)},"romanizable":false}"""
        }
        val romanize = ROMANIZATION_LANGUAGES.joinToString(",", prefix = "[", postfix = "]") {
            """{"code":${quote(it.code)},"name":${quote(it.fallbackName)},"romanizable":true}"""
        }
        return """{"translate":$translate,"romanize":$romanize}"""
    }

    fun clearTranslationCache() = TranslationCache.clear()

    private fun decodeLines(document: String): List<LyricLineDto> = runCatching {
        lyricsJson.decodeFromString(LyricLineList.serializer(), document).lines
    }.getOrDefault(emptyList())

    private fun encodeLines(lines: List<LyricLineDto>): String = runCatching {
        lyricsJson.encodeToString(LyricLineList.serializer(), LyricLineList(lines))
    }.getOrDefault(EMPTY_LINES)

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code < 0x20 -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }
}


@Serializable
internal data class LyricLineList(val lines: List<LyricLineDto> = emptyList())

private const val EMPTY_LINES = """{"lines":[]}"""


interface TranslationCallback {









    fun onResult(
        status: String,
        document: String,
        sourceLanguage: String,
        fromCache: Boolean,
    )
}
