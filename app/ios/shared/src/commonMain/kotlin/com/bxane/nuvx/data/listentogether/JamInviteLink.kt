package com.bxane.nuvx.data.listentogether










data class ParsedJamInvite(
    val code: String,
    val serverUrl: String? = null,
)
















object JamInviteLink {

    const val ORIGIN = "https://nuvx.lol"

    const val HOST = "nuvx.lol"
    const val CUSTOM_SCHEME = "nuvx"
    const val CUSTOM_HOST = "party"








    const val CODE_LENGTH = 6








    fun parseInvite(value: String?): ParsedJamInvite? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val scheme = raw.substringBefore("://", "").lowercase()
        val host = raw.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()
        if (scheme.isEmpty() || host.isEmpty()) return null

        val path = raw.substringAfter("://", "").substringAfter('/', "").substringBefore('?')
        val query = raw.substringAfter('?', "").substringBefore('#')
        val server = sanitizeServerUrl(queryParam(query, "server"))


        if (scheme == CUSTOM_SCHEME && host == CUSTOM_HOST) {
            val fromPath = path.trim('/').takeIf { it.isNotEmpty() }
            val candidate = fromPath ?: queryParam(query, "code") ?: return null
            val code = cleanCode(candidate) ?: return null
            return ParsedJamInvite(code = code, serverUrl = server)
        }


        if (scheme == "https" && host == HOST) {
            val match = INVITE_PATH.matchEntire(path) ?: return null
            return ParsedJamInvite(code = match.groupValues[1].uppercase(), serverUrl = server)
        }

        return null
    }


    fun looksLikeInvite(value: String?): Boolean {
        val raw = value?.trim().orEmpty()
        val scheme = raw.substringBefore("://", "").lowercase()
        val host = raw.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()
        return (scheme == CUSTOM_SCHEME && host == CUSTOM_HOST) || (scheme == "https" && host == HOST)
    }










    internal fun cleanCode(raw: String): String? {
        val cleaned = raw.filter { it.isLetterOrDigit() }.uppercase()
        return cleaned.takeIf { it.length == CODE_LENGTH }
    }








    internal fun sanitizeServerUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null





        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val host = withScheme.substringAfter("://", "").substringBefore('/').substringBefore('?')
        if (host.isBlank() || host.contains(' ')) return null
        return withScheme.trimEnd('/').ifEmpty { withScheme }
    }


    internal fun queryParam(query: String, name: String): String? {
        if (query.isBlank()) return null
        return query.split('&').asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0].equals(name, ignoreCase = true) }
            ?.get(1)
            ?.let { percentDecode(it) }
    }








    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex != null) {
                    out.append(hex.toChar())
                    index += 3
                    continue
                }
            }


            out.append(ch)
            index++
        }
        return out.toString()
    }

    private val INVITE_PATH = Regex("""^/?invite/([A-Za-z0-9]{$CODE_LENGTH})/?$""")
}
