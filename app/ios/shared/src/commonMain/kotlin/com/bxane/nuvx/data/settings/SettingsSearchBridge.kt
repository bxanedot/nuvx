package com.bxane.nuvx.data.settings














object SettingsSearchBridge {









    fun matches(query: String, title: String, termsCsv: String): Boolean =
        SettingsSearch.matches(query, title, terms(termsCsv))









    fun qualityOf(query: String, title: String, termsCsv: String): String =
        SettingsSearch.match(query, title, terms(termsCsv)).name

    private fun terms(csv: String): List<String> =
        if (csv.isEmpty()) emptyList() else csv.split(SEPARATOR)








    private const val SEPARATOR = "\u001F"
}
