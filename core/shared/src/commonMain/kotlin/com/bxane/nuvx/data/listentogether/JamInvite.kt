package com.bxane.nuvx.data.listentogether

object JamInvite {

    const val ORIGIN = "https://nuvx.lol"

    const val CODE_LENGTH = 6

    fun parse(value: String?): String? {
        val text = value?.trim() ?: return null
        val prefix = "$ORIGIN/invite/"
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        val code = text.removePrefix(prefix).substringBefore('?').substringBefore('#')
        if (code.length != CODE_LENGTH || !code.all(Char::isLetterOrDigit)) return null
        return code.uppercase()
    }

    fun url(code: String): String = "$ORIGIN/invite/${code.uppercase()}"
}
