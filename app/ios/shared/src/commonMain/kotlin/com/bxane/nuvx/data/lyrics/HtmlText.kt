package com.bxane.nuvx.data.lyrics





























internal object HtmlText {








    fun textOfFirstElement(
        html: String,
        tag: String,
        wanted: (String) -> Boolean,
        skipWhen: (String) -> Boolean = { false },
        blockTags: Set<String> = DEFAULT_BLOCK_TAGS,
    ): String? {
        val region = regionOfFirstElement(html, tag, wanted) ?: return null
        return toText(region, skipWhen, blockTags)
    }


    private fun regionOfFirstElement(
        html: String,
        tag: String,
        wanted: (String) -> Boolean,
    ): String? {
        val open = Regex(
            """<$tag\b([^>]*?)/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )


        for (candidate in open.findAll(html)) {
            val attributes = candidate.groupValues[1]
            if (!wanted(attributes)) continue

            if (candidate.value.trimEnd().endsWith("/>")) return ""
            val index = candidate.range.last + 1



            val scanner = Regex(
                """<(/?)$tag\b[^>]*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            var depth = 1
            for (match in scanner.findAll(html, index)) {
                if (match.value.trimStart().startsWith("</")) {
                    depth--
                    if (depth == 0) return html.substring(index, match.range.first)
                } else {
                    depth++
                }
            }


            return html.substring(index)
        }
        return null
    }


    fun toText(
        region: String,
        skipWhen: (String) -> Boolean = { false },
        blockTags: Set<String> = DEFAULT_BLOCK_TAGS,
    ): String {
        val out = StringBuilder()

        var skipping: String? = null
        var skipDepth = 0

        val tagPattern = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)\b([^>]*)>""", RegexOption.DOT_MATCHES_ALL)
        var cursor = 0
        for (match in tagPattern.findAll(region)) {
            if (skipping == null) {
                out.append(decodeEntities(region.substring(cursor, match.range.first)))
            }
            cursor = match.range.last + 1

            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase()
            val attributes = match.groupValues[3]



            if (name in CODE_TAGS) {
                if (skipping == null && !closing) {
                    skipping = name
                    skipDepth = 1
                } else if (skipping != null) {
                    if (name == skipping) skipDepth += if (closing) -1 else 1
                    if (skipDepth <= 0) skipping = null
                }
                continue
            }

            if (skipping != null) {
                if (name == skipping) skipDepth += if (closing) -1 else 1
                if (skipDepth <= 0) skipping = null
                continue
            }
            if (!closing && skipWhen(attributes)) {
                skipping = name
                skipDepth = 1
                continue
            }
            if (skipping != null) continue
            when {
                name == "br" -> out.append('\n')
                closing && name in blockTags -> out.append('\n')
                !closing && name in blockTags -> out.append('\n')
            }
        }
        if (skipping == null) out.append(decodeEntities(region.substring(cursor)))
        return out.toString()
    }








    fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        var out = value
        for ((entity, replacement) in ENTITIES) {
            if (entity in out) out = out.replace(entity, replacement)
        }
        return decodeNumericEntities(out)
    }

    private fun decodeNumericEntities(value: String): String {
        if ("&#" !in value) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch != '&' || index + 2 >= value.length || value[index + 1] != '#') {
                out.append(ch)
                index++
                continue
            }
            val hex = index + 3 < value.length &&
                (value[index + 2] == 'x' || value[index + 2] == 'X')
            val digitsFrom = index + (if (hex) 3 else 2)
            var end = digitsFrom
            while (end < value.length && value[end].isLetterOrDigit()) end++
            if (end == digitsFrom || (end < value.length && value[end] != ';')) {
                out.append(ch)
                index++
                continue
            }
            val code = value.substring(digitsFrom, end).toIntOrNull(16.takeIf { hex } ?: 10)
            if (code == null || code !in 1..0x10FFFF) {
                out.append(ch)
                index++
                continue
            }
            out.appendCodePointCompat(code)
            index = end + 1
        }
        return out.toString()
    }

    private fun StringBuilder.appendCodePointCompat(code: Int) {
        if (code < 0x10000) {
            append(code.toChar())
        } else {
            val adjusted = code - 0x10000
            append((0xD800 + (adjusted shr 10)).toChar())
            append((0xDC00 + (adjusted and 0x3FF)).toChar())
        }
    }

    private val ENTITIES = listOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—",
        "&ndash;" to "–", "&hellip;" to "…", "&rsquo;" to "’", "&lsquo;" to "‘",
        "&ldquo;" to "“", "&rdquo;" to "”",
    )


    private val CODE_TAGS = setOf("script", "style", "noscript", "template")


    val DEFAULT_BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
        "section", "blockquote", "pre", "figure",
    )
}
