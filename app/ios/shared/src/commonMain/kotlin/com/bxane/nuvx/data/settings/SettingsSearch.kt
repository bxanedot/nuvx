package com.bxane.nuvx.data.settings







enum class SettingsMatch {

    EXACT,


    TITLE,





    TERM,


    NONE,

    ;











    val strength: Int
        get() = when (this) {
            EXACT -> 3
            TITLE -> 2
            TERM -> 1
            NONE -> 0
        }
}










object SettingsSearch {










    fun match(query: String, title: String, terms: List<String>): SettingsMatch {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return SettingsMatch.TITLE
        val lowerTitle = title.lowercase()
        if (lowerTitle == needle) return SettingsMatch.EXACT


        if (lowerTitle.contains(needle)) return SettingsMatch.TITLE
        val found = terms.any { term -> term.lowercase().contains(needle) }
        return if (found) SettingsMatch.TERM else SettingsMatch.NONE
    }







    fun matches(query: String, title: String, terms: List<String>): Boolean =
        match(query, title, terms) != SettingsMatch.NONE











    fun bestOf(
        query: String,
        entries: List<Pair<String, List<String>>>,
    ): Pair<Int, SettingsMatch>? {
        var bestIndex = -1




        var best: SettingsMatch? = null
        entries.forEachIndexed { index, entry ->
            val found = match(query, entry.first, entry.second)
            if (found == SettingsMatch.NONE) return@forEachIndexed
            if (best == null || found.strength > best!!.strength) {
                best = found
                bestIndex = index
            }
        }
        return bestIndex.takeIf { it >= 0 }?.let { it to best!! }
    }
}
