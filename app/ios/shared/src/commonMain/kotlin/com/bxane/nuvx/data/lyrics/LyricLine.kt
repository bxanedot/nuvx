package com.bxane.nuvx.data.lyrics

import kotlinx.serialization.Serializable


@Serializable
data class LyricWordDto(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)











@Serializable
data class LyricLineDto(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWordDto> = emptyList(),
    val sungUntilMs: Long? = null,
    val background: LyricLineDto? = null,








    val alignment: LyricAlignment = LyricAlignment.Start,
) {
    val isGap: Boolean get() = text.isEmpty()

    val wordSynced: Boolean get() = words.isNotEmpty()

    val isWordSynced: Boolean get() = wordSynced

    val hasKnownEnd: Boolean get() = words.isNotEmpty() || sungUntilMs != null

    val endMs: Long
        get() {
            val lead = words.lastOrNull()?.endMs ?: sungUntilMs ?: timeMs
            return maxOf(lead, background?.endMs ?: lead)
        }
}
